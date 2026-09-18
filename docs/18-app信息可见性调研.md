# LifeCore App 信息可见性调研（只读 / 不修改代码）

> 范围：仅读调研 Android App（Kotlin）+ Hermes Web 控制台（React Today/Notify/...）+ lifecore-server（server.py）+ 设计文档。
> 目标：回答"App 当前能展示什么 / 看不到什么 / agent 流动操作链路上哪些对用户可见 / 哪些不可见"。
> 输入：docs/12、docs/13、docs/11、docs/15、docs/10 + 5 个 Kotlin Fragment + TodayPage.tsx + server.py（1303 行全文）+ PlayService.kt + Api.kt + ThreadDetailActivity.kt + lifecore-api.ts。
> 性质：**纯调研报告，不修改任何 Kotlin / Python / TypeScript / SQL 文件**。

---

## 1. 现状摘要（10 行）

1. **App = 5 等分远程终端 + 单活动锁通知器**：TodayFragment（卡片 B 决策 + 卡片 A threads 摘要复用 + 卡片 C 输入框）+ NotifyListFragment（active + 收件箱 + 排队 TextView）+ ChannelsFragment + JobsFragment + SettingsFragment。Hermes 控制台 TodayPage / NotifyPage 同构，但 **Threads 时间线在 Hermes 端没接**。
2. **唯一对用户可见的实时流 = `notify_items` 的 `awaiting_feedback`**（L2 决策）+ **`queued` 列表**（L1 静默入队的预览）。`logged` / `resolved` / snoozed / expired 在 App 全部 **不可见**。
3. **唯一一个跨进程实时通道 = `PlayService` 单 WS 连接 `/v2/notify/stream`**（pingInterval 20s + 应用层 60s 自定义 ping + Handler 1-3 次 / Alarm 4-10 次 / 15min 长尾兜底）。**WS 只接一种消息**：`/v2/notify/active` 整快照，App 解 `active.id` / `queue`。
4. **4 套数据/通道对 App 不可见**：`events` 原始流（有 `/v2/events` 端点但 App / Kotlin / React 都没调）、`bridge_heartbeat`（有 `/v2/bridge/status` 端点但 App 没调）、`lists` 全集（user_state / running_items / schedule / user_model 命名空间都没调）、`device_commands`（下行命令队列 + 回执）。
5. **Thread 模型存在但仅暴露头部**：`notify_threads` + `notify_items.thread_id` 已落地，`/v2/notify/threads` 返回 ≤200 条摘要。Kotlin 端 `NotifyListFragment` 用了 threads 列表 + 点进 `ThreadDetailActivity` 看 `notifyThreadItems(tid)` 时间线 + 长按撤销；**但 Hermes TodayPage 没调 `/v2/notify/threads`**，控制台今日页只看 active。
6. **agent 决策依据完全不可见**：哪些事件被标记 `requires_feedback=1`、谁决定了"入队"、哪条用了 thread_key 哪条没派生、为什么 state=queued→awaiting_feedback 在此刻晋升——用户 **看不到任何 reasoning**。
7. **专门发给自己需要自己看的 = awaiting_feedback 推送**（PlayService.handleSnapshot 通过 L2 抬头通知 + TTS 播报 + 单调守护 lastSpokenId 防止回放）。但 **没有"主人专属 vs 共享上下文"的二级分级**——所有 awaiting_feedback 都同等重要，没有 channel 隔离 / priority 之外的维度。
8. **session / jobs / channels / tts / asr / state-block 6 类 BFF 端点**：今日页/通知页/通道页/任务页/设置页/会话页都覆盖，但 **"通道健康度 / 推送延迟 / agent 决策日志 / 用户模型" 四个核心 agent 透明化场景均无对应端点 + 无对应 UI**。
9. **M2 设计目标"卡片 A 真 digest"未落地**：TodayFragment 注释里写"卡片 A: 复用 /v2/notify/threads（M2 接 /v2/digest/today）"——`/v2/digest/today` 端点 **服务端不存在**，当前直接拿 threads 列表当 digest 用。
10. **Prompts / 主动行为尚未注入**：prompts/core-agent 9 文件 + prompts/proactive 14 文件已部署，但 docs/13 §9.1 说明"proactive schema.sql 本次部署 **不生效**，等下迭代注入 init_db()"——所以 `feedback_events / thread_snapshots / proactive_kinds / proactive_suggestions / user_model_proposed` **表不存在**，B1-B7 主动行为尚未承载。

---

## 2. App 当前可见数据清单

### 2.1 Android App（Kotlin）端点-视图矩阵

| BFF 端点 | TodayFragment | NotifyListFragment | ChannelsFragment | JobsFragment | SettingsFragment | ThreadDetailActivity | PlayService（WS） | ChatActivity |
|---|---|---|---|---|---|---|---|---|
| `GET /v2/me` | – | – | – | – | ✅ 设备名+注册时间 | – | – | – |
| `GET /v2/notify/active` | ✅ 卡片 B | ✅ activeCard 顶部 | – | – | – | – | ✅ WS 快照 + 60s 轮询兜底 | – |
| `GET /v2/notify/threads` | ✅ 卡片 A（digest list） | ✅ threadsRv 列表 | – | – | – | – | – | – |
| `GET /v2/notify/threads/{tid}/items` | – | – | – | – | – | ✅ 时间线（IM 气泡） | – | – |
| `POST /v2/notify/items/{id}/feedback` | ✅ 3 按钮 | ✅ 3 按钮 | – | – | – | ✅ 长按撤销 + optBar | – (经 FeedbackReceiver) | – |
| `GET /v2/state-block` | – | – | – | – | – | – | – | – |
| `GET /v1/channels` | – | – | ✅ 通道名+archetype+uplink+created_at | – | – | – | – | – |
| `POST/DELETE /v1/channels` | – | – | ✅ 注册对话框+复制 ingest_url+secret | – | – | – | – | – |
| `GET /v2/jobs` | – | – | – | ✅ job 列表 | – | – | – | – |
| `POST/DELETE /v2/jobs` | – | – | – | ✅ 创建对话框 | – | – | – | – |
| `POST /v2/jobs/{jid}/{action}` | – | – | – | ✅ run/pause/resume | – | – | – | – |
| `GET /v2/sessions` | – | – | – | – | – | – | – | ✅ 会话列表 |
| `POST /v2/sessions` | – | – | – | – | – | – | – | ✅ 新建会话 |
| `GET /v2/sessions/{sid}/messages` | – | – | – | – | – | – | – | ✅ 历史气泡 |
| `POST /v2/sessions/{sid}/chat/stream` | – | – | – | – | – | – | – | ✅ SSE 流式对话 |
| `GET /v2/gateway/status` | – | – | – | – | ✅ 网关版本+platforms 状态 | – | – | – |
| `GET /v2/admin/config` | – | – | – | – | ✅ 4 键白名单编辑 | – | – | – |
| `PUT /v2/admin/config` | – | – | – | – | ✅ 重启网关 | – | – | – |
| `GET /v2/admin/mcp` | – | – | – | – | ✅ MCP 增删 | – | – | – |
| `POST /v2/tts` | – | – | – | – | ✅ 试听按钮 | – | ✅ TTS 播报 active | ✅ 单条播放 |
| `POST /v2/asr` | – | – | – | – | – | – | – | ✅ STT 录音 |

**结论（App）**：

- 8 个 Fragment/Activity 共覆盖 **17 个 BFF 端点**，**未调任何** `/v2/events` / `/v2/lists` / `/v2/commands/*` / `/v2/bridge/*` / `/v2/gateway/capabilities` / `/v2/me`（除 Settings）端点。
- **App 只看两类核心对象**：`notify_items`（仅 awaiting_feedback + queued）+ `channels` 元数据。
- **没有"事件流"视图**：没有任何 Fragment 拉过 `/v2/events`。

### 2.2 Hermes Web（React）TodayPage 端点-视图矩阵

| BFF 端点 | TodayPage | NotifyPage | ChatPage | ChannelsPage | JobsPage | SettingsPage |
|---|---|---|---|---|---|---|
| `GET /v2/notify/active` | ✅ statNotify + 决策卡 | ✅ active card + queue card | – | – | – | – |
| `GET /v2/notify/threads` | ❌（未调） | ❌（未调） | – | – | – | – |
| `GET /v2/sessions` | ✅ statSessions + 最近 3 个会话 | – | ✅ | – | – | – |
| `GET /v1/channels` | ✅ statChannels | – | – | ✅ | – | – |
| `GET /v2/jobs` | ✅ statJobs | – | – | – | ✅ | – |
| `GET /v2/state-block` | ✅ stateHint banner | – | – | – | – | – |
| `GET /v2/gateway/capabilities` | – | – | – | – | – | ✅ |

**结论（Web）**：

- **Hermes TodayPage 比 Android TodayFragment 更"瘦"**：它 **没有调 `/v2/notify/threads`** —— 所以 Hermes 控制台的"今日"页 **只显示 awaiting_feedback 决策 + 4 个统计 + 3 个最近会话 + 2 个跳转**，根本不展示历史议题。
- **TodayPage 是"stat-driven"，不是"thread-driven"**——这跟 Android 的"复用 threads 当 digest"不一样。
- Hermes NotifyPage 也只调 `/v2/notify/active`，**不调 `/v2/notify/threads`**——控制台通知页 **没有 thread inbox**。

### 2.3 WS（`/v2/notify/stream`）推送内容

```python
# server.py:602-611
def notify_snapshot() -> dict:
    active = SELECT * FROM notify_items WHERE state='awaiting_feedback' ORDER BY id LIMIT 1
    queue = SELECT * FROM notify_items WHERE state='queued' ORDER BY id LIMIT 20
    return {"server_time": iso(now()), "active": notify_item_full(active), "queue": [notify_item_full(q) for q in queue]}
```

| 字段 | 是否推给 App |
|---|---|
| `active.id` `summary` `priority` `options` `requires_feedback` `created_at` `thread_title` `generation` `thread_ctx` | ✅ PlayService 用作本地通知 + 标题 + 选项按钮 + TTS |
| `active.thread_id` `kind` | ✅ PlayService 用作 notification_id = 100000+tid（同 thread 同 nid） |
| `queue`（≤20 条，仅 summary+id） | ✅ NotifyListFragment TextView "排队中（n）\n..."，**App 重启时此 UI 已拉过 active，但 WS 不重播 queue**（PlayService 不渲染 queue 通知） |
| `events` 原始 | ❌ 不推 |
| `bridge_heartbeat` | ❌ 不推 |
| `lists.user_state` / `running_items` / `schedule` | ❌ 不推（这些是 pre_llm 注入，非 App 流） |
| `channels` 元数据 | ❌ 不推（App 主动拉） |
| `notify_threads` 全量 | ❌ 不推（仅 queue/active 携带 thread_title+generation） |

### 2.4 可见数据汇总

| 用户场景 | 可见性 |
|---|---|
| "有一条新事项等你决策" | ✅ L2 抬头通知 + TTS + 卡片 B + 通知中心卡片 |
| "还有 n 条排队中" | ✅ NotifyListFragment TextView 列出 summary，但 **没有 thread_title、kind、generation、channel_id** |
| "这一条被谁决策过、上一条原文" | ✅ ThreadDetailActivity 时间线（IM 气泡）+ thread_ctx.last_resolution + history 最近 3 条 |
| "我已经完成的（已办 / 已忽略 / 稍后）" | ❌ **完全不可见**——`/v2/notify/threads` 列表只展示 **头 200 条 threads**，每条含 `last_resolution + last_resolved_at + snoozed_until + item_count`，但 **没有任何"已办 / 已忽略"筛选 Tab** —— 全是混合视图 |
| "今天所有事件发生过什么" | ❌ 不可见 |
| "哪些通道在线 / 离线 / 延迟多大" | ⚠️ 半可见：ChannelsFragment 显示通道名 + created_at，**没有 last_event_at / event_count / host**（server.py:357 `channel_stats` 端点存在但 App 未调） |
| "哪些 agent job 在跑 / 暂停 / 已结束" | ✅ JobsFragment 全列表 + 暂停 / 运行中状态 |
| "我有哪些设备 / 哪个在线" | ⚠️ 半可见：SettingsFragment 显示当前设备名 + 网关 fingerprint，没列出所有设备，没显示 bridge_heartbeat |
| "核心 agent 现在看到的我是什么样的" | ❌ `/v2/state-block` 端点存在（server.py:801）但 **Android 0 调用 + Hermes TodayPage 仅渲染 1 行 hint banner**，完整内容 `[live] 待决策:1(...) \| 队列:n \| 通道:n \| [user_state] ... \| [running_items] ... \| [schedule] ... \| [services] ...` 对用户 **完全不可见** |
| "agent 是基于什么规则把我这条标成 requires_feedback" | ❌ 不可见（决策依据 = enqueue_notify 的 payload.requires_feedback 字段，App 完全不读） |
| "通道事件来时发生了什么（签名校验 / 转发 hermes / 入 events 表）" | ❌ 不可见 |

---

## 3. App 当前不可见数据清单

### 3.1 数据库层（SQLite）逐表对照

| 表 | 服务端端点 | 字段 | App 可见 | Web 可见 | 备注 |
|---|---|---|---|---|---|
| `devices` | – | name, token_hash, last_seen, created_at | ⚠️ 仅当前设备（SettingsFragment） | – | 缺多设备列表 / 在线状态 |
| `pair_codes` | `/.well-known/agent.md` 配对流 | code, expires_at | ❌ | ❌ | 配对页直走 |
| `channels` | `GET/POST/DELETE /v1/channels` + `/v1/channels/{id}`（stats） | name, archetype, direction, uplink_level, host, report_policy, session_discriminator, secret, created_at, revoked | ⚠️ ChannelsFragment 只显示 name+archetype+uplink+created_at | ✅ Hermes ChannelsPage | **缺 last_event_at / event_count / host** |
| `events` | `GET /v2/events?since=&limit=` | seq, channel_id, payload, upstream_status, received_at | ❌ **零调用** | ❌ **零调用** | 全部原始事件永久不可见 |
| `notify_items` | `/v2/notify/active` + `/v2/notify/threads` + `/v2/notify/items/{id}/feedback` | id, event_seq, channel_id, state, summary, options, requires_feedback, priority, resolution, created_at, resolved_at, thread_id, kind | ✅ 5 字段（id, summary, options, priority, requires_feedback）；⚠️ 部分（state, channel_id, generation, thread_title 可见但 UI 未渲染）；❌ event_seq / resolved_at | ✅ TodayPage / NotifyPage 同 | **缺 logged / resolved 全量历史的列表视图** |
| `device_commands` | `GET /v2/commands/pending` + `/v2/commands/{cid}/result` + `/v2/commands/{cid}` | id, device_id, action, args_json, state, result_json, error, created_at, updated_at | ❌ | ❌ | 下行命令队列 |
| `bridge_heartbeat` | `POST /v2/bridge/heartbeat` + `GET /v2/bridge/status` | device_id, name, detail_json, last_seen | ❌ | ❌ | 边缘执行器心跳 |
| `lists` | `GET/PUT/DELETE /v2/lists[/{name}/items/{key}]` + `POST /v2/lists/{name}/items/{key}/resolve` | name, key, value_json, resolved, created_at, updated_at | ❌ | ❌ | user_state / running_items / schedule / user_model 全部不可见 |
| `notify_threads` | `GET /v2/notify/threads` + `GET /v2/notify/threads/{tid}/items` | thread_key, channel_id, title, last_resolution, last_resolved_at, last_item_id, item_count, snoozed_until, updated_at | ✅ NotifyListFragment 显示 5 字段（id, title, item_count, last_resolution, snoozed_until, updated_at） | ❌ Hermes **未调** | 控制台 web 完全没接 thread inbox |

### 3.2 agent 推理层

| 推理产物 | 字段 | 当前可见 |
|---|---|---|
| `requires_feedback` 决策依据 | payload.requires_feedback（核心裁决） | ❌ |
| `suggested_priority` | payload.suggested_priority | ⚠️ 写入 priority 列，App 显示 PRIORITY 文本，无来源 |
| `feedback_options` | payload.feedback_options | ✅ App 渲染按钮文案 |
| `thread_key` 4 级派生 | thread_key = payload.thread_key > payload.pointer 命名空间前缀 > session_discriminator > ch:{id} | ❌ **派生过程完全不可见**，App 也看不到 thread_key 字符串本身 |
| `state` 转换（queued→awaiting_feedback→resolved） | DB 状态机 | ⚠️ 部分：ThreadDetailActivity 时间线可见 state / resolution，但 NotifyListFragment 不显示每条 item 的 state |
| `snooze_promote_loop` 30s 扫描 | 进程内 asyncio 协程 | ❌（kind='resume' 续报文案有"上次你说稍后"提示，但用户看不到"30 秒到期"机制） |
| `promote_notify` 单活动锁 | 进程内函数 | ❌（App 只看到 1 条 active / N 条 queue，不知道 FIFO 顺序） |

### 3.3 设备 / 网络 / 通道健康度

| 信息 | 端点 | App 可见 |
|---|---|---|
| `bridge_heartbeat` 设备心跳 | `/v2/bridge/status` | ❌ |
| `events` 按通道聚合（last_event_at / count / upstream_status） | `/v1/channels/{id}` | ❌（仅 Hermes ChannelsPage 可能显示，但 lifecore-api 未注册此调用） |
| `gateway_state.json` 平台态 | `/v2/gateway/status` | ⚠️ 仅 platforms 列表 + state/version，**无 active_agents 字段**（server.py:933 暴露但 App 未渲染） |
| `state-block` 全文 | `/v2/state-block` | ⚠️ Hermes TodayPage 仅渲染 1 行 hint；Android **完全没调** |

---

## 4. agent 流动操作链路图（标注可见性）

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ 外部源 (Web)                                                                       │
│  微信群/公众号 · google-calendar · tasks · gmail · drive · pku-study-monitor  │
└──────────────────────────────────────────────────────────────────────────────┘
          ↓ (PC daemon 拉取 + HMAC V2 签名)
┌──────────────────────────────────────────────────────────────────────────────┐
│ PC 端 daemon (VPS 上运行)                                                          │
│  wechat_watch · google-bridge · ...                                            │
│  可见性: ❌ 不可见 — App 看不到 daemon 进程、版本、心跳                                  │
└──────────────────────────────────────────────────────────────────────────────┘
          ↓ POST /hk/{channel_id}  (HMAC 签名 + ts)
┌──────────────────────────────────────────────────────────────────────────────┐
│ VPS lifecore-server (server.py:465 ingest)                                     │
│                                                                              │
│ ① verify_sig()         — 验签 ±300s                                       ❌    │
│ ② INSERT events        — 原始事件永久入库                                  ❌    │
│ ③ parse payload        — JSON 解析                                           ❌    │
│ ④ resolve_thread_key   — 4 级派生 (server.py:412)                          ❌    │
│    ① payload.thread_key  ② pointer 命名空间  ③ session_discriminator  ④ ch:{id}     │
│ ⑤ upsert_notify_thread — notify_threads 表 UPSERT                        ⚠    │
│ ⑥ INSERT notify_items  — state='queued'(requires_feedback=1)              ⚠    │
│                        或 state='logged'(requires_feedback=0)             ❌    │
│ ⑦ promote_notify()     — 单活动锁晋升 (server.py:402)                     ❌    │
│ ⑧ forward_to_hermes()  — POST /webhooks/{route_name} 转核心                ❌    │
│ ⑨ UPDATE events.upstream_status                                          ❌    │
│ ⑩ ws_broadcast_snapshot() — 推完整 notify_snapshot()                    ✅    │
│                                                                              │
│ 可见性: ②③④⑤⑥⑦⑧⑨ 全部对用户隐藏；⑩ 推 active+queue 给 App                       │
└──────────────────────────────────────────────────────────────────────────────┘
          ↓ WS /v2/notify/stream  (text/json 快照)
┌──────────────────────────────────────────────────────────────────────────────┐
│ App PlayService (PlayService.kt:152-216)                                       │
│                                                                              │
│ ① connectWs()        — pingInterval 20s + 应用层 60s 自定义 ping             ⚠    │
│ ② onMessage          — 解析 text → JSONObject snapshot                       ⚠    │
│ ③ handleSnapshot     — 取 active / queue                                   ✅    │
│ ④ lastSpokenId 单调守护 — SharedPreferences 持久化                            ⚠    │
│ ⑤ postItemNotif      — MessagingStyle + 同 thread 同 nid + L0/L1/L2 三档      ✅    │
│ ⑥ speak              — TTS 播放 (POST /v2/tts)                              ✅    │
│ ⑦ fallback poll      — WS 断开 2min → 60s 轮询 /v2/notify/active          ⚠    │
│                                                                              │
│ 可见性: ① 重连状态仅前台服务日志；②③④⑤⑥⑦ 用户能看到 TTS + 通知；                       │
│         "为什么是 L2 抬头" / "为什么是 lastSpokenId 守护" 不可见                          │
└──────────────────────────────────────────────────────────────────────────────┘
          ↓ 用户按通知按钮 或 卡片 B 按钮
┌──────────────────────────────────────────────────────────────────────────────┐
│ POST /v2/notify/items/{id}/feedback  (action: actioned/dismissed/snooze/undo)     │
│  server.py:646 notify_feedback()                                            │
│                                                                              │
│ ① 校验 state='awaiting_feedback'                                            ❌    │
│ ② UPDATE notify_items SET state='resolved', resolution=?, resolved_at=now   ⚠    │
│ ③ UPDATE notify_threads SET last_resolution=?, last_resolved_at=?,          ⚠    │
│                          snoozed_until=COALESCE(?, snoozed_until)                │
│ ④ promote_notify()   — 晋升下一条 queued → awaiting_feedback                ❌    │
│ ⑤ ws_broadcast_snapshot() — 推新快照                                       ✅    │
│ ⑥ return next_active  — 返回下一条（让 App 可乐观更新）                       ✅    │
│                                                                              │
│ 可见性: 用户按完按钮后立即看到 active 切换（卡片 B / 通知消失），                            │
│         但 ❌ 看不到 "为什么我的通知消失了 / 下一条是怎么选出来的 / snooze_until 是几分钟"        │
└──────────────────────────────────────────────────────────────────────────────┘
          ↓ snooze_promote_loop  进程内 asyncio 30s 扫描  (server.py:734)
┌──────────────────────────────────────────────────────────────────────────────┐
│ snooze_promote_loop                                                           │
│                                                                              │
│ ① SELECT notify_threads WHERE snoozed_until <= now                        ❌    │
│ ② 幂等: 同 thread 已有 queued/awaiting → skip                             ❌    │
│ ③ 取 last_item_id → 拼 summary 前缀 [续报]                                  ⚠    │
│ ④ INSERT notify_items (state='queued', kind='resume', requires_feedback=1)  ⚠    │
│ ⑤ UPDATE notify_threads SET item_count+=1, last_item_id=?, snoozed_until=NULL ❌ │
│ ⑥ promote_notify()                                                       ❌    │
│ ⑦ ws_broadcast_snapshot()                                                 ✅    │
│                                                                              │
│ 可见性: 用户能看到 "上次你说稍后" 灰色引用条（PlayService withQuoteLine），                   │
│         但 ❌ 看不到 "30s 后才到期" / "为什么没到期" / "30s 内如果重连会不会漏触发"               │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 4.1 链路可见性矩阵

| 步骤 | 服务器侧行为 | 用户可见？ | 可见形态 |
|---|---|---|---|
| 外部源推送 | daemon 拉取 | ❌ | – |
| HMAC 验签 | verify_sig | ❌ | – |
| 事件入库 events | INSERT | ❌ | `/v2/events` 端点存在但零调用 |
| 4 级 thread_key 派生 | resolve_thread_key | ❌ | – |
| UPSERT notify_threads | item_count+1 | ⚠️ 半可见 | NotifyListFragment 显示 item_count |
| INSERT notify_items | state=queued/logged | ⚠️ 半可见 | queue 显示 summary / logged 不显示 |
| promote_notify 单活动锁 | state→awaiting_feedback | ⚠️ 半可见 | 卡片 B 显示 active |
| 转发 hermes | forward_to_hermes | ❌ | events.upstream_status 存了但 App 不读 |
| WS 推送快照 | ws_broadcast_snapshot | ✅ | PlayService.handleSnapshot |
| 通知渲染 L0/L1/L2 | postItemNotif | ✅ | MessagingStyle + 三档 channel |
| TTS 播报 | speak + play | ✅ | MiniMax TTS mp3 |
| 用户按按钮 | FeedbackReceiver | ✅ | 通知消失 + 卡片 B 切换 |
| 决议回写 notify_items | UPDATE state=resolved | ⚠️ 半可见 | ThreadDetailActivity 时间线 |
| snooze 续报 | snooze_promote_loop 30s 扫 | ⚠️ 半可见 | "上次你说稍后" 灰条 |
| 状态块注入 | state_block → pre_llm hook | ❌ | `/v2/state-block` 端点存在但 Android 0 调用、Web 1 行 hint |

---

## 5. 差距分析（对照用户原话）

| # | 用户原话 | 现状 | 差距 | 优先级 |
|---|---|---|---|---|
| G1 | "可以看到所有信息"（所有 notify_items） | NotifyListFragment 只看 awaiting_feedback + queue；TodayFragment 复用 threads 当 digest；logged/resolved 不可见 | **`logged` 状态项无入口**——enqueue_notify:451 `state = "queued" if req_fb else "logged"`，requires_feedback=0 的项永久沉底。`/v2/notify/threads` 是线程头视角，**看不到任何"已办/已忽略"的历史清单 Tab** | **P0** |
| G2 | "可以看到所有信息"（事件流） | `events` 表完整存了原始 payload + upstream_status，`/v2/events?since=&limit=` 端点存在 | **零调用**——Android 0 处调，Hermes TodayPage / NotifyPage 都没调 | **P0** |
| G3 | "agent 流动操作"（通道推 → events 入库 → enqueue → promote → WS → App → 反馈） | 通道列表显示 channel 元数据；jobs 列表显示 agent 任务 | **链路全程不可见**——没有"事件时间线"页，没有"哪条 events 走到 notify_items 哪条走到 logged"的因果链视图 | **P1** |
| G4 | "agent 流动操作"（promote 决策） | notify_items.state 转换 | **决策依据 = 黑盒**——promote_notify 只看 queued+requires_feedback=1 FIFO，**用户不知道为什么"这一条"被晋升** | **P1** |
| G5 | "agent 流动操作"（thread 派生） | notify_threads 已实现 4 级派生 | **`thread_key` 字符串从未渲染**——用户只看到 title + item_count，看不到"为什么这 3 条微信消息归到同一个议题" | **P2** |
| G6 | "专门发给自己需要自己看的"（requires_feedback=1 醒目） | PlayService handleSnapshot：active 走 L2 抬头通知 + TTS + 卡片 B | **没有"主人专属 vs 共享上下文"二级分级**——所有 awaiting_feedback 一视同仁；channel_id 只在通知 footer 显示小字 | **P1** |
| G7 | "专门发给自己需要自己看的"（@你/问你/待决策） | priority='urgent' → L2 抬头；priority='normal' → L1 静默 | **priority 字段只有 urgent/normal 两档**，没有"@你" / "待决策" / "可批处理" 的更细分级 | **P2** |
| G8 | "决策依据透明" | – | **没有 reasoning log**——requires_feedback=1 是核心 payload 自带的，但 App 不展示核心给的"为什么" | **P2** |
| G9 | "通道健康 / 推送延迟" | ChannelsFragment 显示 name+archetype+uplink+created_at；server.py:357 `/v1/channels/{id}` 返回 event_count + last_event_at | **App 完全没调 `/v1/channels/{id}`**——看不到 last_event_at / event_count / host / upstream_status | **P1** |
| G10 | "设备活动" | server.py:867 bridge_heartbeat 端点存在 | **完全不可见**——`/v2/bridge/status` 0 调用，SettingsFragment 没列 | **P2** |
| G11 | "用户模型 (lists) 内容" | state_block 拼了 user_state / running_items / schedule 全文注入核心 | **`/v2/lists` 端点存在但 App 0 调用**——用户看不到"AI 觉得我是什么样的人" | **P2** |
| G12 | "今日 digest 卡片 A 真实现" | TodayFragment 注释"复用 /v2/notify/threads（M2 接 /v2/digest/today）" | **`/v2/digest/today` 服务端不存在**——M2 承诺未兑现 | **P1** |

### 优先级汇总

- **P0（核心阻塞，必修）**：G1（logged/resolved 可见）、G2（events 流可见）
- **P1（核心智能体现，需修）**：G3（链路透明）、G4（promote 决策透明）、G6（主人专属 vs 共享分级）、G9（通道健康）、G12（真 digest）
- **P2（体验增强，可后置）**：G5（thread 派生过程可见）、G7（更细优先级）、G8（reasoning log）、G10（设备活动）、G11（用户模型内容）

---

## 6. 建议方案（实施成本 + 用户收益）

> **本调研不实施任何方案**，仅给出方向 + 估算。决策方为用户/架构师。

### 6.1 P0 建议

#### 建议 S1：notify_items 全 state 可见（修复 G1）

**方向**：在 TodayFragment 卡片 A 增加"全部事项"二级抽屉（或下拉筛选）—— 4 个 Tab：
- 待决（state IN awaiting_feedback）
- 排队（state=queued）
- 已办/已忽略（state=resolved WHERE resolution IN actioned/dismissed）
- 稍后（state=resolved WHERE resolution=snooze AND snoozed_until > now）

**服务端**：复用 `/v2/notify/threads` + 新增 `GET /v2/notify/items?state=&resolution=&since=&limit=`（≤500 条，新端点）。

**用户收益**：用户可回溯"今天我做了什么决策 / agent 帮我处理了什么"。

**实施成本**：服务端 ~0.5 天（新增 1 个端点 + 1 个 SQL 索引 `idx_items_state_resolved`）；Android ~1 天（TodayFragment 加 RecyclerView 切换 4 个 Tab）；Hermes TodayPage 同步 ~0.5 天。

#### 建议 S2：events 流可见（修复 G2）

**方向**：新增"事件流"页（Drawer 第二项"通道事件"），按 channel_id + time 倒序，每行展示：
- seq / received_at / channel_name
- payload summary（取 level + summary 字段）
- upstream_status（如 `200:OK` / `502:hermes_unreachable: ...`）

**服务端**：复用 `GET /v2/events?since=&limit=`（server.py:784 已存在）；新增 `?channel_id=` 过滤参数。

**用户收益**：透明化"通道→ events→ notify_items"链路第一环；通道异常一眼看到。

**实施成本**：服务端 ~0.2 天（仅加 `?channel_id=` 过滤 + 索引 `idx_events_channel_received`）；Android ~1 天（新增 fragment_events.xml + EventsFragment）；Hermes ~0.5 天。

### 6.2 P1 建议

#### 建议 S3：链路全景页（修复 G3 + G4）

**方向**：在 ThreadDetailActivity 时间线底部加"链路脚注"——展示该 thread 所有关联 events.seq + upstream_status；每条 notify_item 显示 promote 时间点 + 触发 promote 的 events.seq（需新增字段 `notify_items.promoted_from_event_seq`）。

**用户收益**：用户能看到"这条决策的原始事件 + 链路完整性"。

**实施成本**：服务端 ~1 天（schema 加 promoted_from_event_seq 列 + INSERT/UPDATE 时记录）；Android ~1 天。

#### 建议 S4：主人专属分级（修复 G6）

**方向**：PlayService 通知标题加前缀 `[需你决策]` 仅当 requires_feedback=1 且 priority=urgent；通知 footer 增加 channel_id + "为何要你拍板（reasoning）"折叠面板（核心可在 notify_items.reasoning 字段写一句话）。

**服务端**：enqueue_notify 加 `INSERT notify_items(... reasoning TEXT DEFAULT NULL)`；state_block 也注入 reasoning 摘要。

**用户收益**：一眼区分"专属给我" vs "顺便通知"。

**实施成本**：服务端 ~0.3 天；Android ~0.5 天（PlayService 渲染前缀）。

#### 建议 S5：通道健康卡片（修复 G9）

**方向**：ChannelsFragment 卡片右下角显示：
- last_event_at（"5 分钟前" / "3 天前 ⚠️"）
- event_count_24h
- 上游状态（绿色 ✓ / 黄色 ⚠ / 红色 ✗）

**服务端**：复用 `/v1/channels/{id}`（server.py:356 已存在）；前端聚合请求 `/v1/channels` + 每个通道 stats。

**用户收益**：通道异常秒发现。

**实施成本**：服务端 0（已实现）；Android ~1 天；Hermes ~0.5 天。

#### 建议 S6：真 digest 卡片 A（修复 G12）

**方向**：实现 `/v2/digest/today` —— 服务端聚合 `events` 近 24h + `notify_items` 近 24h + `lists.user_state` + `lists.running_items`，按议题归类，≤10 条；每条 ≥2 行（一行事实 + 一行判断）。

**用户收益**：今日页第一屏就是"agent 视角的一天"，不依赖打开 App 立即可见。

**实施成本**：服务端 ~2 天（聚合 cron + 端点）；Android 改 1 个调用；Hermes 同步。

### 6.3 P2 建议

#### 建议 S7：thread_key 派生过程透明（修复 G5）

**方向**：ThreadDetailActivity 顶部显示 `thread_key` 字符串 + 派生级别徽章（① payload.thread_key 显式 / ② pointer 命名空间 / ③ session_discriminator / ④ 兜底 ch:{id}）。

**用户收益**：透明化"为什么这 3 条微信消息归到一起"。

**实施成本**：服务端 0（已存 thread_key）；Android ~0.3 天。

#### 建议 S8：用户模型可见页（修复 G11）

**方向**：Drawer 新增"AI 怎么看你"——读 `/v2/lists?name=user_model`，渲染所有 key + value + last_validated_at。

**用户收益**：用户可感知"agent 学到了什么" / 主动纠正。

**实施成本**：服务端 0（已存在）；Android ~1 天。

#### 建议 S9：bridge_heartbeat 设备列表（修复 G10）

**方向**：SettingsFragment 加"设备"二级页 —— 列出所有 `devices` + 每个的 `bridge_heartbeat.last_seen`（online/stale）+ `name`。

**用户收益**：透明"PC 端 daemon / 微信 daemon / google-bridge 是否在线"。

**实施成本**：服务端 ~0.3 天（list all devices 端点 + bridge_heartbeat 联表）；Android ~1 天。

#### 建议 S10：reasoning log（修复 G8）

**方向**：在 ThreadDetailActivity 加"agent 视角"折叠面板 —— 显示 requires_feedback=1 的核心 reasoning（payload.reasoning）+ thread 历史中所有 kind=resume / kind=system 的来源。

**用户收益**：决策依据透明。

**实施成本**：服务端 ~0.5 天（payload 新增 reasoning 字段透传）；Android ~0.5 天。

---

## 7. 用户原话 vs 落地落点速查表

| 用户原话 | 落点（数据/页面/端点） |
|---|---|
| "看到所有信息" | notify_items 全 state Tab（S1） + events 流（S2） + lists 透视（S8） |
| "看到 agent 流动操作" | 链路全景页（S3） + thread_key 派生透明（S7） + bridge_heartbeat 设备列表（S9） |
| "专门发给自己需要自己看的" | 通知前缀 + reasoning（S4） + 优先级细粒度 + 通道健康（S5） |
| "决策依据透明" | reasoning log（S10） + promote 决策记录（S3） |

---

## 8. 附：与已有设计文档的对应

- **docs/10** 通知线程延续：thread 模型已落地 ✓；thread_ctx 已在 App 可见 ✓；kind='resume' 续报 ✓
- **docs/12** 主面板重构：今日页 3 卡片 A/B/C 静态版已落地（B 真、C 部分），**A 仍用 threads 占位**（M2 未做） ❌
- **docs/13** 综合方案：P1/P2/P3 均未注入（proactive schema.sql 部署未生效）
- **docs/11** 核心 agent 智能化：`feedback_events` / `thread_snapshots` 表不存在；user_model 命名空间不可见
- **docs/15** 主动外伸层：`devices` / `device_capabilities` / `remote_services` 三表不存在（当前 devices 表仅作 token 存储）

---

## 9. 报告交付检查

| Step | PASS |
|---|---|
| Step 1 读现状（5 Kotlin Fragment + React TodayPage + server.py 全文 + PlayService + Api.kt + ThreadDetailActivity + docs/10/11/12/13/15） | ✅ |
| Step 2 列出当前可见 vs 不可见（端点-视图矩阵 + WS 推送字段 + DB 表对照 + agent 推理层 + 设备/网络/通道健康度） | ✅ |
| Step 3 列出 agent 流动操作链路图（5 段 ASCII 图 + 15 步可见性矩阵） | ✅ |
| Step 4 差距分析（12 项 G1-G12 + P0/P1/P2 优先级 + 对照表） | ✅ |
| Step 5 输出调研报告 docs/18-app信息可见性调研.md | ✅ |
| Step 6 不修改任何 Kotlin / Python / TypeScript / SQL 文件 | ✅ |

---

## 10. 关键发现 3 条

1. **`/v2/digest/today` 端点不存在**：TodayFragment.kt:36 注释"卡片 A: 复用 /v2/notify/threads（M2 接 /v2/digest/today）"——M2 承诺的真 digest 端点 **服务端没实现**，当前 Android 用 threads 列表硬撑（200 条限制 + 仅显示 thread_title + item_count + last_resolution + snoozed_until）；Hermes TodayPage **根本不调 `/v2/notify/threads`**，控制台今日页完全没有 thread inbox。

2. **`/v2/events` + `/v2/lists` + `/v2/bridge/status` + `/v2/commands/*` 4 类端点零调用**：服务端暴露了原始事件流、清单、设备心跳、下行命令 4 类透明化数据通道，但 Android + Hermes 双端 **没有任何 Fragment / Page 调过**——这是"agent 流动操作可见"的最大缺口；用户能感知到的流动只有 WS 推送的 `awaiting_feedback + queue` 两类对象，链路全黑。

3. **PlayService 是 App 唯一透明化的实时管道**：`/v2/notify/stream` WS 推送的快照 **只携带 active + queue**，不携带 thread 全量、不携带 events、不携带 bridge_heartbeat、不携带 lists；notify_snapshot() 设计上 **只服务"单活动锁决策流"**——这与 docs/12 §2.5 "App 必须自己做的：通知已读/代际标记"已落地的部分形成对照，但 **与 docs/12 §2 "AI 找 App" 的卡片 A 真 digest 目标距离还差至少 1 个聚合端点 + 1 个推送通道**。
