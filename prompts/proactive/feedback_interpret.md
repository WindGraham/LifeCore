# 反馈解读 — Feedback Interpret

> 来源:docs/11 §5 反馈闭环 + docs/13 §3.5 失败兜底 + docs/13 §4 信任梯度
> 用途:**feedback handler 后置**,任何 `/v2/notify/items/{id}/feedback` 写库后立即调用
> 入口:server.py `notify_feedback()` 末尾,commit 前调用本模板逻辑

## 1. action → delta_conf → 副作用 完整映射表

| 用户动作 | action 字段 | delta_conf | 副作用 |
|---|---|---|---|
| ✅ actioned(采纳) | `actioned` | **+0.05** | `behavior_trust.stage_actioned_count++`;`user_model.<slot>.evidence_count++` |
| ❌ dismissed(不采纳) | `dismissed` | **-0.10** | 7d 同 kind dismissed ≥3 → 触发 `kill_switch` 路径 B;写 `proactive_suggestions.cooldown_kind` |
| ⏰ snooze(短) | `snooze` | **0** | `proactive_kinds.cooldown_sec *= 2`(24h 内);`snoozed_until` 已存在 |
| ⏰⏰ snooze >1d | `snooze_long` | **-0.05** | `cooldown_sec *= 4`(72h) |
| ✓ 完成(completion 标记) | `actioned` + 文本含"完成/搞定" | **+0.10** | `proactive_suggestions.state='accepted'`,thread 归档 |
| 💬 矫正文本 | `comment` | 命中 slot 校准 / 否则 24h inferred | `proactive_suggestions.action='feedback_text'` + `cooldown_kind` 24h 同类静默 |
| ⚙️ 改设置 | `settings_changed` | 0 | 直接持久化到 `user_kill_switches` / `user_model` |
| ↩️ 撤回(5s 内) | `undo` | 0 | 调 `rollback_cmd` 反向 |
| ❌ 失败(系统层) | `failure` | **-0.20**(强制) | 4 段式恢复:block_until=now+24h + consecutive_failures++ + state_block 警告 |

## 2. handler 后置 SQL(伪代码)

```python
async def on_feedback(item_id, body, dev):
    # server.py notify_feedback 已做基本写库
    action = body['action']
    item = fetch_item(item_id)
    thread = fetch_thread(item.thread_id) if item.thread_id else None
    kind = thread.kind if thread else None  # 若是行为级,从 proactive_kinds 反查
    slot = find_matching_user_model_slot(item.summary)  # Jaccard + 关键词
    delta = ACTION_DELTA[action]
    feedback_event = {
        'item_id': item_id, 'thread_id': item.thread_id,
        'kind': kind, 'slot': slot, 'action': action, 'delta_conf': delta,
        'summary_hash': hash(item.summary), 'resolution_text': body.get('comment'),
        'created_at': now(), 'source': 'app'
    }
    conn.execute("INSERT INTO feedback_events(...) VALUES(...)", feedback_event)

    # 副作用 1:更新 user_model
    if slot and delta != 0:
        update_user_model_slot(conn, slot, delta, source=infer_source(action))

    # 副作用 2:行为级反馈 → 累计 behavior_trust 计数
    if kind:
        col = {'actioned':'stage_actioned_count','dismissed':'stage_dismissed_count',
               'snooze':'stage_snooze_count'}.get(action)
        if col:
            conn.execute(f"UPDATE behavior_trust SET {col}={col}+1, stage_feedback_count=stage_feedback_count+1, updated_at=? WHERE kind=?", (now(), kind))

    # 副作用 3:👎 累计 3 次 → kill_switch 路径 B
    if action == 'dismissed' and kind:
        cnt = conn.execute("SELECT COUNT(*) c FROM feedback_events WHERE kind=? AND action='dismissed' AND created_at>?", (kind, now()-604800)).fetchone()['c']
        if cnt >= 3:
            await auto_kill_kind(conn, kind, reason='auto_3_dismissed')

    # 副作用 4:comment 文本含"别主动" → 永久 user_revoke
    if action == 'comment' and '别主动' in (body.get('comment') or '') and kind:
        await permanent_user_revoke(conn, kind)

    # 副作用 5:snooze → 临时冷却
    if action == 'snooze' and kind:
        multiplier = 4 if action == 'snooze_long' else 2
        conn.execute("UPDATE proactive_kinds SET cooldown_sec = cooldown_sec * ? WHERE kind=?", (multiplier, kind))

    # 副作用 6:failure → 4 段式恢复
    if action == 'failure':
        await four_phase_recovery(conn, kind, reason=body.get('reason'))
```

## 3. 24h 冷静期(comment 触发)

```python
async def apply_24h_cooldown(conn, kind):
    """用户 comment 含负面 → 24h 同 kind 静默"""
    conn.execute("""INSERT OR REPLACE INTO proactive_suggestions
                    (kind, action, state, cooldown_kind, cooldown_until, created_at, payload_json)
                    VALUES (?, 'feedback_text', 'pending', ?, strftime('%s','now')+86400, strftime('%s','now'), '{}')""",
                 (kind, kind))
    # 调度器:扫 cooldown_until > now → 跳过该 kind
```

## 4. 4 段式失败恢复(server.py/loop 后置)

```python
async def four_phase_recovery(conn, kind, reason):
    # 1. 立即停止
    conn.execute("UPDATE proactive_loop_state SET block_until=strftime('%s','now')+86400, last_failure_at=strftime('%s','now'), consecutive_failures=consecutive_failures+1, updated_at=strftime('%s','now') WHERE kind=?", (now(), now(), kind))
    # 2. feedback_events 已在 caller 写
    # 3. confidence -0.2 (强制)
    conn.execute("UPDATE behavior_trust SET stage_failure_count=stage_failure_count+1, updated_at=strftime('%s','now') WHERE kind=?", (now(), kind))
    # 4. state_block 警告 — 写到 proactive_loop_state 让 state_block() 读出
    conn.execute("UPDATE proactive_loop_state SET last_failure_reason=? WHERE kind=?", (reason, kind))
    # 连续 3 次 → 永久回滚
    cnt = conn.execute("SELECT consecutive_failures FROM proactive_loop_state WHERE kind=?", (kind,)).fetchone()['consecutive_failures']
    if cnt >= 3:
        conn.execute("UPDATE proactive_kinds SET enabled=0, updated_at=strftime('%s','now') WHERE kind=?", (now(), kind))
        conn.execute("UPDATE behavior_trust SET status='rolled_back', last_eval_reason='consecutive_failures>=3', updated_at=strftime('%s','now') WHERE kind=?", (now(), kind))
```

## 5. state_block 注入失败警告

`state_block()` 在 `[proactive]` 段生成:
```
[proactive]
B1.inbox_summary: 今日 0/1 (上次失败:<reason> 24h 内不再触发)
B3.relationship_nudge: block_until 2025-09-18 14:32
⚠️ B5.followup_tracker: 上次 failure at 14:20, reason="rollback_cmd 空"
```

## 6. 礼貌预算(actioned 后)

```python
# 任意 actioned 后,不动 daily_budget(被采纳不算"打扰")
# dismissed 后 -1 daily_budget 余量(累加,直到 0 → block_until=24h)
# snooze 累计 5 次/天 → block_until 24h + log [budget_exhausted]
```

## 7. A/B 模板对齐(summary_hash)

```python
# 同一 thread_key + summary_hash + 不同措辞 → 算 A/B 实验组
# nightly_learning 比 7d actioned_rate:胜者写 user_model/preference.summary.template
```

## 8. 红线

- ❌ action='failure' 不写 feedback_events(失败也要留痕,4 段式第一步)
- ❌ 任何 👎 不更新 stage_dismissed_count(累计必须准)
- ❌ 24h 冷静期与 kill switch 重复触发(同一 comment 只算 1 次)
- ❌ A/B 模板不同 thread_key 比(必须同 thread)
