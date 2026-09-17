# 触发决策 — 输入信号 → 哪个 kind 触达

> 来源:docs/11 §4 7 条触发器 + docs/13 §4 7 个行为 + docs/15 §1 设备健康前置
> 用途:proactive_loop 协程(30s 扫一次)的主调度逻辑

## 1. 决策流程(伪代码)

```python
async def proactive_loop_once():
    conn = db()
    now = time.time()
    # 第一关:全局 kill switch
    global_ks = conn.execute("SELECT 1 FROM user_kill_switches WHERE scope='global' AND enabled=0 AND (expires_at IS NULL OR expires_at>?)", (now,)).fetchone()
    if global_ks: return  # 完全停
    # 设备健康
    device_ks = conn.execute("SELECT 1 FROM user_kill_switches WHERE scope LIKE 'device:%' AND enabled=0 AND (expires_at IS NULL OR expires_at>?)", (now,)).fetchall()
    bad_devices = {r[0].split(':',1)[1] for r in device_ks}
    # 第二关:列所有 enabled+trust_stage∈{T1,T2,T3} 的 kind
    kinds = conn.execute("SELECT k.*, s.* FROM proactive_kinds k LEFT JOIN proactive_loop_state s ON s.kind=k.kind WHERE k.enabled=1 AND k.trust_stage IN ('T1','T2','T3') ORDER BY k.layer, k.kind").fetchall()
    for k in kinds:
        # kind 级 kill
        kind_ks = conn.execute("SELECT 1 FROM user_kill_switches WHERE scope=? AND enabled=0", (f"kind:{k['kind']}",)).fetchone()
        if kind_ks: continue
        # block_until 失败恢复冷却
        if k['block_until'] and k['block_until'] > now: continue
        # cooldown_sec 同 kind 冷却
        if k['last_fired_at'] and now - k['last_fired_at'] < k['cooldown_sec']: continue
        # daily_budget 礼貌预算
        today = date.today().isoformat()
        if k['last_daily_date'] == today and k['last_daily_count'] >= k['daily_budget']:
            continue
        # 灰度过滤:T1 仅 trusted+工作时段
        if k['trust_stage'] == 'T1' and not _is_trusted_and_workhour(now): continue
        # T2 工作时段
        if k['trust_stage'] == 'T2' and not _is_workhour(now): continue
        # 跑 trigger_sql
        try:
            rows = conn.execute(k['trigger_sql']).fetchall()
        except Exception as e:
            logger.warning("trigger_sql fail %s: %s", k['kind'], e)
            continue
        if not rows: continue
        # 设备健康前置(B1/B2/B5/B7)
        if k['kind'] in ('B1.inbox_summary','B2.calendar_prep','B5.followup_tracker','B7.cross_channel_dedup'):
            if not _any_bridge_alive(conn, now, max_age=120): continue
        # 读对应 B*.md 拿"判定 prompt"片段(本地缓存到内存)
        # → 把 kind+rows+user_model 拼成 L1/L2/L3/L4 system 提示
        # → 调 LLM 拿"做什么"
        await _dispatch(conn, k, rows)
    conn.close()
```

## 2. 输入信号分类(每类决定 1-2 个 kind 优先)

| 输入信号 | 来源 | 优先 kind |
|---|---|---|
| **时间窗**(08:30 ± 15min) | `user_model/preference.briefing.time` | B1.inbox_summary(晨间简报) |
| **thread 老化**(last_resolution='snoozed' & >4h) | notify_threads | L2.resume_report |
| **沉默**(无 feedback 24h + 全部 heartbeat stale) | feedback_events + bridge_heartbeat | B6.proactive_suggest(只在健康/家庭 running_items 静默 >24h) |
| **bridge 异常**(last_seen > 2*normal) | bridge_heartbeat | 写 user_state,不入队(docs/11 §4-4) |
| **schedule 距 ≤2h**(travel/flight/appointment) | lists(name='schedule') | B2.calendar_prep + B5.followup_tracker |
| **重复模式**(≥3 次 running_items 同模式/周) | running_items | (核心裁决时检测) |
| **slot confidence 跨阈值** | user_model | 7 天 1 次抽,核心主动问 |
| **关系对象 >7d** | notify_threads.updated_at | B3.relationship_nudge |
| **日程冲突** | lists(name='schedule') 重叠 | B5.followup_tracker |
| **23-04 点 + 30min 无输入** | bridge_heartbeat + feedback_events | B6.proactive_suggest(45min 内静默) |
| **同主题 6h ≥2 通道** | notify_threads 摘要相似 | B7.cross_channel_dedup |

## 3. 灰度过滤 helper

```python
def _is_trusted_and_workhour(now_ts):
    trusted = user_model_get('preference.proactive.trusted', default=False)
    return trusted and _is_workhour(now_ts)

def _is_workhour(now_ts):
    h = datetime.fromtimestamp(now_ts).astimezone().hour
    return 9 <= h <= 21  # 工作时段
```

## 4. 决策输出

每个 kind 触发后 `_dispatch` 调用:

```python
async def _dispatch(conn, k, rows):
    layer = k['layer']
    if layer == 'L1': await _l1_silent(k, rows)        # → 读 L1_signal_collection.md
    elif layer == 'L2': await _l2_resume(k, rows)      # → 读 L2_status_report.md
    elif layer == 'L3': await _l3_decision(k, rows)    # → 读 L3_decision_request.md
    elif layer == 'L4': await _l4_execute(k, rows)     # → 读 L4_autonomous_execute.md
```

## 5. 决策失败兜底

任何 LLM 调用 5s 超时或抛异常:
1. `feedback_events(action='failure', kind=k['kind'], resolution_text=str(e))`
2. `behavior_trust.stage_failure_count++`
3. `proactive_loop_state.consecutive_failures++`,若 ≥3 → `proactive_kinds.enabled=0, behavior_trust.status='rolled_back'`
4. `proactive_loop_state.block_until = now + 86400`
5. 写日志 `[proactive_loop] kind=<k> failed: <e>` — 供 nightly_learning 看

## 6. 红线(永远不在决策里走)

- 任何 7d 内 `feedback_events` 同 `kind` `action='dismissed'` 累计 ≥3 → 跳过(连续 3 次 👎)
- 任何 `user_model/preference.proactive.*` 为 `false` → 跳该 kind
- 任何当前 `awaiting_feedback` 已有项 → L2 仍可入队(queued),L3 跳过
- 任何 `bridge_heartbeat` 全部 `last_seen > 300s` → L3/L4 跳过,仅 L1/L2 兜底
