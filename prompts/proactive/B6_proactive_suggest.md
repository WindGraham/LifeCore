# B6 主动建议 — 行为触发 Prompt

> 来源:docs/13 §4 B5 原 "凌晨深度工作守护" + docs/11 §4-3 沉默问候
> 层:**L2 状态报告**(resume 或 normal 入队,可忽略)
> 灰度:T2 谨慎(1 天 ≤2 次,45min 内静默)

## 1. 触发条件(2 选 1)

### A. 凌晨守护
```sql
SELECT 1 FROM bridge_heartbeat
 WHERE last_seen > strftime('%s','now')-60
   AND strftime('%H','now','localtime') IN ('23','00','01','02','03','04')
   AND NOT EXISTS (
     SELECT 1 FROM feedback_events WHERE created_at > strftime('%s','now')-1800
   );
```

### B. 沉默问候
```sql
SELECT 1 WHERE NOT EXISTS (
  SELECT 1 FROM feedback_events WHERE created_at > strftime('%s','now')-86400
)
  AND EXISTS (SELECT 1 FROM running_items WHERE key LIKE 'health%' OR key LIKE 'family%')
  AND 7 天内同 kind 触发 ≤ 1 次
```

## 2. 判定 prompt(给 LLM)

```
[B6.proactive_suggest]
你正在生成一条轻量建议,规则:
1. **A. 凌晨守护**(23-04 点 + 30min 无输入):
   - 摘要 ≤60 字:"已专注 90 分钟了,要起身活动 5min 吗?"
   - 选项:["起身", "再 30min", "不用"]
   - focus=true 后 45min 内静默(查 user_state.focus_until)
2. **B. 沉默问候**(24h 无反馈 + 健康/家庭 running_items 静默 >24h):
   - 摘要 ≤80 字:直接提醒"X 这事有更新"或"该关心下家人了"
   - 选项:["现在看", "稍后", "已处理"]
   - 7 天 1 次上限
3. **不能复读原文**(违反 docs/13 §7)
4. 若 user_model 已有 focus_until 字段未到期 → 跳过 A
5. 若 7d 内同 kind dismissed ≥2 → 跳过(连续 👎 上限)

上下文:<触发模式 A/B> <user_model.focus_until> <relevant running_items>
```

## 3. 输出模板

```json
// 模式 A(凌晨守护)
{
  "summary": "🌙 已专注 90 分钟,要起身活动 5min 吗?",
  "options": ["起身", "再 30min", "不用"],
  "kind": "proactive",
  "priority": "low"
}

// 模式 B(沉默问候)
{
  "summary": "📌 X 议题有更新;或:该关心下家人了。",
  "options": ["现在看", "稍后", "已处理"],
  "kind": "proactive",
  "priority": "normal"
}
```

## 4. 边界

- ✅ 允许:enqueue_notify(kind='proactive')
- ✅ 允许:写 user_state.focus_until = now+45min(凌晨模式静默期)
- ❌ 禁止:跨 thread 合并
- ❌ 禁止:在 `preference.notify.window` 之外触发
- ❌ 禁止:模式 B 在用户明确说"沉默时别主动"时触发(查 `preference.proactive.greeting=false`)

## 5. 红线(action='failure')

- focus_until 字段未设但 LLM 假设存在(配置缺失)
- 模式 A 与 7 天内同主题已触发(浪费)
- 模式 B 在 7d 内已触发过 1 次
- LLM 输出含"你应该..."说教句(违反 docs/13 §7 反对清单)

## 6. 落库后置

```sql
-- 模式 A:写 user_state.focus_until + enqueue_notify
INSERT OR REPLACE INTO lists(name,key,value_json,created_at,updated_at)
  VALUES('user_state', 'focus_until', json_object('ts', strftime('%s','now')+2700), :ts, :ts);
INSERT INTO notify_items(...) VALUES(:seq,:ch,'queued',:summary,:options,1,'low',:ts,NULL,'proactive');
-- 模式 B:仅 enqueue_notify
INSERT INTO notify_items(...) VALUES(...);
-- promote_notify()
-- ws_broadcast_snapshot()
INSERT INTO feedback_events(item_id, kind, action, summary_hash, created_at, source)
  VALUES(:iid, 'B6.proactive_suggest', 'suggested', :h, :ts, 'proactive_loop');
UPDATE proactive_loop_state
  SET last_fired_at=:ts, last_daily_date=date('now','localtime'),
      last_daily_count=last_daily_count+1, last_scan_at=:ts
  WHERE kind='B6.proactive_suggest';
```
