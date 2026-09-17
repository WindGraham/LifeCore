# L3 决策请求 — 行为层 Prompt

> 来源:docs/13 §4 L3 干预 + docs/13 §4 失败兜底四件套
> 适用 kind:`L3.decision_lock` `B5.followup_tracker`

## 1. 触发条件(最严格的一道闸)

```sql
-- 必须 4 个 AND 全通过:
-- (a) kind.enabled=1 AND trust_stage='T3' (L3 默认 T3 才上)
-- (b) 同 kind 7 天内最多 1 次
-- (c) 当前无 awaiting_feedback 活动项(单活动锁未被占)
-- (d) 设备心跳 fresh + preference.notify.window ∈ 区间
SELECT 1 FROM proactive_kinds k
  JOIN proactive_loop_state s ON s.kind=k.kind
  JOIN user_kill_switches ks ON ks.scope='kind:'||k.kind
 WHERE k.kind=:kind AND k.enabled=1 AND k.trust_stage='T3'
   AND (s.block_until IS NULL OR s.block_until<=strftime('%s','now'))
   AND COALESCE(s.last_daily_count,0) < k.daily_budget
   AND NOT EXISTS (SELECT 1 FROM notify_items WHERE state='awaiting_feedback')
   AND NOT EXISTS (SELECT 1 FROM bridge_heartbeat WHERE last_seen<strftime('%s','now')-300)
   AND ks.enabled=1
LIMIT 1;
```

## 2. 判定 prompt(给核心 LLM,**裁决关键**)

```
[proactive/L3 — 决策请求]
你正在生成一个需要用户拍板的关键通知。规则:
1. **必须挂撤回按钮**("不,撤回"),无按钮不发;通知正文最后一行必带"这种提醒不合适?"
2. **默认 draft 态**:写到 proactive_suggestions(state=pending),等用户开 thread 才晋升
3. 摘要 ≤80 字:动作 + 关键参数 + "我打算这样,但等你点头"
   反例:"我准备 X"(空洞)
   正例:"我准备把 14:00 牙医改到 16:00,留 30min 缓冲,确认?"
4. 选项严格 3 个:["<接受>","<接受但改 X>","不,撤回"];不混"稍后"
5. 7 天内被 dismiss+评论"别主动"→ 直接 action='disabled',写到 user_kill_switches

上下文:<kind> <user_model.acceptance_tone> <thread_ctx> <pending_suggestion_payload>
```

## 3. 输出模板(proactive_suggestions + notify)

```json
// proactive_suggestions 必落(失败兜底 #1 的依据)
{
  "kind": "<kind>",
  "thread_id": <tid>,
  "payload_json": "<入队时输入快照>",
  "action": "pending",
  "state": "pending",
  "confidence": 0.6,
  "expires_at": <now+7d>,
  "rollback_cmd": "<反向 MCP 命令,如 calendar.update 删除新事件>"
}

// notify_items 同步
{
  "summary": "<≤80 字提问>",
  "options": ["确认", "改时间", "撤回"],
  "kind": "proactive",
  "requires_feedback": 1
}
```

## 4. 边界

- ✅ 允许:`promote_notify()` 立即晋升(单活动锁已检查通过)
- ✅ 允许:`rollback_cmd` 落库,5s 内用户说"撤回"→ 调 rollback_cmd 反向
- ❌ 禁止:任何 `requires_feedback=0` 的静默 L3(L3 必须等用户)
- ❌ 禁止:LLM 跳过 proactive_suggestions 落库直接发(失败兜底 #1 无依据)

## 5. 红线(action='failure',触发 4 段式恢复)

- LLM 输出无"撤回"选项
- `proactive_suggestions` 写入失败
- 用户 60s 内对同 thread 连续 2 次 dismiss/snooze → 道歉 + thread 永久 disabled + 写 running_items
- 矫正通道文本含"别主动" → 立即写 `user_kill_switches(scope='kind:<kind>', enabled=0, reason='user_revoke')`

## 6. 落库后置

```sql
BEGIN;
  -- 1. 写 proactive_suggestions
  INSERT INTO proactive_suggestions(...) VALUES(...);
  -- 2. enqueue_notify
  INSERT INTO notify_items(...) VALUES(...);
  -- 3. promote_notify() — 单活动锁
  -- 4. ws_broadcast_snapshot()
  -- 5. feedback_events(source='proactive_loop', action='decision_pending', kind=:k)
  -- 6. loop_state 计数
COMMIT;
```
