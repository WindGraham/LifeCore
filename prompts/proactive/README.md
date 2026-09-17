# LifeCore 主动行为调度器 — Prompt 套件索引

> 立即挂到核心 agent 用的 **完整 prompt 套件 + 数据库 schema 补丁**。
> 设计依据:docs/13 §4(主动行为 3 层 7 行为 + 信任梯度 + 失败兜底)、docs/11 §5(反馈闭环)、docs/15 §1(设备目录 kill switch 前置)、server.py `/v2/state-block` `/v2/notify/active` `/v2/notify/items/{id}/feedback` `/v2/jobs` `/hk/{ch_id}`。
>
> **不修改 server.py**——所有新增逻辑通过 5 张新表 + 1 个 `proactive_loop` 协程 + 注入块扩 `[proactive]` 段实现。

---

## 0. 一句话目标

沉默观察,替用户守住那个他自己都会忘掉的、体面的长期自己。
**主动 = 挽回可观的代价**,不主动 = 用户没机会反应。无代价的猜测 → 不主动。

---

## 1. 文件清单(本目录)

| 文件 | 用途 | 谁在什么时候读它 |
|---|---|---|
| **4 层行为分级(L1-L4)** | | |
| `L1_signal_collection.md` | 安静收集:只写 state,不通知 | proactive_loop 扫到 L1 kind → 调 LLM 之前拼 system 提示 |
| `L2_status_report.md` | 状态报告:resume 类不阻塞 | 同 thread_id 更新通知,延后 ≤30s |
| `L3_decision_request.md` | 决策请求:active + 单活动锁 | 入队时挂 thread 摘要,等用户拍板 |
| `L4_autonomous_execute.md` | 主动执行:事后报 | 5s 撤回 + 日志 + 回传 |
| **7 个行为触发(B1-B7)** | | |
| `B1_inbox_summary.md` | 收件箱摘要(L1,T1 灰度) | 用户 ≥8h 未开 App 触达 |
| `B2_calendar_prep.md` | 日历预备(L1,T1 灰度) | T-24h 无 actioned |
| `B3_relationship_nudge.md` | 关系维护(L2,T2 谨慎) | 同 thread >7d 无触达 |
| `B4_routine_check.md` | 例行巡查(L2,绝对不弹) | snooze≥4/14d 标 user_state |
| `B5_followup_tracker.md` | 待跟进追踪(L3 决策,T3) | 日程冲突 7d 备选挂起 |
| `B6_proactive_suggest.md` | 主动建议(L2,T2) | 凌晨守护 / 实时守护 |
| `B7_cross_channel_dedup.md` | 跨通道去重(L2,T2) | 6h 同主题合并 |
| **机制文件** | | |
| `trigger_decision.md` | 触发决策:输入信号→判断哪个 kind | proactive_loop 主调度器 |
| `kill_switch.md` | 关闭开关:用户主动关 / 自动降级 | 任何 LLM 调用前/feedback 后/UI 按钮 |
| `feedback_interpret.md` | 反馈解读:👍提权 / 👎降权 / ⏰snooze | feedback handler 后置 |
| **DB schema** | | |
| `schema.sql` | 5 张表 + 1 view + seed:proactive_kinds / behavior_trust / feedback_events / proactive_suggestions / user_kill_switches / proactive_loop_state | 在 server.py `init_db()` 末尾追加 |

---

## 2. 整体调用关系

```
                     ┌─────────────────────┐
                     │ 外部触发器(docs/11) │
                     │ 时间窗 / 设备心跳 /  │
                     │ thread 老化 / user_model│
                     └─────────┬───────────┘
                               │ SQL 扫
                               ▼
              ┌──────────────────────────────────┐
              │   trigger_decision.md 决定 kind   │
              │  → 查 proactive_kinds (T1/T2/T3) │
              │  → 查 user_kill_switches (硬关)  │
              │  → 查 proactive_loop_state (冷却) │
              │  → 查 behavior_trust (灰度评估)  │
              └──────────┬───────────────────────┘
                         │ kind → 读对应 B*.md 拿 触发条件 / 判定 prompt / 输出模板
                         ▼
       ┌────────────────────────────────────────────────┐
       │  按 enqueue_mode 选路:                          │
       │   silent  → 仅写 state (L1)                    │
       │   notify  → enqueue_notify (L2 同 thread 续命) │
       │   decision→ promote_notify 单活动锁 (L3)       │
       │   execute → 5s 撤回 + 日志 (L4)                │
       └──────────┬─────────────────────────────────────┘
                  │ 反馈落入
                  ▼
        ┌──────────────────────┐
        │  feedback_interpret  │  → 提权 / 降权 / snooze / 矫正
        │  → feedback_events   │  → 24h 冷静 / 4 段式失败恢复
        │  → user_model 增量   │  → user_kill_switches (3 次 👎 永久)
        └──────────┬───────────┘
                   │ 夜间批
                   ▼
        ┌──────────────────────────┐
        │ behavior_trust 评估       │  feedback ≥ 10 自动评估
        │  → T1→T2→T3 / 回滚到 L0  │
        └──────────────────────────┘
```

---

## 3. 核心规则(写给调度器作者)

### 3.1 4 层行为分级

| 层 | 写库 | 通知 | 单活动锁 | 撤回 | 何时升级 |
|---|---|---|---|---|---|
| **L1 安静收集** | ✅ state | ❌ | ❌ | ❌ | T1 灰度 7 天 |
| **L2 状态报告** | ✅ thread | ✅ resume 类 | ❌ | ❌(可忽略) | T2 全用户+工作时段 |
| **L3 决策请求** | ✅ thread | ✅ active | ✅ 单活动锁 | 5s 可撤 | T3 满 45 天 + 挂撤回 |
| **L4 主动执行** | ✅ thread+log | ✅ 事后报 | ✅ | 5s 可撤 + undo_token | T3 + user_revoke 可关 |

### 3.2 7 个行为速查

| 行为 ID | 名称 | 层 | 灰度 | 触发 | 关键不打扰 |
|---|---|---|---|---|---|
| B1 | 收件箱摘要 | L1 | T1 | 用户 ≥8h 未开 App + 24h 内 ≥5 条未读 | **不弹通知** |
| B2 | 日历预备 | L1 | T1 | T-24h 日历条目无 actioned | 缓存到 user_state |
| B3 | 关系维护 | L2 | T2 | 同 thread >7d 无触达 | 同 thread_id resume,2 周一次 |
| B4 | 例行巡查 | L2 | T2 | snooze≥4/14d | **绝对不弹**,等用户开 thread |
| B5 | 待跟进追踪 | L3 | T3 | 日程冲突 | 7d 挂起 + 1 按钮撤回 |
| B6 | 主动建议 | L2 | T2 | 凌晨守护 / 实时 | 45min 内静默,1 天 ≤2 次 |
| B7 | 跨通道去重 | L2 | T2 | 6h 同主题合并 | 摘要合并 + 同主题归并 |

### 3.3 T1 灰度规则

- **新行为** 上线 = 默认 `enabled=0,trust_stage='T0'`,夜间批通过 `proactive_kinds` 改 `T1`。
- T1 7 天:仅在 **trusted 用户**(`user_model/preference.proactive.trusted=true`) + **工作时段**(09:00-21:00 localtime)。
- 收集 `feedback_events` 中同 kind ≥ 10 条 → `nightly_learning.py` 评估:
  - 采纳率 ≥40% 且无"立即 dismissed + 情绪文本" → `trust_stage='T2'`,启用至全用户+工作时段。
  - 否则 `status='rolled_back'`,`enabled=0`,reason 写 `behavior_trust.last_eval_reason`。
- 任何 kind 出 1 次 `action='failure'` → `block_until=now+86400` + 强制 confidence -0.2。

### 3.4 4 段式失败恢复(每条 feedback_events.action='failure' 触发)

1. **立即停止**:`UPDATE proactive_loop_state SET block_until=strftime('%s','now')+86400 WHERE kind=?`;同 kind 24h 不再触达。
2. **写 feedback_events**:`action='failure'`,`resolution_text` = 失败原因(LLM 裁决/工具异常/用户语义"不要"/超时等)。
3. **confidence 强制 -0.2**:`UPDATE behavior_trust SET stage_failure_count=stage_failure_count+1, consecutive_failures=consecutive_failures+1 WHERE kind=?`;连续 3 次 → `enabled=0, status='rolled_back'`。
4. **下次 LLM 调用前注入警告**:在 `state_block()` `[proactive]` 段生成 `⚠️ <kind> 上次失败:<reason> 24h 内不再触发`,直到 `block_until` 到期。

### 3.5 反馈环(delta_conf 规则)

| 反馈 | action 字段 | delta_conf | 副作用 |
|---|---|---|---|
| 👍 / actioned | `actioned` | +0.05 | `behavior_trust.stage_actioned_count++` |
| 👎 / dismissed | `dismissed` | -0.10 | 连续 3 次 → 永久 `user_kill_switches(scope='kind:<kind>', enabled=0)` |
| ⏰ snooze | `snooze` | 0 | `kind.cooldown_sec *= 2` 24h,后恢复 |
| ⏰⏰ snooze_long(>1 天) | `snooze_long` | -0.05 | `kind.cooldown_sec *= 4` 72h |
| ✓ 完成 | `actioned`+`completion=true` | +0.10 | `proactive_suggestions.state='accepted'`,线程归档 |
| ⚙️ 改设置 | `settings_changed` | 0 | 直接 `user_kill_switches` / `user_model` 持久化 |
| 💬 矫正文本 | `comment` | 命中 slot 校准 / 否则 24h inferred | `proactive_suggestions.action='feedback_text'` + `cooldown_kind` 同类 24h 静默 |

### 3.6 礼貌预算(每日)

- L1 累计 ≤ 50(几乎不限)
- L2 累计 ≤ 5(B3/B6/B7 各 kind 单独 ≤ 2,2 周 1 次 / 1 天 2 次 / 6h 1 次)
- L3 累计 ≤ 1(B5 7d 1 次)
- L4 累计 ≤ 1(罕见,默认关)

超预算:`UPDATE proactive_loop_state SET block_until=strftime('%s','now')+86400 WHERE kind=?` + 核心日志 `[budget_exhausted]`。

---

## 4. 与 server.py 的对接点(只读不修改)

| server.py 已存在 | 主动行为套件如何用 |
|---|---|
| `init_db()` (line 62) | 末尾追加本目录 `schema.sql` 即可上线 5 张表 + 7 个 kind |
| `state_block()` (line 1115) | 新增 `[proactive]` 段:列当前日预算余量 + active 失败警告 |
| `enqueue_notify()` (line 439) | L2/L3/L4 共用入队口;`kind` 字段新值 `'proactive'`(区别于 `normal`/`resume`) |
| `promote_notify()` (line 402) | L3 决策请求的单活动锁入口,完全复用 |
| `snooze_promote_loop()` (line 734) | **不替代**;新增 `proactive_loop` 协程,30s 扫 `proactive_kinds.enabled=1` 的 SQL 触发器 |
| `notify_feedback()` (line 646) | 反馈 handler 后置调用 `feedback_interpret` 模板;action 白名单扩 `('comment','thumbs_up','thumbs_down','snooze_long','undo','settings_changed','failure')` |
| `ws_broadcast_snapshot()` (line 689) | 主动入队后调用,App 即时收到 L2 续报 |
| `v2/jobs` (line 982) | 晨间简报用现成 Hermes cron,drive B1 触发 |

---

## 5. 落库顺序(P1→P3)

1. **P1(2-3 天)**:跑 `schema.sql` → state_block 加 `[proactive]` 段 → feedback handler 后置 `feedback_interpret` 模板。
2. **P2(3 天)**:加 `proactive_loop` 协程(30s 扫,复用 `snooze_promote_loop` 写法) → 启用 B1/B2(T1 灰度)。
3. **P3(5 天)**:加 `nightly_learning.py` → 评估行为 trust_stage → 启用 B3/B6/B7(T2)→ B5 上 T3。
4. **P4+** (待定):B4 L2 改 L3 反思通知?+ Glance widget + 语音 + KWS。

---

## 6. 红线(绝不做)

- ❌ 不复读原始事件当摘要(要加判断/关联/结论)
- ❌ 不在"用户没机会反应才付出代价"以外的场景主动
- ❌ 不为显得聪明而过度打扰
- ❌ 不编造"我记得你说过"(记忆宁可少不可假)
- ❌ 不每次都解释为什么(长期关系里不需要)
- ❌ 不让核心直接 SSH 到边缘(永远"边缘暴露、本地调用")
- ❌ L3 决策请求不在 `notify.window` 之外触发
- ❌ 不连续 3 次 👎 还在"再给一次机会"(直接永久关)
