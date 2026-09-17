# B1 收件箱摘要 — 行为触发 Prompt

> 来源:docs/11 §4-1 晨间简报 + docs/13 §4 B1(=文档原 B6 未读聚合日报,L1→弱 L2)
> 层:**L1 安静收集**(永远 enqueue_mode='silent',绝不弹通知)
> 灰度:T1 起步,7 天后评估升级 T2

## 1. 触发条件

```sql
-- 来自 proactive_kinds.trigger_sql(B1.inbox_summary 已 seed)
SELECT COUNT(*) FROM notify_items
 WHERE state IN ('queued','awaiting_feedback')
   AND created_at >= strftime('%s','now')-86400
   AND (SELECT MAX(f.created_at) FROM feedback_events f WHERE f.source='app') IS NULL;
```
含义:**过去 24h 用户没碰过 App** + **有 ≥5 条未读候选**。需 `last_app_active_at` 字段 → 若暂无,降级用 `(MAX(feedback_events) < now-8h)`。

## 2. 判定 prompt(给 LLM,**最大不打扰约束**)

```
[B1.inbox_summary]
你要为用户生成 24h 收件箱摘要,规则:
1. **不弹通知,不写 notify_items,不动单活动锁** — 只写 user_state
2. 用户打开 App 才会看到(在主页面 "今天已为你做完" 卡片)
3. 摘要格式:过去 24h 5 条候选,每条 ≤40 字,**必须加判断/合并**:
   - 同议题合并(如 3 条同群聊 → 一条)
   - 标 [紧急] / [待办] / [可忽略] 三档
   - 末尾 1 句"我观察到...",指出 1 个用户可能没注意的模式
4. 总字数 ≤400
5. 同摘要 24h 内已存在 → 覆盖而非堆

上下文:<过去 24h 5-10 条候选摘要> <user_model.tolerance.spam> <preference.tone>
```

## 3. 输出模板

```json
// 写 user_state(不写 notify_items)
{
  "key": "proactive/B1.inbox_summary/<date>",
  "value": {
    "summary": "过去 24h 你有 5 条新信息:\n1. [紧急] 牙医 14:00 改期确认\n2. [待办] Alice 问 X 答了吗\n3. [可忽略] 3 条群聊水\n\n我观察到:你最近 7 天对群聊水 < 全部 dismissed,可以考虑静音该群?",
    "candidates": [
      {"thread_id": 12, "kind": "urgent"},
      {"thread_id": 8,  "kind": "todo"}
    ],
    "ttl_hours": 24,
    "shown_to_user": false
  }
}
```

## 4. 边界

- ✅ 允许:写 user_state / schedule / running_items
- ❌ 禁止:enqueue_notify 任何路径
- ❌ 禁止:调 MCP 改外部状态
- ❌ 禁止:把"我观察到..."推断当事实(必须 source='inferred',confidence ≤ 0.5)

## 5. 红线(action='failure')

- `last_app_active_at` 不可用(配置缺失)→ 降级为不触发,不强行推断
- 候选 < 5 条 → 不触发(无意义)
- LLM 输出字数 > 500(浪费 token + 用户不会看)
- 摘要含原始邮件/聊天全文复读(违反 docs/13 §7)

## 6. 落库后置

```sql
INSERT OR REPLACE INTO lists(name,key,value_json,created_at,updated_at)
  VALUES('user_state', 'proactive/B1.inbox_summary/'||date('now'), :v, :ts, :ts);
INSERT INTO feedback_events(kind, action, summary_hash, created_at, source)
  VALUES('B1.inbox_summary','silent_done',:h,:ts,'proactive_loop');
UPDATE proactive_loop_state
  SET last_fired_at=:ts, last_daily_date=date('now','localtime'),
      last_daily_count=last_daily_count+1, last_scan_at=:ts
  WHERE kind='B1.inbox_summary';
```

## 7. T1 灰度评估

7 天后 nightly_learning 查 `feedback_events WHERE kind='B1.inbox_summary'`:
- count ≥ 10 → 评估
- 用户开 App 主动点开 "今天已为你做完" 卡片 → `actioned` 计入
- 7 天内 0 次点开 → `actioned_rate < 5%` → `status='rolled_back', reason='low_engagement'`
