# B4 例行巡查 — 行为触发 Prompt

> 来源:docs/13 §4 B3 原 "习惯性回避反思"(L3 在文档,但本清单降为 L2 静默)
> 层:**L2 但 enqueue_mode='silent'** — **绝对不弹通知**,等用户开 thread 时首行显示
> 灰度:T2 谨慎

## 1. 触发条件

```sql
SELECT t.id, COUNT(*) c FROM notify_threads t
  JOIN notify_items i ON i.thread_id=t.id AND i.resolution='snoozed'
  WHERE i.created_at > strftime('%s','now')-1209600   -- 14d
  GROUP BY t.id HAVING c >= 4;                        -- 14d 内 snooze≥4
```
含义:**14 天内同一 thread 被用户"稍后"了 ≥4 次**——典型回避模式。

## 2. 判定 prompt(给 LLM)

```
[B4.routine_check]
你正在标记一个"用户可能回避"的 thread。**规则(红线,违反则 failure)**:
1. **不弹通知,不写 notify_items,不调 promote_notify** — 只改 user_state
2. 当用户**主动打开**这个 thread 时,在 thread 第一行显示反思提示:
   "你 14 天里 4 次按了稍后。我没有要催你,只是想问:要不要我们拆解一下这个议题?"
3. 改写 thread 标题前缀:⚠️ 习惯性回避 → <原标题>
4. 同 thread 7 天内已标过 → 跳过(不重复)
5. 必须问 1 个**具体**问题(为什么拖/卡在哪/有无替代方案),不能是"你还好吗"

上下文:<thread_ctx> <snooze_count> <last 3 snooze 时间分布> <user_model.avoidance_patterns>
```

## 3. 输出模板

```json
// 改 user_state(不写 notify_items)
{
  "key": "thread_meta/<tid>/avoidance_flag",
  "value": {
    "flag": "avoidance_pattern",
    "snooze_count_14d": 4,
    "first_seen_at": <float>,
    "shown_to_user": false,           // 用户开 thread 才置 true
    "question_prompt": "X 议题你已经 4 次按了稍后,卡在哪了?要不要拆解成更小的下一步?"
  }
}

// 改 notify_threads.title(加前缀)
UPDATE notify_threads SET title = '⚠️ 习惯性回避 → ' || title WHERE id = :tid;
```

## 4. 边界

- ✅ 允许:写 user_state、改 thread.title、读 thread_history
- ❌ **绝对禁止**:enqueue_notify 任何路径(L2 标记,但不入队)
- ❌ **绝对禁止**:在"今天已为你做完"卡片主动提示(用户开 thread 才显示,非主动)
- ❌ 禁止:把"回避"标签扩散到 user_model(避免越界)

## 5. 红线(action='failure')

- LLM 生成的问题不是具体可操作("你还好吗" / "在想什么" → 失败)
- snooze_count < 4(阈值严格)
- thread 已被标 `avoidance_pattern` 且 7 天内已 shown_to_user=true(避免重复打扰)
- 用户已在 running_items 标"我主动回避"(明确豁免)

## 6. 落库后置

```sql
INSERT OR REPLACE INTO lists(name,key,value_json,created_at,updated_at)
  VALUES('user_state', 'thread_meta/'||:tid||'/avoidance_flag', :v, :ts, :ts);
UPDATE notify_threads SET title = '⚠️ 习惯性回避 → ' || title, updated_at=:ts WHERE id=:tid;
INSERT INTO feedback_events(thread_id, kind, action, summary_hash, created_at, source)
  VALUES(:tid, 'B4.routine_check', 'silent_mark', :h, :ts, 'proactive_loop');
UPDATE proactive_loop_state
  SET last_fired_at=:ts, last_daily_date=date('now','localtime'),
      last_daily_count=last_daily_count+1, last_scan_at=:ts
  WHERE kind='B4.routine_check';
```

## 7. 与 docs/13 §4 B3 的偏离说明

docs/13 原定义为 L3 干预("用户打开 thread 时首行显示")。本套件把"入队"动作去掉,只留"标记"动作——更符合 docs/13 §1 信条("沉默观察"),且不会因 thread 标题前缀变化影响 L2 同 thread 续命逻辑。
