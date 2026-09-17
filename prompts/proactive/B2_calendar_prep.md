# B2 日历预备 — 行为触发 Prompt

> 来源:docs/13 §4 B1 原 calendar_prep(L1,T-24h 日历上下文预加载)
> 层:**L1 安静收集**(silent,不打扰)
> 灰度:T1 起步

## 1. 触发条件

```sql
-- 来自 proactive_kinds.trigger_sql(B2.calendar_prep 已 seed)
SELECT t.id FROM notify_threads t
  JOIN lists s ON s.name='schedule' AND s.resolved=0
    AND json_extract(s.value_json,'$.start_ts') BETWEEN strftime('%s','now')+82800 AND strftime('%s','now')+90000
  WHERE NOT EXISTS (
    SELECT 1 FROM notify_items i WHERE i.thread_id=t.id AND i.resolution='actioned'
  );
```
含义:**距离日历事件 T-23h 到 T-25h 区间** + **同议题无 actioned**(用户没确认过)。

## 2. 判定 prompt(给 LLM)

```
[B2.calendar_prep]
你要为用户预加载 T-24h 日历事件的上下文,规则:
1. **不弹通知** — 写到 user_state + 预取 thread_history 缓存到 LIFECORE 进程内 dict
2. 摘要 ≤150 字:事件名 + 时间地点 + 我已经准备好的资料(过往相关 thread 摘要)
3. 若日历条目 confidence < 0.5 → 不触发(避免为不存在的会准备)
4. 若设备 `last_health != 'ok'` → 降级为只写 user_state,不预取
5. 同一天同一事件 24h 内已预取过 → 跳过

上下文:<schedule 条目> <related thread 摘要列表> <devices.health> <user_model.calendar_importance>
```

## 3. 输出模板

```json
{
  "key": "proactive/B2.calendar_prep/<event_id>/<date>",
  "value": {
    "summary": "明日 14:00 牙医(中山医院 3F)。已准备:上次牙医是 3 月,你提到要问 X。",
    "prepared": [
      {"type": "thread_history", "thread_id": 23, "snippet": "..."},
      {"type": "draft_question", "text": "..."}
    ],
    "event_id": "<from schedule>",
    "start_ts": <float>,
    "preloaded_at": <float>
  }
}
```

## 4. 边界

- ✅ 允许:`thread_history(tid, n=3)` MCP 只读
- ✅ 允许:写 user_state、schedule 派生条目
- ❌ 禁止:enqueue_notify(预加载,不入队)
- ❌ 禁止:跨设备转发(只为本设备)
- ❌ 禁止:为 `start_ts - now > 36h` 的远期事件预取(浪费)

## 5. 红线(action='failure')

- `start_ts < now`(过期事件)→ 跳过
- `last_health` 缺失或 `down` → 跳过
- 同一 event_id 24h 内已预取 → 跳过(不算失败,但不进 feedback_events)
- LLM 输出无"已准备"列表(只复述事件本身没价值)

## 6. 落库后置

```sql
INSERT OR REPLACE INTO lists(name,key,value_json,created_at,updated_at)
  VALUES('user_state', 'proactive/B2.calendar_prep/'||:eid||'/'||date('now'), :v, :ts, :ts);
-- 缓存到内存:_cal_prep_cache[event_id] = v(进程死了重建,丢了无所谓)
INSERT INTO feedback_events(kind, action, summary_hash, created_at, source)
  VALUES('B2.calendar_prep','silent_done',:h,:ts,'proactive_loop');
UPDATE proactive_loop_state
  SET last_fired_at=:ts, last_daily_date=date('now','localtime'),
      last_daily_count=last_daily_count+1, last_scan_at=:ts
  WHERE kind='B2.calendar_prep';
```
