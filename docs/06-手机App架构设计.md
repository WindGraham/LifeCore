# 手机 App 架构设计（Android 优先）v0.1

> 日期：2026-09-16 · 角色：App 架构师 · 状态：待评审
> 硬约束：**App 只对接外围服务层（registrar / inbox / notify / pairing-DR / list_manager 的 versioned BFF API），绝不直连 Hermes 内部**。Hermes 对 App 不可见——App 不知道 webhook route、不知道 MCP server、不知道 state.db。这是"App 迭代不动核心"和"核心将来可整体替换为 vendor harness（docs/05:63）"的根本保证：替换核心时，BFF 后面的实现换了，`/v2/*` 一个字节不变，App 零改动。

## 0. 总体形态

```
┌─────────────── Android App（原生 Kotlin + Compose）───────────────┐
│ 配对/设备管理 │ 播报卡片(单活动锁) │ 队列视图 │ 收件箱 │ 语音(唤醒/ASR/TTS播放) │
│ 保活三通道：厂商推送(唤醒) + 前台服务WSS(主数据面) + ntfy(兜底)        │
└──────────────┬───────────────────────────────────────────────────┘
               │  仅 HTTPS / WSS，Bearer 设备凭证 + TLS pinning + 指纹校验
┌──────────────▼─────────── VPS 外围服务层（versioned BFF）───────────┐
│ pairing-DR │ notify(队列+音频预渲染) │ inbox(since同步) │ registrar    │
│ list_manager+notify 队列（docs/02 §14）                                │
└──────┬──────────────────────────────────────────────────────────────┘
       │ webhook 上行 / MCP 下行 / api_server / state.db 只读 / hooks
┌──────▼──────────┐   ← App 与 Hermes 之间隔着整个 BFF，方向单一、面收敛
│ Hermes 核心      │
└─────────────────┘
```

BFF 收敛原则：App 的每个端点都映射到**恰好一个外围服务**；外围服务内部怎么调 Hermes 是它们的自由（registrar 调 CLI、notify 读 list_manager、inbox 读 webhook 事件），App 不可见也不可见。

---

## 1. 配对设计（回答问题 1）

### 1.1 服务器密钥对与指纹

- 服务器一对 **Ed25519** 密钥，由 **pairing-DR sidecar** 持有（不是 Hermes；Hermes 对配对无感）。私钥落盘权限 600；指纹 = `SHA-256(SPKI-DER)` 的 base32 短串（24 字符，人工可比对），记 `fp`。
- 指纹的两个用途：① App 首次配对后**钉住**（pin），之后每次建连校验服务器身份；② 换机/灾难恢复时做**带外人工比对**（docs/02 §5：`url`+`fp` 长期有效，扫码动作 = 物理在场的根信任验证）。

### 1.2 QR 码内容（与 docs/02 §5 一致，不发明新格式）

```json
{"v":1,"url":"https://agent.example.com","fp":"K7Q2…","code":"X7KQ2M"}
```

- `url` + `fp`：长期有效，**QR 本身无秘密**（可打印贴墙、可放 `/.well-known/qr.png`）。
- `code`：一次性、10 分钟有效、绑定单个配对会话，由 pairing-DR 签发。

### 1.3 首次配对流程（App 端时序）

```
1. App 扫 QR → 解析 {url, fp, code}
2. GET {url}/.well-known/agent.md        # 读契约，得端点清单与版本（公理三）
3. App 生成 Ed25519 设备密钥对（私钥进 Android Keystore，永不导出）
4. POST {url}/v1/pair                    # docs/02 §3 既有端点
   { code, device_name, device_pubkey, platform:"android" }
5. pairing-DR 验证 code（一次性）→ 签发长期设备凭证，用服务器私钥签名响应：
   { device_id, access_token, server_pubkey, server_fp, api:{v2_base:"/v2"},
     token_expires:"长期，可轮换", capabilities:{...} }
6. App 校验 server_fp == QR 里的 fp（不等 = 立即中止并报警，安全事件，绝不重试自动恢复）
   校验签名 → 钉指纹 → Keystore 存设备私钥，EncryptedSharedPreferences 存 access_token
7. 配对完成，进入常规重连状态机（docs/02 §7：401=安全事件停机报警，其余指数退避）
```

### 1.4 长期凭证存储与请求认证

- **设备私钥**：Android Keystore（硬件-backed 优先），仅用于签名，不可导出——换机即等于新设备。
- **access_token**：Keystore 加密的 SharedPreferences 存储；MVP 认证 = `Authorization: Bearer <access_token>` + TLS pinning + 每次建连比对服务器指纹。
- **v2 强化（推荐同步实现，成本极低）**：每请求附加 `X-Device-Signature: <ts>.<ed25519_sig("<ts>.<method>.<path>.<sha256(body)>")>`，VPS 按 device_pubkey 验——token 被窃也无法重放/冒用（token 证明"配对过"，签名证明"这台设备在场"）。401 仍表示凭证吊销（安全事件，docs/02 §7 行为）。

### 1.5 加设备

方向反转（docs/02 §5 流程 2）：已配对手机当**签发台**。

1. App「设置→设备→添加设备」生成 10 分钟注册令牌，显示为 QR（内容 = `{"v":1,"url":..., "reg_token":"<10min>"}`，复用 agent.md §2.1 的注册令牌机制，一物两用：daemon 用它换通道凭证，新设备用它换设备凭证）。
2. 新设备（手机/电脑/daemon）出示 QR 换凭证。
3. **强制人工环节**：pairing-DR 把「新设备 <name> 请求接入」作为 `requires_feedback` 条目注入 notify 队列，**任一已配对设备上用户点"允许"才签发**；10 分钟无人处理自动拒绝。理由：设备凭证 = 系统全部读权限的钥匙，签发必须有物理在场的人按一次（docs/02 §10：凭证获取的物理下限不可外包）。

### 1.6 换机 / 灾难恢复（手动确认环节，绝不全自动）

照 docs/02 §8 剧本，App 侧加防钓鱼表述（回应 §11 开放问题"待恢复模式的指纹确认 UX"）：

1. App 连上服务器，发现处于**待恢复模式**（`/.well-known/agent.md` 响应带 `{"mode":"restore"}`，或 `POST /v1/pair` 返回 `server_state:"empty"`）→ 全屏阻断页，禁止后台自动继续。
2. 页面显示：旧指纹 → 新指纹（**双指纹并列**，要求用户比对 QR/纸质备份上的旧指纹）；文案固定三行："服务器身份已变更。如果你刚换了机器/重装了系统，这是正常的；如果不是，立即取消——你的流量可能正在被劫持。"（不引用服务器返回的任何动态文本进确认页，防服务器端注入话术。）
3. **用户手动按一次"确认恢复"** → App 调 `POST /v1/restore` 上传加密备份（密钥在用户口令/手机安全区，服务器解不开，docs/02 §8）→ 旧凭证全体复活，App 按常规重连循环无感对接。
4. 指纹不符且无恢复意图 = 中间人告警，停机（公理二：安全事件绝不自动"恢复"，docs/02 §32-38）。

### 1.7 设备吊销

「设置→设备」列表（`GET /v2/devices`）显示全部已配对设备；任一台可吊销其他设备（`DELETE /v2/devices/{id}` → pairing-DR 杀 token）。被吊销设备的下一次请求得 401 → 走"凭证失效需重新配对"安全事件路径。

---

## 2. 语音管线决策（回答问题 2）

**明确结论：混合架构——唤醒词与短指令本地、流式 ASR 本地为主、TTS 在 VPS 端生成并预渲染下发（MP3/Opus）、断网降级到 Android 系统 TTS。**

| 环节 | 位置 | 选型 | 理由 |
|---|---|---|---|
| 唤醒词 / VAD | **App 本地** | sherpa-onnx KWS（关键词级），纯离线 | 唤醒必须零网络依赖；模型 <5MB；前台服务内常驻可行 |
| 短指令识别（"是/否/稍后提醒"） | **App 本地** | 关键词 spotting，不走完整 ASR | 决策卡片只有 2-3 个选项，KWS 足够，延迟 <200ms，离线可用 |
| 完整 ASR（长语音输入、自由文本） | **App 本地为主** | sherpa-onnx Paraformer 流式（中文专项优化） | 中文效果与云端差距已很小；隐私：语音原文不出手机（与三级上行协议精神一致，C 级默认不出地方）；零成本无限用 |
| ASR 兜底 | VPS 端 | 音频流/文件 POST 给 BFF，外围层调云端 ASR | 本地模型识别失败 ≥2 次、或用户显式选择时启用 |
| TTS（播报） | **VPS 端生成** | 云端 TTS API（VPS 上调），**入队时即预渲染**成 Opus 挂到 notify 条目 | 见下 |
| TTS（离线兜底） | App 本地 | Android TextToSpeech | 断网/服务器挂时仍能播（文字通知降级） |

**TTS 为什么放 VPS 而不是 App：**

1. **延迟其实更低**：notify 队列是串行的（docs/02 §14.2 单工约束），一条汇报成为 active 之前已在队列里排队——VPS 有**数秒到数分钟的提前量**把音频渲染好，推送到达时音频已就绪，首字节延迟 ≈ 推送到达延迟（1-3s），与本地 TTS 相当甚至更好。"边到边实时 TTS"才有延迟问题，我们是"预渲染+推送"，天然避开。
2. **成本**：VPS 上一次渲染多处复用（多设备、重复播报、音频当通知音）；App 端高质量中文 TTS 模型体积与内存代价不值得为单人系统付。
3. **音质与一致性**：云端中文 TTS 质量显著优于端侧小模型；跨设备声音统一（手机/将来的音箱同一个"系统声音"）。
4. **隐私对齐**：播报文本本来就是 B 级摘要（已离开地方、VPS 可裁决可见，docs/02 §13.2），给 TTS 服务不新增隐私面；而用户**语音输入**的原文（C 级潜质）则坚持本地 ASR 不出手机。两个方向不对称是刻意的：下行文本已脱敏，上行语音最敏感。

**成本与隐私小结**：ASR 本地 = 隐私 + 零边际成本 + 离线可用；TTS 云端 = 音质 + 预渲染零延迟 + 复用。混合各自取长。

---

## 3. 杀后台与播报三层保障（回答问题 3）

### 3.1 三通道保活（国内 Android 现实）

| 通道 | 机制 | 角色 |
|---|---|---|
| **通道① 厂商推送** | 集成 Mi/Huawei/OPPO/vivo/荣耀推送 SDK（或经聚合 SDK 一个入口多通道）；FCM 作为有 GMS 设备上的并行通道 | **唤醒器**：进程死了也能拉起。Payload 极简：`{wake:1, notify_id, tier:"normal|alert"}`——**不带内容**（隐私：推送内容会留在厂商服务器；唤醒后 App 用凭证拉真实数据） |
| **通道② 自建前台服务 + WSS** | 常驻前台服务（持久通知）维持 `WSS /v2/stream`，即主数据面 | **主力通道**：存活期间一切实时事件走这里，厂商推送只当心跳备份 |
| **通道③ ntfy 兜底** | 订阅高熵 topic（docs/02 §6.4：ntfy 高熵 topic = 高熵 URL 安全模型）；可自托管 ntfy | **兜底唤醒**：厂商推送注册失败/老设备/推送延迟过大时的第二唤醒器；同时是**第三层播报**（预渲染音频挂 ntfy attachment 当通知音） |

保活策略要点：前台服务是数据面主力，但**设计上假设它随时会死**——所有状态都在服务端（notify 队列、since 游标），进程死后重启只是"重连 + 补同步"，无本地状态可丢（docs/02 §7 重连状态机照搬）。

### 3.2 播报三层保障

**第一层 — 存活直连（秒级）**：前台服务活着 → WSS `notify.active_changed` 事件 → 立刻拉音频播放。延迟 <1s。

**第二层 — 推送唤醒 20 秒窗口（3-8s）**：进程已死 → 厂商推送/ntfy 到达 → `BroadcastReceiver`（厂商推送）→ 触发 `WorkManager` 加急任务 + `startForegroundService`：

- Android 10+ 后台启动限制：先弹一条"正在播报"高优先级通知获得可见性，再 `startForeground` 起服务播放；alert 级用推送附带的 **full-screen intent 通知**（`USE_FULL_SCREEN_INTENT` 豁免路径）直接拉全屏播报。
- 窗口语义：**从唤醒起 20 秒内**完成"拉音频 → 播放"，无论用户是否打开 UI；超时则降级第三层。
- 20 秒的选取：覆盖国内厂商推送到达延迟抖动（P99 ~5-10s）+ 音频拉取（预渲染音频 <200KB，1s 内）+ 播放，留足余量又不至于被系统判滥用电量。

**第三层 — 预渲染音频当通知音（不依赖任何存活）**：VPS 在 notify 条目激活时除了走 WSS/推送唤醒，**同时**把预渲染音频作为 attachment 经 ntfy 推送（高熵 topic，audio/opus，<200KB）。进程死透、第二层也失败时：ntfy 通知自带音频以**通知铃声**形式播放——这条音频是"带声音的通知"，任何保活方案都杀不掉系统通知音。代价是音色走通知通道（音量受通知流控制），只用于兜底。

三层共用同一音频文件（VPS 预渲染一次），只是投递路径不同：**同一内容，三条命**。

### 3.3 降级顺序与 UX

存活直连 →（死）推送唤醒播放 →（再死）通知音 →（全灭）静默卡片入 App 打开时补播。App 卡片上标注本次播报实际走了哪层（调试用，普通用户无感）。

---

## 4. MVP 功能与扩展路径（回答问题 4）

### 4.1 MVP 范围（第一个可用版本）

1. **配对**：扫码配对、指纹钉扎、设备列表/吊销（§1 全套，这是地基）。
2. **音频播报**：WSS 实时 + 三通道保活 + 三层播报保障（§3 全套）。
3. **待决策卡片（单活动锁 UX）**：
   - 全屏/悬浮卡片只显示**当前 active** 一条：`awaiting_feedback` 时显示 `feedback_options`（默认"是/否/稍后提醒"，来源 edge-agent.md §3 条目级声明）；
   - **队列视图**：其余条目 FIFO 排队展示（位置、等待时长、是否将被 alert 插队打断）；
   - 回复入口：点按三键 **或** 唤醒词 + 本地 KWS 语音（"是/否/稍后"）；
   - 单活动锁在 UX 上的表达：任何时刻只有一张卡可回；新条目到达 = 顶部横幅"队列 +N"，不打断当前卡（alert 级除外——打断时明确提示"被打断，已回队列头部"，docs/02 §14.2 规则 2）；
   - 超时安静：卡片超时自动转"已记录"灰条，不催（规则 3）；想催用户在队列视图里手动"催一下"（对应"是否改催由用户在 App 调"）。
4. **稍后提醒闭环**：选"稍后提醒"→ `resolved(snoozed)` + agent 调提醒程序 MCP 改期 → 到点新条目入队（docs/02 §14.2 规则 4）→ App 收到新 active 卡片。App 只需正确渲染状态机流转，闭环逻辑全在 VPS 侧。

明确**不做**进 MVP：收件箱浏览、通道管理、会话视图、C 级原文回查、列表编辑。

### 4.2 扩展到网关级能力的路径

BFF 按版本演进，App 按 Tab 渐进解锁，全部复用同一设备凭证：

```
MVP（播报+决策卡）
 └─ Tab2 收件箱：GET /v2/inbox 流式浏览 B 级摘要（since 游标增量同步）
     └─ 原文回查：GET /v2/inbox/{id}/original（BFF 代理核心 MCP client 调地方 get_original，C 级按需，pointer 经 BFF 转发）
 └─ Tab3 网关状态：GET /v2/channels（registrar 注册表投影 = 接入服务清单，docs/05 场景 8）
     └─ 通道管理：POST/PUT/DELETE /v2/channels（report_policy 编辑、吊销）
 └─ Tab4 会话：GET /v2/sessions + POST /v2/messages（与核心 agent 直接对话，docs/05 前导）
     └─ 列表：GET/POST /v2/lists（list_manager，场景 8）
 └─ 状态面板：GET /v2/state/...（state.db 只读投影）
```

注意扩展路径上 App 仍然不碰 Hermes：channels 管理走 registrar（registrar 内部调 `hermes webhook subscribe` CLI，docs/04 §15）；sessions/messages 走 BFF 对 api_server 的封装；state 走只读投影服务。核心换成 vendor harness 那天，BFF 端点签名不变。

---

## 5. App-VPS 接口：分层、认证、版本化、端点表（回答问题 5 + notify 队列 API）

### 5.1 分层

- **数据面**（高频、可增量、可重放）：`GET /v2/notify/*`、`GET /v2/inbox`、`WSS /v2/stream`。全部 `since`/游标语义，断线重同步幂等安全（docs/02 §7）。
- **控制面**（低频、写操作）：配对、设备管理、反馈提交、通道/列表/会话管理。

### 5.2 认证

`Authorization: Bearer <access_token>`（/v1/pair 签发）+ `X-Device-ID`；推荐叠加 Ed25519 每请求签名（§1.4 v2 强化）。TLS + 服务器指纹钉扎在连接层。401 → 安全事件停机报警（不重试！docs/02 §7 与 contracts/agent.md §4 一致）；429 → 指数退避。

### 5.3 版本化与共存

- 既有 `/v1/*`（docs/02 §3：`/v1/pair`、`/v1/restore`、`/v1/channels`、`/hk/*`、`WSS /v1/channels/*`）是 **agent/daemon 面**，App 除 `/v1/pair`、`/v1/restore` 外**一律不调**。
- App 面全新端点全部落在 **`/v2/*`**，与 `/v1/*` 共存互不影响；"永不破坏"承诺分别适用于两个面。客户端启动先 `GET /.well-known/agent.md`，按声明的端点版本协商（响应带 `api:{v2_base, versions:["v2"]}`）。
- 破坏性变更走 `/v3/*` 并存期 ≥3 个月，App 通过 agent.md 版本声明自动迁移。

### 5.4 端点表

#### MVP 端点

| 方法 | 路径 | 层 | 用途 |
|---|---|---|---|
| GET | `/.well-known/agent.md` | 公开 | 契约发现（版本协商、能力清单） |
| POST | `/v1/pair` | 控制 | 配对码换长期设备凭证 + 服务器公钥（docs/02 §3 既有端点） |
| POST | `/v1/restore` | 控制 | 灾难恢复上传加密备份（仅待恢复模式） |
| GET | `/v2/notify/active` | 数据 | **待决策卡片**：当前 active/awaiting_feedback 条目（含 audio_url、feedback_options、队列深度、打断标记） |
| GET | `/v2/notify/queue?cursor=` | 数据 | **队列视图**：FIFO 全量（含位置、等待时长、状态机字段、是否为 alert） |
| GET | `/v2/notify/items/{id}` | 数据 | 卡片详情（B 级摘要全文、pointer、channel 名、suggested_priority） |
| GET | `/v2/notify/items/{id}/audio` | 数据 | 预渲染播报音频（Opus；渲染未完成返回 202 + retry_after） |
| POST | `/v2/notify/items/{id}/feedback` | 控制 | 回复：`{"choice":"yes|no|snooze","snooze_in":"15m"}` → resolved(actioned/dismissed/snoozed) |
| WSS | `/v2/stream?since=` | 数据 | 实时事件流：`notify.new` / `notify.active_changed` / `queue.changed` / `device.revoked`（401/指纹不符立即断并报安全事件） |
| GET | `/v2/devices` | 控制 | 已配对设备列表 |
| DELETE | `/v2/devices/{id}` | 控制 | 吊销设备 |

#### 扩展端点（v2 内渐进开放）

| 方法 | 路径 | 层 | 用途 |
|---|---|---|---|
| GET | `/v2/inbox?cursor=&limit=` | 数据 | B 级收件箱增量同步 |
| GET | `/v2/inbox/{id}/original` | 数据 | C 级原文回查代理（pointer → 核心 MCP → 地方 get_original，contracts/edge-agent.md §4） |
| GET | `/v2/channels` | 控制 | 注册表投影（接入服务清单） |
| POST | `/v2/channels` | 控制 | 注册新通道（BFF → registrar） |
| PUT | `/v2/channels/{id}/report_policy` | 控制 | 汇报策略调整（mode/triggers/semantics，agent.md §2.1a） |
| DELETE | `/v2/channels/{id}` | 控制 | 吊销通道 |
| GET | `/v2/sessions` / `POST /v2/sessions/{id}/messages` | 控制 | 网关级会话与消息（BFF 封装 api_server） |
| GET/PUT | `/v2/lists` `/v2/lists/{name}` | 控制 | list_manager 投影与读写（场景 8） |
| GET | `/v2/state/{domain}` | 数据 | state.db 只读投影（健康/服务状态面板） |
| POST | `/v2/devices/enroll` | 控制 | 出示注册令牌换设备凭证（加设备流程 §1.5，10 分钟有效） |

### 5.5 notify 队列读取 API 定义（App 第一批消费者）

数据模型直接映射 docs/02 §14.2 状态机：

```jsonc
// GET /v2/notify/active → 200
{ "item": {
    "id": "nt_9f2c", "state": "awaiting_feedback",   // queued|active|awaiting_feedback|resolved
    "summary": "课程群：数据库作业改到周五 23:59",      // B 级摘要（A 级通道则只有元数据）
    "pointer": "wx:8832",                            // C 级回查指针，App 原文回查用
    "channel": { "id": "ch_x9f2", "name": "wechat-monitor" },
    "archetype": "message", "priority": "normal", "is_alert": false,
    "requires_feedback": true,
    "feedback_options": ["是", "否", "稍后提醒"],       // 缺省 ["是","否","稍后提醒"]
    "audio": { "url": "/v2/notify/items/nt_9f2c/audio", "ready": true, "duration_s": 7 },
    "enqueued_at": "...", "active_since": "...", "expires_at": "...",  // 超时安静用
    "interrupted_from": null                         // alert 插队时记录被打断条目
  },
  "queue": { "depth": 3, "next_eta_s": 120 } }

// GET /v2/notify/queue?cursor= → 200
{ "items": [ /* 同上单据数组，FIFO 序 */ ],
  "next_cursor": "c_88a1", "has_more": false }

// POST /v2/notify/items/{id}/feedback → 200
{ "result": "resolved", "resolution": "snoozed",     // actioned|dismissed|snoozed|expired
  "queue": { "depth": 2 }, "next_active": { "id": "nt_a11b", ... } }  // 单活动锁：立即返回下一条
```

约束：队列视图只读单活动锁（至多一条非 resolved 处于 active/awaiting_feedback）；feedback 只允许打在**当前 active** 上，打已 resolved/非活动条目返回 409——UX 与协议同构，杜绝双设备并发回复竞态（多设备并发写配置的开放问题 docs/02 §11 此处先以"仅 active 可回"收口，卡片级冲突天然消失）。

---

## 6. 技术选型（回答问题 6）

**结论：原生 Android（Kotlin + Jetpack Compose）。**

| 方案 | 保活/推送 | 语音（sherpa-onnx JNI、音频焦点） | Keystore/指纹 | 结论 |
|---|---|---|---|---|
| 原生 Kotlin | 厂商推送 SDK 一等公民；前台服务/full-screen intent 直接 API | JNI 直调无桥接损耗；AudioFocus/AudioTrack 全控 | Keystore、StrongBox、biometric prompt 原生 | **选** |
| KMP | 推送/保活必须回落 platform 层，共享层只剩协议代码 | 语音基本写不进共享层 | 同左 | 共享收益 ≈ 网络+模型层，单人系统维护双 UI 不值 |
| Flutter | 厂商推送靠社区插件（维护滞后是常态）；前台服务需写原生插件 anyway | 音频/唤醒词绕 MethodChannel，延迟与包体积双输 | 加密存储插件质量参差 | 生态短板恰好全打在本项目硬需求上 |

补充决策：

- **模块化隔离换平台成本**：协议层（认证签名、since 同步、notify 状态机、配对流程）写成**无 Android 依赖的纯 Kotlin 模块**（ktor-client 多平台版）。将来上 iOS/KMP 或桌面端，这层原样复用——这是给"原生"决策买的保险，成本极低（协议层预估 ~2000 行）。
- sherpa-onnx（KWS + Paraformer + VAD）一个库覆盖 §2 全部端侧语音；Opus 解码用 libopus。
- minSdk 26，targetSdk 34；单模块起步，保活/语音/配对三个子系统先物理分包（`keepalive/` `voice/` `pairing/`），为将来拆分 KMP 预留边界。

---

## 7. 与既有设计的一致性核对

| 本设计 | 依据 |
|---|---|
| 配对三流程/QR 无秘密/手动确认恢复 | docs/02 §5、§8 |
| 三类故障三行为（401 停机、指纹不符报警） | docs/02 §7 状态机；contracts/agent.md §4 |
| notify 单活动锁/alert 插队/超时安静/snooze 闭环 | docs/02 §14.2 四条规则 |
| B 级摘要 + pointer、C 级经 BFF 回查 | docs/02 §13.2；contracts/edge-agent.md §4 |
| 接入服务清单 = registrar 注册表投影 | docs/05 场景 8 |
| App 零 Hermes 直连、外围面收敛 | docs/02 §12.2 任务 4 修正；docs/05 战略记录 |
| A 级通道只上元数据（卡片 summary 为 null） | contracts/edge-agent.md §3 |
