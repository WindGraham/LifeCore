# Contextual Memory · 情境记忆注入模板

> 用途：核心每回合裁决末尾**异步**落一条 `thread_snapshots`；续报时刻按需**读取**本 thread 最近 1-2 条核心自己的总结。
> 设计依据：docs/11 §二（情境记忆）+ docs/13 §3
> 加载位置：MCP 工具 `thread_recent_context(thread_key, n=2)` + 写入由核心裁决末尾触发

---

## 1. 数据源（新建轻量表）

```sql
CREATE TABLE IF NOT EXISTS thread_snapshots(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  thread_id INTEGER NOT NULL,           -- → notify_threads.id
  turn_seq INTEGER NOT NULL,            -- 线程内回合号（1, 2, 3, …）
  role TEXT NOT NULL,                   -- 'core' | 'user' | 'system'
  summary TEXT NOT NULL,                -- ≤300 字符的人话总结
  confidence REAL DEFAULT 1.0,          -- 核心自我置信度
  created_at REAL NOT NULL
);
CREATE INDEX idx_snap_thread ON thread_snapshots(thread_id, turn_seq DESC);
CREATE INDEX idx_snap_created ON thread_snapshots(created_at);

-- 归档（凌晨迁移用）：活跃只看 turn_seq ≤ 50
CREATE TABLE IF NOT EXISTS thread_snapshots_archive(
  id INTEGER PRIMARY KEY,               -- 与上面同 schema，无外键
  thread_id INTEGER NOT NULL,
  turn_seq INTEGER NOT NULL,
  role TEXT NOT NULL,
  summary TEXT NOT NULL,
  confidence REAL,
  created_at REAL NOT NULL
);
```

> **不建触发器**：核心每回合裁决末尾写一条，足够；不要 LLM 路径上有 DB 写延迟。

---

## 2. 写入时机

| 时机 | 写入规则 |
|---|---|
| 核心对 `awaiting_feedback` 项产出裁决 | 落一条 `role='core'`，summary = "我判断…" 的人话（≤300 字） |
| 用户对核心裁决回应（dismissed/actioned/snooze） | 落一条 `role='user'`，summary = 用户原话精简 |
| 凌晨 04:00 batch 迁移 | `turn_seq > 50` 的旧条移到 `thread_snapshots_archive` |

**异步**：核心 LLM 返回后，开 `asyncio.create_task(_write_snapshot(...))`，不阻塞下一回合。

---

## 3. 读取时机（核心需要时主动调）

**触发 1：续报时刻**（`kind='resume'`）
- BFF 在 state_block 后**追加** 一段：
  ```
  [continuity thread_key=wx:8832]
  上次我说(turn 3, conf 0.85): "这看起来是社团招新摊位安排的可选时间冲突，建议先看下周三的"
  你回(turn 4, conf 1.0): "稍后"
  现在: 距上次决议已过 4h12m
  ```
- 模板：见 `thread_continuity.md` §2

**触发 2：核心自己判定"需要回忆"**
- 核心在裁决里写"调用 `thread_recent_context(wx:8832, n=2)`" → MCP 工具返回结构化数据
- MCP 实现：`SELECT role, summary, confidence, created_at FROM thread_snapshots WHERE thread_id=? ORDER BY turn_seq DESC LIMIT ?`

---

## 4. 摘要压缩策略（auto-compact）

| token 数 | 动作 |
|---|---|
| `< 4K` | 直接全量返回 |
| `4K–8K` | 截留最近 2 条 + 早期条目压缩为 1 行"earlier: …" |
| `> 8K` | 只返回最近 1 条 + 建议核心主动 `summarize_thread` 重写 |

**压缩触发**：`thread_history(thread_key)` 返回字节数 > 4000 时由 BFF 自动跑压缩。

```python
def compress_snapshots(snaps: list[dict], budget: int = 4000) -> str:
    if sum(len(s["summary"]) for s in snaps) < budget:
        return "\n".join(s["summary"] for s in snaps)
    keep = snaps[:2]
    older = snaps[2:]
    earlier = " | ".join(s["summary"][:80] for s in older[:5])
    return "\n".join(s["summary"] for s in keep) + f"\n…earlier({len(older)}): {earlier}"
```

---

## 5. 跨通道 / 跨时间继承规则

| 间隔 | 行为 |
|---|---|
| < 10 分钟 | 视为同一回合，**不写新 snapshot**，核心继续上下文 |
| 10 分钟 – 1 小时 | 写新 snapshot，`turn_seq += 1`，summary 开头加 `(续)` |
| 1 小时 – 1 天 | 写新 snapshot，summary 开头加 `(跨时段)`，自动降 `confidence *= 0.9` |
| > 1 天 | 写新 snapshot，summary 开头加 `(新对话)`，`confidence = min(prev, 0.7)` |

> 跨通道（邮件→IM→日历）同 thread_key 时，**共享 turn_seq**——这是 B7 跨通道主题收敛的实现路径。

---

## 6. 注入模板（核心裁决前 BFF 可选附加）

```markdown
[continuity thread_key=<TK>]
last_core(turn=N, conf=C, ago=T): "<一句话总结我上次做了什么判断>"
last_user(turn=N, conf=C, ago=T): "<用户原话精简>"
gap: 距上次决议 <gap_str>
hint: <若 conf<0.5 加 "（记忆较旧，仅供参考）">
```

> **仅当** `kind='resume'` 或核心自己请求时注入。普通新事件不注入——避免注入块膨胀。

---

## 7. 边界

| 场景 | 处理 |
|---|---|
| thread 不存在 | MCP 返回 `{snapshots: [], hint: "无历史"}` |
| `confidence < 0.3` 的旧 snapshot | 自动从返回中过滤掉 |
| 写入失败（DB 锁） | 异步任务 swallow 异常，仅 logger.warning |
| 用户说"忘掉刚才" | `DELETE FROM thread_snapshots WHERE thread_id=?`（核心 LLM 调用 list_mcp） |

---

## 8. 与其他层的关系

| 层 | 区别 |
|---|---|
| Working | 当前回合可见；超过本回合即失 |
| **Contextual（本层）** | 同 thread 跨回合/跨通道可见；按 `turn_seq` 衰减 |
| Long-term | 跨 thread 跨月；按 `evidence_count` + `last_validated_at` 衰减 |

> 一句话：**Contextual = 同一议题的近期记忆；Long-term = 跨议题的稳定偏好**。
