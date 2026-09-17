# B5 待跟进追踪 — 行为触发 Prompt

> 来源:docs/13 §4 B4 原 "日历冲突静默备选"(L3 干预)
> 层:**L3 决策请求**(单活动锁,等用户拍板,5s 可撤)
> 灰度:**T3 完全**(默认 enabled=1,需 user_revoke 才能永久关)

## 1. 触发条件

```sql
-- 来自 proactive_kinds.trigger_sql(B5.followup_tracker 已 seed)
SELECT s1.key FROM lists s1, lists s2
 WHERE s1.name='schedule' AND s2.name='schedule'
   AND s1.key<>s2.key AND s1.resolved=0 AND s2.resolved=0
   AND json_extract(s1.value_json,'$.start_ts') < json_extract(s2.value_json,'$.end_ts')
   AND json_extract(s1.value_json,'$.end_ts')   > json_extract(s2.value_json,'$.start_ts');
```
含义:**schedule 表中两个未决议事件时间段重叠**。

## 2. 判定 prompt(给 LLM)

```
[B5.followup_tracker]
你正在裁决一个日程冲突备选建议,规则:
1. **L3 决策请求**:必须阻塞等用户拍板,5s 内可撤
2. **必须挂撤回按钮**,3 选项:["用备选", "改时间", "撤回"]
3. summary ≤80 字:具体冲突 + 建议备选 + "等你点头"
   例:"14:00 牙医与 13:30 会议冲突。建议把牙医改 15:30,留 30min 缓冲。确认?"
4. 写入 proactive_suggestions(state=pending),**不直接改 calendar**
5. 同一冲突 7d 内已建议过(B5 cooldown=604800=7d) → 跳过
6. 用户 dismiss 2 次同类型冲突 → 写 user_model:preference.calendar.conflict='auto_accept_first'
   (L3+ 自动化,但仍是 L3 主动告知,不静默改)

上下文:<冲突事件 A> <冲突事件 B> <备选时间窗 candidates> <user_model.calendar.flexibility>
```

## 3. 输出模板(proactive_suggestions + notify)

```json
// 必落 proactive_suggestions(7d 挂起)
{
  "kind": "B5.followup_tracker",
  "thread_id": null,                // 跨 thread 事件,无关联
  "payload_json": "{\"event_a\":...,\"event_b\":...,\"candidates\":[...]}",
  "action": "pending",
  "state": "pending",
  "confidence": 0.6,
  "expires_at": <now+7d>,
  "cooldown_kind": "B5.followup_tracker",
  "rollback_cmd": "calendar.update(event_id=:eid, start_ts=:orig)"   // 采纳后用于反悔
}

// 同步 notify_items(L3,单活动锁)
{
  "summary": "📅 14:00 牙医与 13:30 会议冲突。建议牙医改 15:30,留 30min。确认?",
  "options": ["用备选", "改时间", "撤回"],
  "kind": "proactive",
  "requires_feedback": 1,
  "priority": "high"
}
```

## 4. 边界

- ✅ 允许:`promote_notify()` 立即晋升
- ✅ 允许:用户接受后调 `calendar.update`(风险 R1_local_mutation,需 rollback_cmd 备好)
- ❌ 禁止:直接改 calendar 不等用户(L3 不可绕过)
- ❌ 禁止:无撤回按钮
- ❌ 禁止:`cooldown_sec` 内的重复冲突建议(7d 一次最严)

## 5. 红线(action='failure',触发 4 段式恢复)

- LLM 输出无"撤回"选项
- 备选时间窗为空(无法建议)
- 用户 60s 内对同冲突 2 次 dismiss/snooze → 道歉 + `cooldown_kind='B5.followup_tracker'`,`cooldown_until=now+30d` + running_items
- 矫正通道含"别主动" → `user_kill_switches(scope='kind:B5.followup_tracker', enabled=0, reason='user_revoke', expires_at=NULL)`

## 6. 落库后置

```sql
BEGIN;
  INSERT INTO proactive_suggestions(kind, payload_json, action, state, confidence, expires_at, rollback_cmd)
    VALUES('B5.followup_tracker', :payload, 'pending', 'pending', 0.6, strftime('%s','now')+604800, :rb);
  INSERT INTO notify_items(event_seq,channel_id,state,summary,options,requires_feedback,priority,created_at,thread_id,kind)
    VALUES(:seq,:ch,'queued',:summary,:options,1,'high',:ts,NULL,'proactive');
  -- promote_notify()
  -- ws_broadcast_snapshot()
  INSERT INTO feedback_events(item_id, kind, action, summary_hash, created_at, source)
    VALUES(:iid, 'B5.followup_tracker', 'decision_pending', :h, :ts, 'proactive_loop');
  UPDATE proactive_loop_state
    SET last_fired_at=:ts, last_daily_date=date('now','localtime'),
        last_daily_count=last_daily_count+1, last_scan_at=:ts
    WHERE kind='B5.followup_tracker';
COMMIT;
```

## 7. T3 启用条件

`nightly_learning.py` 校验:`user_kill_switches(scope='kind:B5.followup_tracker', enabled=1)` 存在 + 该 kind 行为 trust_stage='T3'。
