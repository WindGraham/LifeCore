# 关闭开关 — Kill Switch

> 来源:docs/15 §5 安全边界 + docs/13 §3.5 礼貌预算
> 用途:**任何 LLM 调用前检查** + **feedback handler 后置检查** + **UI 按钮直写**

## 1. 三档 kill 范围(scope)

| scope | 影响 | 谁会触发 | 何时触发 |
|---|---|---|---|
| `global` | proactive_loop 完全停 | 用户 UI 按钮 / 紧急一键 | 用户说"全部关" |
| `device:<id>` | 某设备的入队 + bridge 不再上报 | 用户 UI / 设备掉线 | `last_health='down' > 1h` |
| `kind:<kind>` | 单个 kind 不入队,但其他仍可 | 反馈 3 次 👎 / 矫正通道 | feedback_interpret 触发 |

## 2. 判定规则(proactive_loop 启动时 + 每次决策前)

```sql
-- 1. global 检查
SELECT 1 FROM user_kill_switches
 WHERE scope='global' AND enabled=0
   AND (expires_at IS NULL OR expires_at > strftime('%s','now'));
-- 命中 → 完全停,日志 `[proactive_loop] killed_by_global`

-- 2. device 检查(每个 kind 决策前)
SELECT scope FROM user_kill_switches
 WHERE scope LIKE 'device:%' AND enabled=0
   AND (expires_at IS NULL OR expires_at > strftime('%s','now'));
-- 把这批 device_id 拉出来,凡涉及该设备的 kind 全跳过

-- 3. kind 检查(每个 kind 决策前)
SELECT 1 FROM user_kill_switches
 WHERE scope=:kind_scope AND enabled=0
   AND (expires_at IS NULL OR expires_at > strftime('%s','now'));
-- 命中 → 该 kind 跳过
```

## 3. 触发的三路径

### 路径 A:用户主动关(UI 按钮或语音)
```sql
INSERT OR REPLACE INTO user_kill_switches(scope, enabled, reason, expires_at, created_at, updated_at)
  VALUES(:scope, 0, :reason, :expires_at, :ts, :ts);
INSERT INTO feedback_events(kind, action, resolution_text, created_at, source)
  VALUES(NULL, 'settings_changed', :reason, :ts, 'app');
```

### 路径 B:反馈 3 次 👎 自动降级(feedback_interpret 触发)
```sql
-- 7d 内同 kind dismissed >= 3
SELECT COUNT(*) FROM feedback_events
 WHERE kind=:k AND action='dismissed' AND created_at > strftime('%s','now')-604800;
-- >= 3 → 永久 kill(无 expires_at)
INSERT OR REPLACE INTO user_kill_switches(scope, enabled, reason, expires_at, created_at, updated_at)
  VALUES('kind:'||:k, 0, 'auto_3_dismissed', NULL, :ts, :ts);
-- 同时写 behavior_trust
UPDATE behavior_trust SET status='disabled', last_eval_reason='auto_3_dismissed', updated_at=:ts WHERE kind=:k;
UPDATE proactive_kinds SET enabled=0, updated_at=:ts WHERE kind=:k;
```

### 路径 C:设备健康降级(docs/15 §1)
```sql
-- devices.last_health='down' > 1h
SELECT 1 FROM devices WHERE last_health='down' AND (strftime('%s','now') - last_seen) > 3600;
-- → 写 user_kill_switches(scope='device:<id>', enabled=0, reason='auto_health_down', expires_at=now+24h)
```

## 4. 用户"撤回 kill"(恢复)

```sql
-- 用户在 UI 说"重新打开 X"
DELETE FROM user_kill_switches WHERE scope=:scope AND reason != 'user_revoke';  -- user_revoke 永久的不删
-- 若是 kind 永久关,需显式 user_revoke:
UPDATE user_kill_switches SET expires_at=strftime('%s','now') WHERE scope=:scope AND reason='user_revoke';
INSERT INTO feedback_events(kind, action, resolution_text, created_at, source)
  VALUES(:k, 'settings_changed', 'user_re_enabled', :ts, 'app');
-- 写 nightly_learning 标志:重新评估 trust_stage
UPDATE behavior_trust SET status='active', stage_started_at=:ts, eval_due_at=:ts+604800,
                          last_eval_reason='user_re_enabled', updated_at=:ts WHERE kind=:k;
```

## 5. 红线

- ❌ **绝不**在 `proactive_kinds.enabled=0` 时绕过 kill switch
- ❌ **绝不**让 `user_revoke` 的 kill 自动恢复(必须用户显式)
- ❌ **绝不**让 L3 行为在 `device_health='down'` 时执行
- ❌ **绝不**在 kill switch 未生效的情况下写 `feedback_events.action='settings_changed'`

## 6. 4 段式失败的"立即停止"也算 kill

```sql
-- 失败恢复第 1 段:24h 同 kind block(临时 kill)
UPDATE proactive_loop_state
  SET block_until=strftime('%s','now')+86400,
      consecutive_failures=consecutive_failures+1
  WHERE kind=:k;
-- 这等价于临时 kind 级 kill,但不写 user_kill_switches(不污染审计表)
-- 24h 后自动恢复 — 与永久 kill 区分
```

## 7. UI 端最小暴露

- 三个按钮:**全停 / 只关 L3+ / 临时 24h 静默**
- 24h 静默 = INSERT user_kill_switches(scope='global', enabled=0, expires_at=now+86400)
- 全停 = expires_at=NULL(user_revoke 风格)
- 只关 L3+ = scope='kind:L3.*' OR scope='kind:L4.*' OR scope='kind:B5.followup_tracker'
