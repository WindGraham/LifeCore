# Working Memory · 每回合注入模板

> 用途：BFF 在 `state_block()` 中按此模板渲染，作为 `message` 字段的前缀注入。
> 加载位置：`lifecore-server/server.py` 的 `state_block()` 函数；`/v2/state-block` 端点
> 触发：每回合 LLM 调用前（hook：核心 `pre_llm_call` 已被 BFF 拦截）
> 长度预算：`STATE_BUDGET=2500` 字符（环境变量 `LC_STATE_BUDGET` 可调）

---

## 1. 数据源（state_block 渲染输入）

| 段 | 来源 | 查询 | 上限 |
|---|---|---|---|
| `[live]` | `notify_items` 当前 active/queued + `channels` 计数 | `SELECT COUNT(*) WHERE state='queued'`, `bridge_heartbeat.last_seen < 120s ? online : stale` | 200 字 |
| `[user_state]` | `lists/user_state` 活跃项 | `SELECT * WHERE resolved=0 ORDER BY updated_at DESC LIMIT 20` | 600 字 |
| `[running_items]` | `lists/running_items` 活跃项 | 同上 | 400 字 |
| `[schedule]` | `lists/schedule` 未到期项 | 同上 | 400 字 |
| `[threads]` | `notify_threads` 近 7d 议题 | `SELECT thread_key,last_resolution,last_summary,item_count,updated_at WHERE updated_at > now-7d ORDER BY updated_at DESC LIMIT 15` | 600 字 |
| `[user_model]` | `lists/user_model` slot（`confidence≥0.4`） | `SELECT key,value_json,confidence,last_validated_at WHERE name='user_model'` | 300 字 |
| `[services]` | `channels`（非 revoked）+ `bridge_heartbeat.last_seen` | `SELECT name,host,last_seen` | 300 字 |
| `[devices]` | `devices` 表的健康面（v0.2 才用，先 stub） | 占位 0 字 | 0 字 |

> **总预算 ≤ 2500 字**。超限按 `[threads] > [user_model] > 其它` 顺序截断；截断要加 `…(截断)`。

---

## 2. 注入时机与守卫

**触发**：`/v2/sessions/{sid}/chat`、`/chat/stream`、`/v2/sessions/{sid}/messages`（重发场景）三个入口。

**守卫**（`server.py:inject_state` 已有，照搬逻辑）：

```python
def inject_state(raw: bytes) -> bytes:
    body = json.loads(raw or b"{}")
    msg = body.get("message") or body.get("input") or ""
    block = state_block()
    # 守卫 1：只有当 message 不以 [live 开头才注入（避免重入）
    if block and not str(msg).startswith("[live "):
        body["message"] = block + "\n" + msg
    return json.dumps(body, ensure_ascii=False).encode()
```

**严禁**：
- 在 hermes 侧 pre_llm_call shell hook 再注入一次（双注入 = 块翻倍 = 预算爆）
- 把 state_block 拼到 assistant 已输出的回合（只前不后）
- 在用户主动 `@core` 或带 `[no-state]` 前缀的消息中注入

---

## 3. state_block JSON 渲染 Schema（输出形）

```json
{
  "ts": "09-17 14:32",
  "live": "[live 09-17 14:32] 待决策:1(社团招新摊位安排) | 队列:3 | 通道:5 | bridge:vps=online, pixel=stale",
  "user_state": [
    "  now.doing: 在写 docs/14 (09-17 14:20)",
    "  energy.mid: 咖啡 1 杯，10 点后开始疲 (09-17 13:55)"
  ],
  "running_items": [
    "  draft.lifecore.architecture: 等校对 (09-17 12:00)",
    "  pending.tax.q3: 9/30 截止 (09-15 09:00)"
  ],
  "schedule": [
    "  standup: 今日 15:00 (09-17 14:00)",
    "  dinner.wang: 09-19 19:00 王老师 (09-16 18:00)"
  ],
  "threads": [
    "  wx:8832: 社团招新摊位 | 上次:snooze | 第3次 | (09-17 11:20)",
    "  gmail:abc: 会议邀请 周五 | 上次:actioned | 第1次 | (09-16 22:10)",
    "  github:pr-22: feat: 主动外伸 | 上次:actioned | 第2次 | (09-15 09:00)"
  ],
  "user_model": [
    "  preference.notify.window=22:00-08:00 (conf=0.85, n=12)",
    "  preference.tone=short,emoji=minimal (conf=0.78, n=9)",
    "  tolerance.spam.marketing=0 (conf=0.62, n=5)"
  ],
  "services": [
    "  wechat-monitor @ Pixel (online)",
    "  gmail-bridge @ VPS (online)",
    "  filesystem-watch @ PC-home (stale 8m)"
  ]
}
```

> **顺序不可乱**：`[live] → [user_state] → [running_items] → [schedule] → [threads] → [user_model] → [services]`。
> LLM 阅读习惯：先看"现在"，再看"我的画像"，最后看"外部世界"。

---

## 4. 渲染模板（Python 端字符串模板，可直接拷到 `state_block()`）

```python
def state_block() -> str:
    sections = []
    # ① [live] 实时健康面
    sections.append(_live_header())
    # ② 全文清单（user_state / running_items / schedule）
    for lname in FULL_LISTS:                       # ["user_state","running_items","schedule"]
        sections.append(_list_full(lname, limit=20, budget=600))
    # ③ [threads] 议题命运（≤600 字）
    sections.append(_threads_summary(limit=15, days=7, budget=600))
    # ④ [user_model] 长期画像摘要（≤300 字，confidence ≥ 0.4）
    sections.append(_user_model_summary(min_conf=0.4, budget=300))
    # ⑤ [services] 通道 + 心跳
    sections.append(_services_summary(limit=30, budget=300))
    # 截断到 STATE_BUDGET
    block = "\n".join(s for s in sections if s)
    if len(block) > STATE_BUDGET:
        block = block[:STATE_BUDGET] + "\n…(截断)"
    return block
```

> 模板渲染失败（DB 锁/连接断）→ 返回空串 `""`，**绝不抛异常到 LLM 路径**。

---

## 5. 与核心 system prompt 的协作

- 核心 system prompt 永驻（`system.md`）—— 只讲"我是谁"
- 每回合 state_block 注入（`memory_working.md` 渲染）—— 讲"现在发生了什么"
- 续报时刻追加 `thread_continuity.md` —— 讲"上次你说…"
- 三段拼起来 = 核心完整上下文

---

## 6. 边界与降级

| 异常 | 降级 |
|---|---|
| DB 连接超时 | 跳过整个 state_block，返回空串 |
| 单段查询 > 预算 | 截断该段，加 `…(本段截断)` |
| `user_model` 段空 | 不输出该段（不输出空标题） |
| `notify_threads` 全 0 | 输出 `[threads]\n  (无活跃议题)` |
| `[live]` 段拼不出 | 仍输出 `[live UNKNOWN]` 让 LLM 知道注入本身存在 |

> **绝对不在注入块里塞 prompt injection 风险源**：只读 SELECT，不接受用户输入作为 SQL 参数。
