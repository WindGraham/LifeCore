# B3 关系维护 — 行为触发 Prompt

> 来源:docs/13 §4 B2 原 ddl_nudge(转译为关系维护语义)+ docs/11 §4-2 老化扫描
> 层:**L2 状态报告**(同 thread_id resume 续报)
> 灰度:T2 谨慎(7 天后从 T1 升级,2 周最多 2 次)

## 1. 触发条件

```sql
-- 来自 proactive_kinds.trigger_sql(B3.relationship_nudge 已 seed)
SELECT t.id FROM notify_threads t
 WHERE t.last_resolution IN ('snoozed','dismissed')
   AND t.updated_at < strftime('%s','now')-604800       -- >7d
   AND NOT EXISTS (
     SELECT 1 FROM notify_items i WHERE i.thread_id=t.id
       AND i.created_at > strftime('%s','now')-604800   -- 7d 内没新条目
   );
```
含义:**同 thread >7 天没触达** + **之前是 snooze/dismiss**(非 actioned 完成的)。

## 2. 判定 prompt(给 LLM)

```
[B3.relationship_nudge]
你正在生成一条 L2 关系维护 nudge。规则:
1. **同 thread_id 续命** — 不开新 thread,复用现有 notify_threads 行
2. 标题:📌 议题名(第N次跟进),≤20 字
3. 正文 ≤120 字:
   - 关联:你上次 X(引 thread_history 最后决议)
   - 价值:再不回可能 Y(挽回可观的代价;若不存在,不发)
   - 不重复对方原话全文
4. 选项:["现在回", "再稍后", "我处理过了"] — **无"忽略"**(总得有动作)
5. 用户 2 次 👎 同 thread → B4.routine_check 接手,**永不弹第三次**

上下文:<thread_ctx> <related thread history> <user_model.relationship_tone> <last contact timestamp>
```

## 3. 输出模板(同 L2 resume)

```json
{
  "summary": "📌 Alice 问 X(第 3 次跟进) 7 天前你说稍后;再不回她可能觉得已读不回...",
  "options": ["现在回", "再稍后", "我处理过了"],
  "kind": "resume",
  "priority": "normal"
}
```

## 4. 边界

- ✅ 允许:同 thread_id `enqueue_notify(kind='resume')`(30s 复用 promote_notify)
- ✅ 允许:调 `thread_history` MCP 拉前文
- ❌ 禁止:跨 thread 合并
- ❌ 禁止:`kind='normal'` 新建独立通知
- ❌ 禁止:用户说"我处理过了"后再次触发(查 `feedback_events.action='actioned'` 同 thread)

## 5. 红线(action='failure')

- 7 天内同 thread 已触发过(B3 cooldown=259200=3d)→ 跳过(不算失败)
- `user_model.tolerance.spam.relationships` < 0.3 → 跳过
- 关联信息无新意(只是时间过去,无新事件)→ 跳过
- LLM 输出含"再不回就..."但没有具体 Y(空洞威胁)

## 6. 落库后置

```sql
-- snooze_promote_loop 写法派生(kind='resume' + 同 thread)
INSERT INTO notify_items(event_seq,channel_id,state,summary,options,
  requires_feedback,priority,created_at,thread_id,kind)
  VALUES(:seq,:ch,'queued',:summary,:options,1,'normal',:ts,:tid,'resume');
UPDATE notify_threads SET last_item_id=:iid, snoozed_until=NULL, updated_at=:ts WHERE id=:tid;
-- promote_notify()
-- ws_broadcast_snapshot()
INSERT INTO feedback_events(item_id, thread_id, kind, action, summary_hash, created_at, source)
  VALUES(:iid, :tid, 'B3.relationship_nudge', 'resumed', :h, :ts, 'proactive_loop');
UPDATE proactive_loop_state
  SET last_fired_at=:ts, last_daily_date=date('now','localtime'),
      last_daily_count=last_daily_count+1, last_scan_at=:ts
  WHERE kind='B3.relationship_nudge';
```
