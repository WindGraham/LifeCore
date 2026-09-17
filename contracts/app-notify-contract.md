# App ↔ Server 通知契约 v1（冻结 2026-09-17，双端并行开发的对齐基准）

> 实现见 docs/10-通知线程延续设计方案.md。本契约由父 agent 冻结，两侧实现以此为准，冲突时改代码不改契约。

## 1. REST 扩展

### GET /v2/notify/active（响应扩展，向后兼容）
```json
{
  "server_time": "...",
  "active": {
    "id": 42, "event_seq": 39, "channel_id": "ch_d023e95e",
    "state": "awaiting_feedback",
    "summary": "……", "options": ["加入日历", "忽略", "稍后"],
    "requires_feedback": true, "priority": "high",
    "resolution": null, "created_at": "...",
    "thread_id": 7, "kind": "normal",
    "thread_title": "社团招新摊位安排",
    "generation": 3,
    "thread_ctx": {
      "last_resolution": "snooze",
      "last_summary": "上次那条的摘要",
      "history": [
        {"id": 40, "summary": "…", "resolution": null, "created_at": "..."},
        {"id": 35, "summary": "…", "resolution": "snooze", "created_at": "..."}
      ]
    }
  },
  "queue": [ {同构, 但 state="queued", thread_ctx 可为 null} ]
}
```
- `generation` = 线程的 item_count（代际角标用）。
- `thread_ctx.history` ≤3 条，新→旧。

### POST /v2/notify/items/{id}/feedback（扩展）
body: `{"action": "actioned|dismissed|snooze", "minutes": 30}` 或 `{"action":"snooze","until": <unix_ts>}`。
- snooze 必须带 minutes 或 until；服务端写 notify_threads.snoozed_until。
- 语义不变：返回 {"resolved": action, "next_active": ...}。

### GET /v2/notify/threads（新）
```json
{"threads": [{"id": 7, "thread_key": "wx:8832", "channel_id": "ch_d023e95e",
  "title": "社团招新摊位安排", "item_count": 3, "kind_latest": "resume",
  "last_resolution": "snooze", "last_resolved_at": "...", "snoozed_until": 1726..., "updated_at": "..."}]}
```
新→旧排序。

### GET /v2/notify/threads/{tid}/items（新）
```json
{"items": [{"id": 42, "summary": "…", "kind": "normal", "state": "awaiting_feedback",
  "resolution": null, "options": ["…"], "created_at": "..."}]}
```
旧→新排序（时间线阅读顺序）。

## 2. WS 推送（低耗电统治模式）

`wss://<base>/v2/notify/stream?token=<device_token>`
- 鉴权：query token（WS 握手头受限）。
- 服务端在以下时机向**所有**在线连接广播完整快照（JSON 同 /v2/notify/active 响应体）：
  新 item 入队 / promote / feedback 决议 / snooze 到期晋升。
- 心跳：客户端 OkHttp pingInterval 20s；nginx 已配 proxy_read_timeout 3600s。
- 断线：客户端指数退避重连（2s→5s→10s→30s→60s 封顶）；**WS 断开超过 2 分钟才降级为 60s 轮询兜底**，WS 恢复即停轮询。

## 3. 通知身份（Android 端规则）

- notification_id = `100000 + thread_id`（无 thread 用 `200000 + item_id`）——同议题永远更新同一条通知（续命语义）。
- MessagingStyle：aiPerson 固定 "LifeCore"；新条目 addMessage；通知内最多保留最近 3 条 message。
- 动作按钮：options 前 3 个 → Action（动词文案直接用 options 原文），点击即 feedback(actioned/dismissed/snooze 映射：末位含"稍后"→snooze 默认 30min)。
- thread_ctx.last_resolution=snooze 且 kind=resume 时，正文首行加灰字引用行：`上次你说"稍后" · {MM-dd HH:mm}`。
- 点击通知 → ThreadDetailActivity（extra thread_id）。
- 锁屏可见性 PRIVATE，public 版本只显示"LifeCore 有新事项"。

## 4. thread_key 派生（服务端唯一实现）
① payload.thread_key ② payload.pointer 的命名空间前缀（第一个 ":" 后若还有 ":" 则截到第二段，如 `wx:8832:chat123`→`wx:8832`；`gmail:abc`→`gmail:abc`）③ channels.session_discriminator ④ `ch:{channel_id}`。

## 5. 时钟与晋升（服务端唯一实现）
- asyncio 后台循环 30s：扫 notify_threads.snoozed_until <= now。
- 幂等：同 thread 已有 queued/awaiting item 则跳过；否则 INSERT kind='resume' item（summary 原摘要前加 `[续报] `，options 追加"稍后"）→ promote_notify()。
- 晋升后触发 WS 广播。
