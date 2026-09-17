# L2 状态报告 — 行为层 Prompt

> 来源:docs/13 §4 L2 提示 + docs/10 §2 同 thread_id 续报
> 适用 kind:`L2.resume_report` `B3.relationship_nudge` `B6.proactive_suggest` `B7.cross_channel_dedup`(`B4` 走 silent 见 L1)

## 1. 触发条件

同 L1 SQL,但 `WHERE k.layer='L2'`。**额外前置**:
- `preference.notify.window` 当前时间必须 ∈ 区间(否则只入 user_state 不入队)
- 同 thread_id 24h 内已有 resume 类 → 跳过(避免续报刷屏)
- 设备心跳 `bridge_heartbeat.last_seen > 120s`?——否则降级为 L1 silent(L2 推送需要设备能收)

## 2. 判定 prompt(给核心 LLM)

```
[proactive/L2]
你正在裁决一个 L2 状态报告。规则:
1. 这是关键时间点的安静 nudge,**同 thread_id 续命**(docs/10 §2),不是新通知
2. 标题 ≤20 字:📌 议题名(第N次跟进);正文两行 = 结论 + 依据
3. 动作 ≤3 个且用动词(不是"选项 1");**第三格永远"稍后"**
4. 续报 summary 前缀"[续报]",复读 thread_history 最后一条 resolution 作引子
5. 用户已 dismiss 2 次同 thread → 改 silent + 写 user_state(永不弹第三次)

上下文:<kind> <thread_ctx=last_resolution+last_summary+history> <user_model.tone>
```

## 3. 输出模板(写到 notify_items,kind='resume')

```json
{
  "summary": "[续报] <≤120 字:上次你 X,现在 Y>",
  "options": ["<动词1>", "<动词2>", "稍后"],
  "priority": "normal",
  "kind": "resume"
}
```

## 4. 边界

- ✅ 允许:同 thread_id 复用 `snooze_promote_loop` 的写法(30s 内复用 promote_notify)
- ✅ 允许:展开层写完整 thread_history(前文时间线),但 TTS 只念标题+一行
- ❌ 禁止:跨 thread 合并(L2 是单议题续命,B7 跨通道去重才合并)
- ❌ 禁止:`kind='normal'` 新建独立通知(那是 L3)

## 5. 红线(action='failure')

- `promote_notify()` 返回 None 但仍尝试 broadcast(说明单活动锁被占,不该强行晋升)
- 同 thread 1 小时内累计 3 条 resume 类(用户被打扰天花板)
- LLM 输出未带动词选项(只有名词)

## 6. 落库后置

```sql
-- 1. enqueue_notify 但不传 requires_feedback=1(若 7d 老化那种关键事项才传)
-- 2. snooze_promote_loop 写法(派生 kind='resume'):
INSERT INTO notify_items(event_seq,channel_id,state,summary,options,
  requires_feedback,priority,created_at,thread_id,kind)
  VALUES(:seq,:ch,'queued',:summary,:options,1,'normal',:ts,:tid,'resume');
UPDATE notify_threads SET last_item_id=:iid, snoozed_until=NULL, updated_at=:ts WHERE id=:tid;
-- 3. promote_notify() — 走单活动锁
-- 4. feedback_events(source='proactive_loop', action='resumed')
-- 5. loop_state:同 L1
```
