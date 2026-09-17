# LifeCore 核心 Agent Prompt 套件

> 9 个文件，覆盖核心 agent 永驻 system prompt、三层记忆注入、主动行为触发、反馈闭环、子 agent 分发、线程延续。
> 设计依据：`docs/11`、`docs/13`、`docs/15`、`contracts/agent.md`、`services/lifecore-server/server.py`。
> 目标：可直接拷贝到 hermes 的 `config.yaml` / `system_prompt` / `hooks.pre_llm_call` 使用。

---

## 0. 一句话哲学

> **沉默观察，替用户守住那个他自己都会忘掉的、体面的长期自己。**
>
> 默认不主动伸手；触达率 vs 打扰率，恒偏向打扰率 ↓。
> 主动的金标准：**"若不主动，用户是否会因为没机会反应而付出可观的代价"**——能挽回的才主动，纯猜测不主动。

---

## 1. 文件清单与加载时机

| 文件 | 加载位置 | 时机 | 优先级 |
|---|---|---|---|
| [`system.md`](./system.md) | hermes `config.yaml` 的 agent `system_prompt` | 服务启动时一次性加载，**永驻** | P0 |
| [`memory_working.md`](./memory_working.md) | `hooks.pre_llm_call` shell hook（BFF 改写） | **每回合** LLM 调用前注入 | P0 |
| [`memory_contextual.md`](./memory_contextual.md) | hermes `mcp_servers.list_mcp` 的 `thread_recent_context` 工具 | 续报时按需取；写由核心每回合裁决末尾异步触发 | P1 |
| [`memory_longterm.md`](./memory_longterm.md) | hermes `mcp_servers.list_mcp` 的 `read_user_model` 工具；注入块 `[user_model]` 段 | 注入块每回合自动给摘要（≤300 字）；写由核心/反馈处理器触发 | P1 |
| [`proactive_trigger.md`](./proactive_trigger.md) | `proactive_loop` 协程 30s 扫描周期内调用核心裁决 | **仅**触发器命中时入队；T1/T2/T3 灰度见 §4 | P2 |
| [`feedback_loop.md`](./feedback_loop.md) | `notify_feedback` handler + 核心裁决时读取 | 用户按 feedback / 核心收到 comment 时 | P0 |
| [`subagent_dispatch.md`](./subagent_dispatch.md) | 核心 LLM 工具列表中的 `dispatch_subagent` 描述 | 核心判定"需要隔离/并行/独立上下文"时主动调用 | P1 |
| [`thread_continuity.md`](./thread_continuity.md) | 续报时刻裁入 LLM 上下文 + `thread_history` MCP 拼"上次你说稍后" | 仅 `[续报] kind='resume'` / `thread_continuity.allowed=true` 时 | P0 |

> **加载优先级**：核心启动时拼出 `system = system.md + 短 append`；每回合 BFF 注入 `memory_working.md` 渲染块；其他按需。

---

## 2. 怎么挂到 hermes

### 2.1 系统 Prompt（`system.md`）

写到 `~/.hermes/config.yaml` 的 agent 段：

```yaml
agents:
  default:
    system_prompt_file: /opt/lifecore/LifeCore/prompts/core-agent/system.md
    append_system_prompt_file: /opt/lifecore/LifeCore/prompts/core-agent/memory_principles_tail.md
    # append 段短写"三层记忆如何用"，不重复 system.md 全文
```

hermes 重启后生效（`PUT /v2/admin/config` 也行）。

### 2.2 每回合注入（`memory_working.md`）

**不要**在 hermes 侧改 pre_llm_call shell hook——BFF 已经在做。`/v2/sessions/{sid}/chat` 和 `/chat/stream` 已经把 `state_block()` 前置到 message 字段。需做的是把 `state_block()` 的渲染规则扩为按 `memory_working.md` 输出。

做法：在 `lifecore-server/server.py` 的 `state_block()` 函数中追加 `[threads]`、`[user_model]`、`[devices]` 三段；模板直接照抄 `memory_working.md` §4。

### 2.3 反馈闭环（`feedback_loop.md`）

`/v2/notify/items/{id}/feedback` handler 必须**同步**做完：
1. 写 `notify_items.resolution`（已有）
2. 写 `feedback_events`（新表，见 docs/11 §5）
3. 算 Jaccard + 关键词 → top-3 user_model slot → 更新 confidence
4. 反推 `preference.proactive.*` 计数 → 触发 `proactive_suggestions.action='throttle'`

handler 不在 LLM 路径上——这段是 **Python 必做动作**，prompt 只描述意图。

### 2.4 主动触发器（`proactive_trigger.md`）

新增 `proactive_loop` 协程（与 `snooze_promote_loop` 并列），30s 扫一次四类触发器。**纯 SQL 决策入队什么**；需要"写一句人话"时通过 `proactive_seek_judgment(channel, payload)` 异步调核心拿裁决。

核心侧：核心被 `proactive_seek_judgment` 唤醒时，使用 `proactive_trigger.md` §2 的 B1-B7 prompt 模板（按触发器 ID 选）拼出 system append。

### 2.5 子 agent 分发（`subagent_dispatch.md`）

把 `dispatch_subagent` 加到 hermes 的工具列表（`mcp_servers.subagent_dispatcher` 或本地脚本）。核心 LLM 在工具描述里看到这段 prompt 后，可自行决定何时 fork。

### 2.6 线程延续（`thread_continuity.md`）

续报时刻由 `notify_threads` 触发：核心被新事件唤醒，事件 `kind='resume'` → 注入 prompt 时**附加** `thread_continuity.md` 全文（一次性，TTL = 单回合）。

---

## 3. 三层记忆数据落地

| 层 | 表 | MCP 工具 | 注入位置 |
|---|---|---|---|
| Working | `notify_items / notify_threads / lists` (运行时) | `list_mcp` (已有) | `state_block()` 的 `[live]` + `[user_state]` 等段（`memory_working.md` §4） |
| Contextual | `thread_snapshots` (新表) | `thread_recent_context(thread_key, n=2)`（新） | 续报时按需取；写由核心每回合裁决末尾异步触发（`memory_contextual.md` §3） |
| Long-term | `lists` 中 `name='user_model'` 命名空间 | `read_user_model(slots=[...])`（新） | `state_block()` 的 `[user_model]` 段（`memory_longterm.md` §4）；写由反馈/核心推断触发（`memory_longterm.md` §5） |

新增表 schema 见各文件 §SQL。

---

## 4. 主动行为灰度（T1/T2/T3）

| 阶段 | 启用范围 | 退出条件 | 落地 |
|---|---|---|---|
| **T1 灰度**（首 14 天） | 仅 L1（B1 日历预加载、B6 未读聚合） | 14 天核心调用率 ≥30% → T2；<10% 调查 | `proactive_kinds.tier='L1'` 行存在即开 |
| **T2 谨慎**（14-45 天） | L2 的 B2 DDL nudge、B5 凌晨守护、B7 跨通道收敛 | 采纳率 ≥40% 且无"立即 dismissed + 情绪文本" → T3 | `proactive_kinds.tier='L2' AND enabled=1` |
| **T3 完全**（45 天起） | L3 的 B3 习惯性回避反思、B4 日历冲突备选 | 用户说"别主动" → 永久 `disabled_until=user_revoke` | `proactive_kinds.tier='L3' AND granted_at IS NOT NULL` |

**失败回滚**：连续 3 次 👍 反向（用户 dismissed 后的自由文本为负向）→ 降级回 L0/L1，写 `proactive_suggestions.action='rollback'`。

每日预算：L2 ≤ 5 次 / L3 ≤ 1 次；用尽 → 核心日志 `[budget_exhausted]` 并停。

---

## 5. 设备目录前置（来自 docs/15）

任何 L2/L3 主动伸手**前必查** `devices.last_health ∈ {ok, degraded}` 且 `last_seen_at < 300s`。
- 不可用 → 降级为只写 `running_items`，**不推送**。
- `degraded` → 仅推送对延迟不敏感的行为（如 B6 聚合日报），禁所有 spawn/expose 类。
- 红线设备（`tags` 含 `emergency_disabled_until > now`）→ **永久 skip**。

详见 `proactive_trigger.md` §5。

---

## 6. 红线（system.md / proactive_trigger.md 共同背书）

> 核心 agent **永不**：
> 1. 装/卸 APK（`pm install/uninstall`）；
> 2. 改系统分区、刷机、bootloader 解锁相关；
> 3. 撤 OAuth token、删账号、删邮件；
> 4. 任何 `R3_destructive` 类操作；
> 5. 持 SSH 凭据主动连边缘设备（永远"边缘暴露、本地调用"）；
> 6. 在用户明确说"别主动"后复活被 `disabled_until` 标记的行为；
> 7. 编造"我记得你说过"——记忆宁可少不可假。

---

## 7. 测试方法

### 7.1 单元测试（prompt 渲染）

```bash
cd /opt/lifecore
python -c "
from pathlib import Path
for f in Path('prompts/core-agent').glob('*.md'):
    txt = f.read_text()
    assert 300 <= len(txt) <= 6000, f'{f.name}: {len(txt)} 字超界'
print('OK')
"
```

### 7.2 注入块回归（state_block）

`curl http://127.0.0.1:8790/v2/state-block | jq .block` → 应看到 `[live ...]` + `[threads]` + `[user_model]` 三段（`memory_working.md` §4）。

### 7.3 主动行为灰度沙盒

在 `proactive_kinds` 手工 `INSERT` 几条 B1/B6 触发信号 → 30s 内观察 `notify_threads` 是否出现新行（`kind='proactive'`）。

### 7.4 反馈闭环

App 端连点三次 👎 → 看 `feedback_events` 三行 → 看 `user_model/preference.tolerance.spam.*` 的 `evidence_count` += 3、`confidence` 累计减。

### 7.5 子 agent

调 `dispatch_subagent(task="...", context={...})` → 子 agent 在独立 session 跑 → 回执 `{status, summary, artifacts}`。失败回退由核心接手。

---

## 8. 版本与变更

- v0.1（2026-09-17）：初版，9 文件全套。
- 变更原则：与 `docs/06 定型决议.md` 同步；任何新增 trigger / slot 必须 P1/P2/P3 路径评估。

> **维护者**：核心 agent 的 prompt 是产品哲学的活体。任何修改先问"这是否还是沉默观察？"——答"是"才动。
