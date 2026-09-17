# Thread Continuity · 线程延续（user 模型对历史决策的回忆）

> 用途：续报时刻（`kind='resume'` 或用户主动说"上次那条"）核心被唤醒时，把 thread 历史决策拼进上下文，让核心能用"上次你说稍后"这样的人话。
> 设计依据：docs/10（线程延续）+ docs/11 §二（情境记忆）+ docs/13 §3
> 加载位置：BFF `state_block()` 在续报事件时**附加**本 prompt 渲染结果 + hermes 侧 system append

---

## 1. thread_key 4 级派生回顾（来自 docs/10）

> **服务端唯一实现**（`server.py:resolve_thread_key`），核心不要自己派生——直接信任 `notify_threads.thread_key`。

| 优先级 | 来源 | 示例 |
|---|---|---|
| ① | `payload.thread_key` | "topic:db-assignment" |
| ② | `payload.pointer` 命名空间前缀 | `wx:8832:chat123` → `wx:8832`；`gmail:abc` → `gmail:abc` |
| ③ | `channels.session_discriminator` | "$.thread_id" |
| ④ | 兜底 | `ch:{channel_id}` |

> 跨通道（B7 收敛）= 同 thread_key 命中多个 channel_id，**共享 turn_seq**。

---

## 2. 跨时间继承策略

| 间隔 | 行为 | 引用前缀 |
|---|---|---|
| **< 10 分钟** | 视为同一回合，**不写新 snapshot**，继续 | (无引用) |
| **10 分钟 – 1 小时** | 写新 snapshot，`turn_seq += 1` | `(续)` |
| **1 小时 – 1 天** | 写新 snapshot，confidence *= 0.9 | `(跨时段)` |
| **> 1 天** | 写新 snapshot，confidence = min(prev, 0.7) | `(新对话)` |

> **绝对不要**让核心引用 `> 7 天` 前的 snapshot 作为"我记得你说"——记忆宁可少不可假（红线 7）。

---

## 3. 续报注入模板（BFF 拼到 state_block 后）

**触发**：`/hk/{ch_id}` 收到的事件 `kind='resume'` 或续报 webhook。

**注入位置**：`state_block` 之后追加独立段，**不与 [threads] 段混**：

```markdown
[continuity thread_key=<TK> gap=<GAP>]
last_core(turn=N, conf=C, ago=T): "<一句话总结我上次做了什么判断>"
last_user(turn=N, conf=C, ago=T): "<用户原话精简>"
thread_ctx: last_resolution=<R>, item_count=<N>, snoozed_until=<TS or null>
hint: <若 conf<0.5 加 "（记忆较旧，仅供参考）">
```

**完整示例**：

```markdown
[continuity thread_key=wx:8832 gap=4h12m]
last_core(turn=3, conf=0.85, ago=4h12m): "我判断社团招新摊位安排有冲突，建议先看下周三"
last_user(turn=4, conf=1.0, ago=4h12m): "稍后"
thread_ctx: last_resolution=snooze, item_count=3, snoozed_until=null
hint: (无)

# 续报触发后，BFF 把这段拼到 message 字段的开头：
# [live ...] [user_state] [running_items] [schedule] [threads] [user_model] [services]
# [continuity thread_key=wx:8832 gap=4h12m]
# ...
```

> **总注入块仍 ≤ 2500 字**；续报段最长 300 字。

---

## 4. summary 压缩触发条件

| thread_snapshots 总 token | 动作 |
|---|---|
| `< 4K` | 全量返回最近 N 条 |
| `4K–8K` | 截留最近 2 条 + 早期合并为 1 行 |
| `> 8K` | 仅最近 1 条 + 建议核心主动 `summarize_thread` 重写 |

**实现**（`memory_contextual.md` §4 已有）：

```python
def _format_continuity(snaps: list[dict], gap_sec: int) -> str:
    if not snaps:
        return ""
    keep = snaps[:2]
    older = snaps[2:]
    lines = []
    for s in keep:
        lines.append(
            f"last_{s['role']}(turn={s['turn_seq']}, "
            f"conf={s['confidence']:.2f}, ago={_fmt_ago(s['created_at'])}): "
            f"\"{s['summary'][:120]}\""
        )
    if older:
        lines.append(f"earlier({len(older)}): " + " | ".join(
            s["summary"][:60] for s in older[:3]
        ))
    hint = "（记忆较旧，仅供参考）" if snaps[0]["confidence"] < 0.5 else "（无）"
    return (f"[continuity thread_key=<TK> gap={_fmt_gap(gap_sec)}]\n"
            + "\n".join(lines)
            + f"\nhint: {hint}")
```

---

## 5. 引用历史决策的人话模式

**核心在裁决时**可使用以下人话引用模式（**仅当 snapshot.confidence ≥ 0.5**）：

| 模式 | 示例 | 适用 |
|---|---|---|
| 直接引用 | "上次你说稍后" | snooze 续报 |
| 间隔引用 | "这事 4 小时前你搁置过" | 长 gap 续报 |
| 决议链引用 | "上次 dismissed，这次又来了" | 同 thread 多次出现 |
| 关联引用 | "上次邮件说要确认，今天日历也排上了" | B7 跨通道收敛 |

**禁止模式**：

- ❌ "我之前记得你说…"（`confidence<0.5`）
- ❌ "你一直说…"（无 snapshot 支持的归纳）
- ❌ "上次你答应我…"（actioned 不等于承诺）
- ❌ 引用 `> 7 天` 前的 snapshot（红线 7）

---

## 6. 跨 10 分钟 / 1 小时 / 1 天的具体行为

**核心裁决 Prompt**（拼 system append）：

> 你刚收到一条 `[continuity]` 段：
> - gap < 10min → "这是同回合的延续，按 user_state 继续"
> - gap 10min–1h → "用户隔了一小段时间回来，引用 `(续)` 前缀，可继续推进"
> - gap 1h–1d → "用户隔了一段较长时间，引用 `(跨时段)` 前缀；如核心先前 confidence 较高（≥0.7）可继续主动；否则降级为 L0 等待用户重新发起"
> - gap > 1d → "用户已经离开一段时间，**降级到 L0**，不主动推送，只等用户主动说"

**示例核心裁决输出**：

```json
{
  "tier": "L2",
  "summary": "社团招新摊位 4h 前你搁置过，现在有新档期可挑",
  "options": ["查看档期", "忽略", "稍后"],
  "continuity_ref": "上次你说稍后"
}
```

---

## 7. user_model 对历史决策的引用方式

> 当核心裁决需要引用 `user_model` 中某 slot 作为依据时：

| slot | 引用方式 |
|---|---|
| `preference.notify.window` | "按你 22 点后的静音偏好…" |
| `preference.tone` | （不主动说，让 LLM 风格自然应用） |
| `tolerance.spam.marketing` | "营销类按你的偏好默认降级…" |
| `recurring.<topic>` | "按你的'每周日晚'习惯…" |

> **绝不**："我记得你曾说过…"（除非 `evidence_count ≥ 3` 且 `last_validated_at < 90d`）。

---

## 8. 与其他文件的关系

| 文件 | 协作 |
|---|---|
| `memory_contextual.md` §1-4 | 数据源（`thread_snapshots` 表 + 压缩逻辑） |
| `system.md` §6 | 多消息投递 + `kind='resume'` 行为 |
| `feedback_loop.md` §7 | feedback_history 用于判断"该议题对用户态度" |

---

## 9. 边界与禁止

| 禁止 | 原因 |
|---|---|
| 引用 `confidence < 0.5` 的 snapshot | 红线 7（记忆宁可少不可假） |
| 引用 `> 7 天` 前的 snapshot | 同上 |
| 引用未在本 thread 出现过的 feedback | 跨议题幻觉 |
| 把 snapshot 当作承诺 | actioned ≠ 承诺下次 |
| 在续报时刻直接 push 而不查 continuity | 失忆表现 |

---

## 10. 测试 Checklist

- [ ] 续报事件触发后 state_block 出现 `[continuity ...]` 段
- [ ] gap < 10min 时不写新 snapshot
- [ ] gap > 1d 时核心裁决 tier 自动降级到 L0
- [ ] summary 压缩触发后总长 ≤ 300 字
- [ ] 引用 `(跨时段)` 前缀不出现在 1h 内的续报
- [ ] `confidence<0.5` 的 snapshot 不被引用到人话
