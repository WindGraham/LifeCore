# L1 信号收集(安静收集)— 行为层 Prompt

> 来源:docs/13 §4 L1 预防 + docs/15 §1 devices 健康前置
> 适用 kind:`L1.silent` `B1.inbox_summary` `B2.calendar_prep` `B4.routine_check`(L2 但 enqueue_mode='silent')

## 1. 触发条件(SQL 选)

```sql
SELECT k.* FROM proactive_kinds k
JOIN proactive_loop_state s ON s.kind = k.kind
WHERE k.layer = 'L1' AND k.enabled = 1
  AND k.trust_stage IN ('T1','T2','T3')
  AND (s.block_until IS NULL OR s.block_until <= strftime('%s','now'))
  AND (s.last_fired_at IS NULL OR s.last_fired_at + k.cooldown_sec <= strftime('%s','now'))
  AND (s.last_daily_date != date('now','localtime') OR s.last_daily_count < k.daily_budget);
```

按 `proactive_kinds.trigger_sql` 逐条跑,有结果则该 kind 触发。

## 2. 判定 prompt(给核心 LLM 看的 system 提示,**拼接进 state_block**)

```
[proactive/L1]
你正在裁决一个 L1 安静收集行为。规则:
1. 这是后台预取/聚合/草稿,**绝不弹通知**,用户不打开 App 永远看不到
2. 你的输出 = 一段 ≤200 字的"我做了什么"摘要,我会写进 user_state,下次用户开 App 时首屏被动呈现
3. 必须加判断/关联/结论,**禁止复读原始事件原文**
4. 若 24h 内同 thread 已有 1 条 L1 摘要 → 合并覆盖,不堆条目
5. 失败时回 action='silent_skip',写 running_items 一条"为何不收",绝不静默丢失

上下文:<kind=...> <trigger_payload=...> <user_model=...> <recent_L1=...>
```

## 3. 输出模板(写到 lists(name='user_state'))

```json
{
  "key": "proactive/L1/<kind>/<date>",
  "value": {
    "summary": "<≤200 字判断式摘要>",
    "kind": "<kind>",
    "evidence": ["<thread_id>", "<user_model_slot>"],
    "ttl_hours": 24,
    "shown_to_user": false
  }
}
```

## 4. 边界

- ✅ 允许:写 `user_state` / `schedule` / `running_items`;调只读 MCP(`thread_history`/`read_user_model`);缓存到 LIFECORE 进程内 dict(进程死了重建)
- ❌ 禁止:任何 `enqueue_notify()` 调用;`promote_notify()` 任何写通知的操作;跨设备转发
- ❌ 禁止:把 LLM 输出当权威事实——若 user_model 没有 `confidence≥0.6` 的相关 slot,只写"待验证"

## 5. 红线(直接 action='failure')

- LLM 返回含"我不确定"、"也许"、"可能"等模糊词超过 3 次
- 触发了 `proactive_kinds.trigger_sql` 但无对应数据(空触发 = 配置错)
- user_model 中 `preference.notify.window` 与触发时间冲突(用户在睡觉,你还收——浪费 token)

## 6. 落库后置

```sql
-- 1. 写 user_state
INSERT OR REPLACE INTO lists(name,key,value_json,created_at,updated_at)
  VALUES('user_state', :k, :v, :ts, :ts);
-- 2. 写 feedback_events(source='proactive_loop', action='silent_done') — 仅审计
INSERT INTO feedback_events(kind, action, summary_hash, created_at, source)
  VALUES(:kind, 'silent_done', :h, :ts, 'proactive_loop');
-- 3. 更新 loop_state 计数
UPDATE proactive_loop_state
  SET last_fired_at=:ts, last_daily_date=date('now','localtime'),
      last_daily_count=last_daily_count+1, last_scan_at=:ts
  WHERE kind=:kind;
```
