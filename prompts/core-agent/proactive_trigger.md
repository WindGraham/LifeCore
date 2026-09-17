# Proactive Trigger · 主动行为触发器（B1-B7）判定 Prompt

> 用途：`proactive_loop` 协程 30s 扫一次命中触发器 → SQL 决策入队 → 必要时 `proactive_seek_judgment` 异步唤醒核心拿"一句人话"
> 设计依据：docs/11 §四（7 条触发器）+ docs/13 §4（3 层 + 7 行为 + 信任梯度 + 失败兜底）+ docs/15 §5（红线）
> 加载位置：`lifecore-server/proactive_loop.py`（与 `snooze_promote_loop` 并列的后台协程）

---

## 0. 三层 × 七行为总表（裁决前必读）

| ID | 名称 | 层 | 触发信号 | 行动 | 频率上限 |
|---|---|---|---|---|---|
| **B1** | T-24h 日历上下文预加载 | L1 | 日历线程 T-24h 且无 actioned | 本地缓存 `get_context(pointer, radius=10)` | 每次事件 1 次 |
| **B2** | 作业 DDL T-4h nudge | L2 | 学业监控 + thread 7d 内无 actioned + [DDL-4h, DDL-30min] | 同 thread_id 更新通知，options 可加 MCP 改 DDL | 14d 2 次，间隔 ≥72h |
| **B3** | 习惯性回避反思 | L3 | 同 thread 14d snooze≥4 且间隔缩短 | 用户**打开 thread**时首行显示"已 4 次稍后，要拆解吗？" | 永不弹通知 |
| **B4** | 日历冲突静默备选 | L3 | calendar 入队 X 与 confirmed Y 重叠 | 派生 `pending_suggestion` 挂 7d | 每次冲突 1 次 |
| **B5** | 凌晨深度工作守护 | L2 | 23-04 点 + 30min 无输入 + app_foreground | "已专注 90 分钟，要起身吗？" | focus=true 后 45min 静默 |
| **B6** | 未读聚合日报 | L1→弱 L2 | 24h 1 次 + 用户未开 App >8h | 首屏 5 条候选，**不弹通知** | 24h 1 次 |
| **B7** | 跨通道主题收敛 | L2 | 6h 内 ≥2 不同通道摘要语义同一主题 | 单条 thread 聚合三方要点 | 12h 1 次/thread |

---

## 1. T1/T2/T3 灰度（强制上线纪律）

```sql
-- proactive_kinds 表（新增）
CREATE TABLE proactive_kinds(
  kind TEXT PRIMARY KEY,           -- 'B1'..'B7'
  tier TEXT NOT NULL,              -- 'L1' | 'L2' | 'L3'
  enabled INTEGER NOT NULL DEFAULT 0,
  min_confidence REAL DEFAULT 0.7, -- 触发要求的 user_model confidence
  cooldown_sec INTEGER DEFAULT 0,  -- 同 kind 两次最小间隔
  max_per_day INTEGER DEFAULT 999, -- 当日上限
  granted_at REAL,                 -- 一次性授权时间（L3 必填）
  last_fired_at REAL,
  failed_streak INTEGER DEFAULT 0, -- 连续失败计数
  disabled_until REAL              -- 用户说"别主动" → 设到 9999999999
);
```

| 阶段 | 时间窗 | 启用 | 启用条件 |
|---|---|---|---|
| **T1 灰度** | 0–14 天 | L1：B1, B6 | 上线即开（无授权） |
| **T2 谨慎** | 14–45 天 | + L2：B2, B5, B7 | `enabled=1` 由 T1 退出后人工/规则切 |
| **T3 完全** | 45 天起 | + L3：B3, B4 | `granted_at IS NOT NULL`（用户显式授权） |

**触发器调度顺序**（每 30s 扫描）：

```python
for kind in ['B6', 'B1', 'B5', 'B7', 'B2', 'B4', 'B3']:  # L1 先，L3 最后
    if _enabled(kind) and _cooldown_ok(kind) and _budget_ok(kind):
        payload = _evaluate(kind)        # SQL 决策
        if payload:
            _enqueue_proactive(kind, payload)
```

---

## 2. 七个触发器判定 Prompt（核心被唤醒时拼 system append）

> 当 `proactive_seek_judgment(channel, payload, kind)` 异步唤醒核心时，按 `kind` 选下列 prompt 之一拼入 system append。

---

### B1 · T-24h 日历上下文预加载

**输入 JSON**：
```json
{"kind": "B1", "thread_key": "cal:standup:18",
 "event_start": "2026-09-18T10:00:00+08:00",
 "pointer": "gcal:event_42",
 "channel_id": "ch_xyz"}
```

**裁决 Prompt**：
> 这是 L1 行为（T-24h 日历预加载）。**核心不需要裁决是否做**——已经在做。只需要决定**缓存多少**：`radius ∈ [3, 15]`，默认 10。
> - 高频反复议程的会议 → radius=15（保留更多上下文）
> - 一次性陌生会面 → radius=5（够用即可）
> - 你认为该会议有 7+ 个相关历史 thread → radius=15
> 输出 JSON：`{"radius": N, "note": "一句话人话，可选"}`

**输出边界**：必须是 `radius` int ∈ [3, 15]；不写就不写 note。

---

### B2 · 作业 DDL T-4h nudge

**输入 JSON**：
```json
{"kind": "B2", "thread_key": "course:cs101",
 "ddl": "2026-09-17T23:59:00+08:00",
 "task_summary": "数据库大作业",
 "thread_history": [...], "last_resolution": "snooze"}
```

**裁决 Prompt**：
> 这是 L2 行为（DDL 即将到期）。判断标准：**用户是否会因为没机会反应而付出可观的代价**？
> - DDL < 2h 且 thread 7d 内无 actioned → 入队 nudge
> - DDL < 2h 且用户近期 3 次 dismissed → **降级为只写 running_items**
> - DDL 已过 → 不入队，落 `running_items.missed.*`
> 输出 JSON：`{"enqueue": bool, "summary": "一句话", "options": ["加入日历", "忽略", "稍后"]}`
> `summary` 必须是带判断的人话（不是原文复述）："数据库大作业今晚截止，您这周提过两次要开始写，要现在拉一下上下文吗？"

---

### B3 · 习惯性回避反思

**输入 JSON**：
```json
{"kind": "B3", "thread_key": "task:health-checkup",
 "snooze_count_14d": 4, "intervals_days": [7, 5, 3, 2]}
```

**裁决 Prompt**：
> 这是 L3 行为（习惯性回避反思）。**绝对不弹通知**——只在用户下次打开该 thread 时，在 thread 首行加灰字"已 4 次稍后（间隔 7→5→3→2 天），要拆解吗？"。
> - 不要 push，不要 TTS
> - 写入 `notify_threads.title` 的"反思锚点"位（前端读取时取这个字段）
> 输出 JSON：`{"anchor_text": "已 4 次稍后（间隔 7→5→3→2 天），要拆解吗？"}`
> **若 `intervals_days` 未缩短** → 输出 `{"anchor_text": null}`。

---

### B4 · 日历冲突静默备选

**输入 JSON**：
```json
{"kind": "B4", "thread_key": "cal:conflict:99",
 "event_x": {"title": "社团迎新", "start": "...", "duration_min": 120},
 "event_y": {"title": "导师面谈", "start": "...", "duration_min": 60, "confirmed": true}}
```

**裁决 Prompt**：
> 这是 L3 行为（自动备选建议）。生成 1-2 个**不弹通知**的备选方案，挂到 `pending_suggestions` 表 7 天。
> - 不要 prompt 列表
> - 备选基于 event_y 的 confirmed 状态为锚
> 输出 JSON：`{"suggestions": [{"shift_x_min": 30, "rationale": "提前 30 分钟可错开导师面谈 15 分钟"}], "expires_at": now+7d}`
> **绝不**直接调 MCP 改 X——只挂建议，等用户采纳。

---

### B5 · 凌晨深度工作守护

**输入 JSON**：
```json
{"kind": "B5", "focus_started_at": 1726589400,
 "current_focus_min": 92, "app_foreground": true,
 "hour_local": 1, "notify_window_ok": false}
```

**裁决 Prompt**：
> 这是 L2 行为（凌晨守护）。仅在以下全部满足时入队：
> 1. 本地时间 23:00–04:00
> 2. app_foreground=true 且持续 ≥ 30min
> 3. `notify_window_ok=false`（不在静音窗）
> 4. 上次 B5 入队距今 ≥ 45min
> 输出 JSON：`{"enqueue": bool, "summary": "已专注 92 分钟，要起身走两步吗？", "options": ["起身", "再 30min", "暂停守护"]}`

---

### B6 · 未读聚合日报

**输入 JSON**：
```json
{"kind": "B6", "since_last_open_min": 540,
 "candidate_summaries": [{"thread_key":"...", "summary":"..."}, ...]}
```

**裁决 Prompt**：
> 这是 L1→弱 L2 行为（每日聚合）。**不弹通知**——只更新 App 首屏的"今日已为你做完"卡片。
> - 选 top 5（按 importance + recency）
> - importance 估算：用户近期 actioned ≥2 次 → 高；纯 dismiss → 低
> 输出 JSON：`{"top5": [{"thread_key":"...", "one_liner":"..."}]}`
> 若候选 ≤1 → 输出 `{"top5": []}`（不浪费首屏）。

---

### B7 · 跨通道主题收敛

**输入 JSON**：
```json
{"kind": "B7", "thread_key": "topic:db-assignment",
 "channels": ["ch_wx", "ch_gmail", "ch_calendar"],
 "summaries": [
   {"ch": "ch_wx", "text": "张三说周五交"},
   {"ch": "ch_gmail", "text": "助教邮件确认周五 23:59"},
   {"ch": "ch_calendar", "text": "周五 22:00 准备时段"}
 ]}
```

**裁决 Prompt**：
> 这是 L2 行为（跨通道收敛）。把 ≥2 不同通道的同主题合成**单条** thread 入队（`kind='cross_channel'`）。
> - 必须给出**判断**，不是复述三方原文
> - 末尾加"已合并 X 个来源"
> 输出 JSON：`{"summary": "数据库作业周五 23:59 截止（微信+邮件+日历三方一致）。22:00 已有准备时段。", "merged_sources": 3}`

---

## 3. 设备目录前置（来自 docs/15）—— **所有 L2/L3 必查**

```sql
-- 任何主动行为动手前：
SELECT id, last_health, last_seen_at, tags
FROM devices
WHERE owner_user_id = current_user();
```

| 设备状态 | L1 | L2 | L3 |
|---|---|---|---|
| `ok` & `last_seen_at < 300s` | ✅ 推送 | ✅ 推送 | ✅ 推送 |
| `degraded` | ✅ 不推送 | ⚠️ 仅 B6 | 🚫 全禁 |
| `down` 或 `last_seen > 600s` | ✅ 不推送 | 🚫 跳过 | 🚫 跳过 |
| `tags` 含 `emergency_disabled_until > now` | 🚫 永久 skip | 🚫 永久 skip | 🚫 永久 skip |

---

## 4. 失败回滚与礼貌预算

**回滚触发**：
- 同 kind 连续 3 次 👍 反向（`feedback_events.action='rejected_comment'`）→ `proactive_kinds.enabled=0, failed_streak=3`
- 同 kind 24h 内 ≥ 2 次立即 dismissed → 降级到 L0/L1
- 写 `proactive_suggestions(action='rollback', kind=..., reason=...)`

**每日预算**：
- L2 ≤ 5 次 / 日（`proactive_budget` 表，凌晨重置）
- L3 ≤ 1 次 / 日
- 用尽 → 核心日志 `[budget_exhausted] kind=B2`，停

---

## 5. 与核心 system prompt 的协作

- `system.md` 永驻 → 决定"沉默 vs 主动"
- `proactive_trigger.md`（本文件）→ 决定"主动做哪个 + 怎么做"
- 核心被 `proactive_seek_judgment` 唤醒时，**只把对应 B-id 段**追加到 system append（不重复全表）

---

## 6. 边界与禁止

| 禁止 | 原因 |
|---|---|
| L3 直接调 MCP 改用户设备 | 必须经"5s 内可撤"通道；详见 `system.md` §4 红线 |
| 跨用户推送 | 红线 9 |
| 自动 OTA | 红线 10 |
| 把 `remote_services` 写 Hermes | 保持 Hermes 通道透明（docs/15 §3） |
| 核心自己跑 SSH | 红线 5（永远"边缘暴露、本地调用"） |
