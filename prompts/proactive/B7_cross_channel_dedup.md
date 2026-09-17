# B7 跨通道去重 — 行为触发 Prompt

> 来源:docs/13 §4 B7 原 "跨通道主题收敛" + docs/11 §4-4 跨通道整合
> 层:**L2 状态报告**(resume 入队)
> 灰度:T2 谨慎(6h 内 1 次,1 天 ≤2 次)

## 1. 触发条件

```sql
SELECT t1.id, t2.id FROM notify_threads t1, notify_threads t2
 WHERE t1.channel_id <> t2.channel_id
   AND t1.updated_at > strftime('%s','now')-21600     -- 6h
   AND t2.updated_at > strftime('%s','now')-21600
   AND t1.id < t2.id;
-- 然后 LLM 判摘要相似度(关键词 + 嵌入 + thread_key 共同前缀)
```

## 2. 判定 prompt(给 LLM)

```
[B7.cross_channel_dedup]
你正在合并跨通道的同主题通知,规则:
1. **6h 内 ≥2 不同通道** 摘要语义同主题 → 合并单条 thread
2. 摘要 ≤120 字:三方要点各一行 + "合并原因:同主题"
   例:"📌 X 议题(3 通道合并)\n 邮件:Alice 提议周三\n 微信:你已回周四\n 日历:已建 14:00\n合并原因:同主题"
3. **同主题后续自动归入**(改 thread_key 前缀 `merged-<hash>`)
4. 选项:["看合并详情", "拆开", "忽略"]
5. 用户 👎 1 次 → 该 thread 不再合并,但其他 thread 仍可触发
6. **轻量语义判定**:关键词命中 + thread_key 前缀同 → 算同主题;否则不动
   (等长程记忆起来再升级,docs/13 §8-4 决策)

上下文:<t1 摘要> <t2 摘要> <channel_a> <channel_b> <user_model.dedup_sensitivity>
```

## 3. 输出模板

```sql
-- 改 thread_key 合并(派生 hash 标识同主题)
UPDATE notify_threads SET thread_key='merged-'||substr(md5(:h),1,12) WHERE id IN (:t1,:t2);
-- 派生一条 resume 类入主 thread
INSERT INTO notify_items(event_seq,channel_id,state,summary,options,requires_feedback,priority,created_at,thread_id,kind)
  VALUES(:seq,:ch,'queued',:summary,:options,1,'normal',:ts,:main_tid,'resume');
-- promote_notify()
-- ws_broadcast_snapshot()
```

输出 summary 模板:
```json
{
  "summary": "📌 X 议题(2 通道合并)\n 邮件:Alice 提议周三\n 微信:你已回周四\n合并原因:同主题",
  "options": ["看合并详情", "拆开", "忽略"],
  "kind": "resume",
  "priority": "normal"
}
```

## 4. 边界

- ✅ 允许:同 thread_key 合并(派生 `merged-<hash>`)
- ✅ 允许:enqueue_notify(kind='resume') 派生新 item
- ❌ 禁止:跨用户合并
- ❌ 禁止:删除原 thread(只派生,原条目保留)
- ❌ 禁止:LLM 直接合并 >3 通道(超过人类认知,应拆 2 组)

## 5. 红线(action='failure')

- thread_key 已是 `merged-` 前缀 → 跳过(避免重复合并)
- 6h 内同 thread_key 合并 ≥2 次(用户 👎 后频次降级)
- 摘要相似度 < 0.5(LLM 判) → 跳过(不算失败,只 skip)
- LLM 输出无"合并原因"行

## 6. 落库后置

```sql
-- 1. 改 thread_key(幂等)
UPDATE notify_threads SET thread_key = 'merged-' || substr(md5(:h), 1, 12) WHERE id IN (:t1, :t2);
-- 2. 派生 resume item 挂主 thread
INSERT INTO notify_items(event_seq,channel_id,state,summary,options,requires_feedback,priority,created_at,thread_id,kind)
  VALUES(:seq, :ch, 'queued', :summary, :options, 1, 'normal', :ts, :main_tid, 'resume');
-- 3. promote_notify()
-- 4. ws_broadcast_snapshot()
-- 5. feedback_events
INSERT INTO feedback_events(item_id, thread_id, kind, action, summary_hash, created_at, source)
  VALUES(:iid, :main_tid, 'B7.cross_channel_dedup', 'merged', :h, :ts, 'proactive_loop');
-- 6. loop_state
UPDATE proactive_loop_state
  SET last_fired_at=:ts, last_daily_date=date('now','localtime'),
      last_daily_count=last_daily_count+1, last_scan_at=:ts
  WHERE kind='B7.cross_channel_dedup';
```

## 7. T2 启用条件

- 7 天后评估 `feedback_events` 中 `kind='B7.cross_channel_dedup' AND action='merged'` 的 `actioned_rate ≥ 40%`
- 且无"立即 dismissed + 情绪文本"案例
