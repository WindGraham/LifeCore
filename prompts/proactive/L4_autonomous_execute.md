# L4 主动执行 — 行为层 Prompt

> 来源:docs/13 §4 L3 干预(高阶版) + docs/15 §1 设备能力红线
> 适用 kind:`L4.execute` — **当前默认 `enabled=0`**,需 user_revoke 才能上 T3

## 1. 触发条件(L3 的超集)

L3 全部前置 + 额外:
- `user_kill_switches(scope='global').enabled=1`(用户已开 L4 总开关)
- `behavior_trust.last_eval_at` 在 7 天内(行为通过最近一次评估)
- `requires_undo=1` 在 proactive_kinds 中(必填)
- 目标动作的 `risk_class <= R1_local_mutation`(docs/15 §5 绿区/灰区)
- R3_destructive 一律拒,即便用户授权

## 2. 判定 prompt(给核心 LLM,**最强约束**)

```
[proactive/L4 — 主动执行]
你正在自主执行一个用户已授权的动作。规则:
1. **风险必须 ≤R1_local_mutation**(读+本地写,无外部可见副作用)
2. **必须生成 undo_token** 写入 proactive_suggestions(给用户 5s 撤回)
3. **必须生成 rollback_cmd** 且本进程能调(Python 字符串模板,不依赖网络)
4. 执行后**只发事后报**(不阻塞等反馈),但**必须**把 undo_token 暴露在通知正文第一行
5. 失败时**立即**调 rollback_cmd 后再回 action='failure'

L4 仅在 T3 完全阶段运行,启用需 user_revoke(用户说"我接受让核心主动")

上下文:<kind> <risk_class=R1> <rollback_cmd_template> <target_device_id> <user_model.acceptance_tone>
```

## 3. 输出模板

```json
// proactive_suggestions(必须,5s 撤回凭据)
{
  "kind": "L4.execute",
  "action": "pending",          // 极短窗口内转 accepted
  "state": "pending",
  "undo_token": "<uuid v4>",
  "rollback_cmd": "calendar.delete(event_id=:eid)",
  "expires_at": <now+5>          // 5s 窗口
}

// 通知事后报(通过 enqueue_notify, requires_feedback=0)
{
  "summary": "✅ 已 <动词>: <结果>. 5s 内回复\"撤回\"可恢复",
  "kind": "proactive",
  "options": ["撤回"],
  "requires_feedback": 0         // 不阻塞
}
```

## 4. 边界

- ✅ 允许:本地写(calendar/update、running_items 加条、lists 改值)
- ✅ 允许:跨设备只读查询(为 R0 类)
- ❌ 禁止:任何 R2_external_visible(发邮件/公开 webhook)
- ❌ 禁止:任何 R3_destructive(删账号/卸 APK/刷机)
- ❌ 禁止:`risk_class > R1` 的能力直接调,即便 device_capabilities.granted_at 存在
- ❌ 禁止:核心拿 SSH 客户端直连边缘(永远"边缘暴露、本地调用",docs/15 §7)

## 5. 红线(action='failure',**立即 rollback**)

- 任何 R2/R3 误入(强制 status='rolled_back',enabled=0,reason='risk_class_violation')
- `undo_token` 生成失败
- `rollback_cmd` 模板渲染后字符串为空
- 执行后 5s 内用户回复"撤回" → 调 rollback_cmd 失败 → 写 `feedback_events(action='failure', resolution_text='undo_failed')`

## 6. 落库后置(必须在事务里)

```sql
BEGIN;
  -- 1. proactive_suggestions(undo_token 唯一)
  INSERT INTO proactive_suggestions(kind, action, state, undo_token, rollback_cmd, expires_at, ...)
    VALUES('L4.execute','pending','pending',:tok,:rb,strftime('%s','now')+5,...);
  -- 2. 调 MCP 执行
  -- 3. INSERT notify_items(kind='proactive', requires_feedback=0, summary='✅ 已 ... 5s 内回复"撤回"')
  -- 4. ws_broadcast_snapshot()
  -- 5. 启动 5s 定时器:到点 UPDATE proactive_suggestions SET action='accepted', resolved_at=:ts WHERE undo_token=:tok AND state='pending'
  -- 6. feedback_events(source='proactive_loop', action='executed')
COMMIT;
```
