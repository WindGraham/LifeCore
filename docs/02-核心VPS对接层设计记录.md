# 核心 VPS 对接层设计记录

> 日期：2026-09-15（讨论定稿，待 POC）
> 性质：本文档记录"agent.md 自描述 + 通道注册"模式的完整设计，是《架构调研与设计文档》的增补篇。
> 一句话概括：**VPS 从"一台要别人学的机器"变成"一本会说话的说明书"——对全世界只暴露一个网址（规则文件）和一组终身不变的端点；任何 agent 读完规则即可替用户完成接入，二维码只做第一次认识，之后永远自动对接。**

---

## 1. 设计动机（要解决的真问题）

1. 用户有大量第三方个人任务，**每来一个新需求就产生一次不可消除的配置劳动**（凭证、格式理解、接入、永久维护负债）——单人系统无人可转嫁；
2. 解法不是消灭配置劳动，而是**更换劳动主体**：用户从"执行者"退为"审批者 + 凭证保管员"，agent 当代劳者；
3. 为了让"agent 代劳"对任意工具链成立，接入知识必须收敛成一个**固定 URL 上的规则文件**，而不是散落文档或 SDK；
4. VPS 必须做到重启/更新/换机后，手机 App 与散落在各处的桥接脚本、daemon **零人工自动对接回来**。

## 2. 设计公理（三条承重墙）

### 公理一：唯一永恒常量

整个系统只有一样东西不许丢：**`agent.md` 的 URL，绑定稳定域名（从第一天起，不写 IP）**。

所有客户端（手机、桥接、daemon）终身只持有三样：

| 持有物 | 来源 | 寿命 |
|---|---|---|
| 服务器端点（域名） | QR 码 / 配对 | 终身 |
| 自己的长期凭证 | 配对时签发 | 终身，可吊销 |
| 服务器身份指纹（公钥） | 配对时写入，带外验证 | 随服务器密钥对 |

其余一切——路由表、会话、`since` token、甚至服务器硬盘——全部设计为**可丢失、可重建**。

### 公理二：三类故障，三种行为（绝不混淆）

| 故障类别 | 例子 | 客户端行为 | 机制 |
|---|---|---|---|
| 瞬时失联 | 重启、更新、断网 | 静默指数退避重连 + `since` 补齐 | 重连状态机 |
| 灾难重建 | 换机、磁盘损毁 | 老凭证继续有效，无感 reconnect | 服务器可从加密备份整体复活 |
| 安全事件 | 凭证被拒、指纹不符 | **立即停止，大声报警，绝不自动"恢复"** | 吊销 + 人工确认 |

把"凭证被拒"做成自动重试 = 把入侵者当网络抖动欢迎。

### 公理三：零私有协议

线上跑的字节全是标准件（HTTP、HMAC、WebSocket、CloudEvents、MCP）；私有的只有**装配说明书**（agent.md），而说明书是给人和 agent 读的，不是协议发明。agent.md 必须自带《灾难恢复 runbook》：任何空服务器 + 这份文档 + 备份 = 完整复活。

## 3. VPS 对外端点（终身不变清单）

| 端点 | 鉴权 | 用途 |
|---|---|---|
| `GET /.well-known/agent.md` | 公开 | 规则文件本体（零秘密：只含说明书与公开格式定义） |
| `POST /v1/pair` | 配对码 | 一次性配对码换长期凭证 + 服务器公钥 |
| `POST /v1/restore` | 仅"待恢复模式" | 空状态服务器接收加密备份（恢复后关闭此模式） |
| `POST /v1/channels` | Bearer | **注册通道（核心）**：声明任务 + 格式 → 分配虚拟通道 |
| `GET /v1/channels/{id}` | Bearer | 通道状态、事件计数、最近错误（agent 排障工具） |
| `DELETE /v1/channels/{id}` | Bearer | 删除/吊销通道 |
| `POST /v1/mcp-servers` | Bearer | 注册出向 MCP server |
| `POST /hk/{channel_id}` | HMAC（每通道 secret） | 入向事件推进入口（push 模式数据面） |
| `WSS /v1/channels/{id}` | Bearer | session 模式数据面（出站 WS，双向） |

暴露面 = 一个公网端口。A 类（IM 长轮询/WS、IMAP、ntfy 订阅、云 API）全部出站连接，无需入向端口。

## 4. agent.md 规则文件（写法：命令优先，解释殿后）

结构：

```markdown
# My-Personal-Agent VPS 对接规则 v1
> 版本 1.3 · 本文件是唯一权威对接文档；/v1/* 端点永不破坏，增补只加不改。

## 0. 三条铁律（违反任一视为接入失败）
1. 入向事件必须 CloudEvents 1.0（schema 见附录，7 种原型选一）
2. 事件必须带 actions_available（只读源写 []）
3. 必须 HMAC 签名：X-Signature: t=<ts>,v1=<hmac(secret,"<ts>.<body>")>，±300s 防重放
## 1. 认证（token 获取：App 设置→开发者，一次性）
## 2. 入向接入：curl 样例 → 桥接脚本模板（40 行骨架）→ 验收步骤
## 3. 出向接入：MCP server 注册 curl 样例
## 4. 客户端重连状态机（伪代码）
## 5. 常见失败与自查表
## 6. 灾难恢复 runbook（空服务器复活步骤，agent 可执行）
## 附录：CloudEvents schema + 7 种原型字段表
```

agent 对可执行样例理解最好、对散文最差——所有步骤给可直接照抄的 curl/代码。

## 5. 配对协议（一个模式，三个入口）

QR 码内容：`{"v":1,"url":"https://agent.example.com","fp":"<服务器公钥指纹>","code":"X7KQ2M"}`

- `url` + `fp` 长期有效，**扫码动作 = 带外根信任验证**（物理在场）；
- `code` 一次性、10 分钟有效、绑定单个配对会话。

三个流程：

1. **首次配对**（服务器全新）：终端打印 QR（同时 `/.well-known/qr.png` 可访问；QR 本身无秘密）→ 手机扫 → 交 code → 领 `{长期凭证, 服务器公钥}` → 钉指纹，终身不再扫码；
2. **加设备**（手机已配对）：手机 App 显示 QR（内容 = VPS 签发的 10 分钟注册令牌）→ 目标设备跑安装命令 → 领凭证（即主文档 §8.4 daemon 入职，方向反过来由手机当签发台）；
3. **换机恢复**：手机扫新服务器 QR，App 识别"空服务器"→ 提示用户确认（**必须手动按一次**：指纹变更 = 服务器身份变更，绝不全自动）→ 上传加密备份 → 旧凭证全体复活。

## 6. 通道协议（/v1/channels：收敛点）

### 6.1 注册（"分配端口"的正确形态）

```
POST /v1/channels
{ "name": "course-deadlines",
  "direction": "source | sink | both",
  "archetype": "message|metric|file|task|calendar|alert|result",
  "format": { ... },          // 见 6.2
  "mode": "push | session" }
```

响应：

```json
{ "channel_id": "ch_a9f3c2",
  "ingest_url": "https://my-vps.tld/hk/ch_a9f3c2",
  "ws_url":     "wss://my-vps.tld/v1/channels/ch_a9f3c2",
  "secret":     "s3cr3t...",
  "test_event": "已注入合成事件，GET /v1/channels/ch_a9f3c2 查看" }
```

**一个任务一个高熵 URL；URL 即端口；吊销即失效**（不可猜测 = 第一道门，HMAC = 第二道）。不分配真实 TCP 端口（安全组不放行、TLS 无法按端口签发、端口有限、路径无限）。

### 6.2 格式声明（"我会用什么格式发送"的标准答案）

服务器边界只说 CloudEvents；客户端声明的是**映射**，三档强度：

| 客户端能力 | 声明 | 服务器行为 |
|---|---|---|
| 能自己组装 | 直接发 CloudEvents | 零翻译直通 |
| 结构化外来格式 | `mapping`（JSONPath/正则 per 字段） | 按映射翻译进原型 |
| 只会 POST 原始数据 | `"raw": true` + 一条样本 | 先存 `raw`，映射后补（主文档 §8.6 样本驱动）；原型可后改 |

映射存通道配置，改映射不改 URL，历史事件不重放。

### 6.3 两种联系形态（同一通道，可并存、可切换）

- **push**：注册即结束；服务 `POST /hk/{id}`（HMAC），无状态、可跑完即退。覆盖 ~90% 上报场景；
- **session**：服务 dial `ws_url`，**socket 本身即通道**：事件帧上行、动作请求下行（`direction: "both"` 时，核心 Agent 的动作沿 socket 推给 daemon）。即主文档 §8.4 daemon 模型的数据面。

### 6.4 收敛关系

| 之前的设计 | = 通道协议的 |
|---|---|
| `POST /v1/routes` | push 通道 |
| daemon 入职 + 适配器能力清单 | session 通道 |
| T2 映射 DSL | `format.mapping` |
| 主文档 §9.4 ntfy 高熵 topic | 高熵 URL 安全模型 |

## 7. 客户端重连状态机（所有散落实体共用，写进 agent.md）

```python
loop:
  try: 连接(端点, 凭证) → 同步(since) → since = 最新游标
  except 网络错误:       指数退避 2s→5s→10s→30s→60s 封顶，静默
  except 401/凭证被拒:   停机并通知用户"凭证失效，需重新配对"   # 安全事件，不重试
  except 服务器指纹不符:  停机并报警"服务器身份变了！若非换机警惕中间人"
  except /v1 404:        大版本变更 → 重拉 agent.md 按新规则协商
```

事件只追加 + ID 去重 ⇒ `since` 失效不是错误只是变慢，全量重同步幂等安全。**重连是协议，不是运维。**

## 8. 灾难重建（"全自动"的试金石）

原则：**VPS 是牛不是宠物**——全部状态 = SQLite + 配置，任何变更（新通道/新设备/吊销）触发增量导出**加密备份**（密钥由用户口令派生或存手机安全区，服务器自己解不开），自动同步 ≥2 副本：手机 App + 对象存储。

换机剧本：空服务器 → 待恢复模式（只服务 agent.md 和 /v1/restore）→ 手机连上认出空服务器 → **用户手动确认一次** → 上传备份 → 旧凭证全体复活 → 散落客户端按常规重连循环零感知对接。**全自动到"灾难恢复零确认"是过度设计**——那等于把根信任交给 DNS 劫持者；一次点按是该交的税。

## 9. 端到端时序（新需求的全自动闭环）

```
用户对 coding agent："写个服务盯课程系统 deadlines，规则看 agent.md"
  ├─ agent 读 agent.md → 用配对码换 token
  ├─ 写服务（~30 行轮询）→ POST /v1/channels（附格式声明）
  ├─ 拿 ingest_url+secret 写进服务配置，启动
  ├─ 发测试事件 → GET 通道状态自检
  └─ 回报："course-deadlines 已上线，测试事件在收件箱"
用户：0 次配置，只看审批。凭证获取与脚本批准是物理下限，不可外包。
```

## 10. 不可压缩的人肉成本（诚实边界）

1. 每对凭证/每次扫码：物理到场；
2. 看懂 agent 写的代码再批准：审核能力不可外包；
3. 围墙花园（无 API 的 App）：只读靠通知监听，动作无解——物理限制；
4. 什么值得接的判断：只有用户知道 ⇒ 控制接入总量，系统只接"高频 × 值得打断"的源。

## 11. 开放问题（POC 前待答）

- [ ] 通道注册表 schema（含映射 DSL 的存储格式与版本化）
- [ ] 加密备份的密钥派生方案（口令 vs 手机安全区 vs 双轨）
- [ ] session 模式的帧协议细节（与 Hermes Relay 契约的对齐程度）
- [ ] 待恢复模式的指纹确认 UX（App 端如何防钓鱼表述）
- [ ] 多设备并发改配置的冲突解决（最后写赢 + 向量时钟？）
- [ ] agent.md 的版本协商协议（客户端声明已读版本）

---

## 12. 实施任务清单（2026-09-16 讨论稿，待逐条修正）

> 状态：四条任务原文记录 + 修正意见见会话；修正后版本见 12.5。

### 12.1 原文
1. **重新设计 Hermes 的 agent 逻辑和 prompt**；先分析 Hermes 的 agent/session/memory/database 构建方式；
2. **VPS 上 Hermes 核心的保活与自服务增强**：公共注册表；地方程序注册用的开发 prompt（含 MCP 信息）；同步双向建立联系；
3. **agent 的 session 等机制为第 2 点适配**；
4. **开发手机 App**，并为 App 对 Hermes 核心做修改。

### 12.2 讨论后的修正（关键：消灭"修改核心"冲动）
- 任务 1 修正为"**分析 + 配置化定制**"：system prompt 用 personality/channel_overrides 配置层实现；turn loop/prompt 组装/压缩**不动**（prompt caching 是 Hermes 的 sacred invariant，动它 = 失去上游）；
- 任务 2 即自研三件套：agent.md（公开契约，含已注册 MCP server 清单）+ channels-registrar（HTTP 薄壳包 `hermes webhook subscribe` 热加载机制）+ pairing/DR sidecar；
- 任务 3 具体化：身份/会话判别/断点续传（since 游标）与通道注册表**统一设计**，在边缘（wrapper 层）实现，不改 Hermes session 内部机制；
- 任务 4 修正为：App 开发 + 优先用 **gateway hooks / 插件**扩展（hooks 支持 gateway:startup、session:start/end、agent:start/end 等生命周期事件，零核心修改）；核心补丁仅作最后手段且应尽量上游化。

### 12.3 依赖关系
任务 2 的 registrar 先行 → 任务 1 的分析为任务 3 供料 → 任务 4 与 2/3 并行。

### 12.4 任务 1 终版修正：动态上下文注入（2026-09-16）

需求成立："实时 prompt"（如当前并行任务清单）确为必需，但实现不是"修复 prompt 组装逻辑"，而是**动态上下文注入**，三条官方扩展点按优先级：

1. **工具优先**：易变状态（并行任务、实况）做成 MCP/服务门控工具（如 `list_running_tasks`），agent 按需调用，零缓存压力——符合 Hermes "narrow waist" 信条；
2. **瞬时注入兜底**：需要主动感知时，压缩成一行注到**当前回合上下文末尾**（参照官方 `message_timestamps` 先例：注在最新 user 消息上、不落历史），永不放 system prompt 前部（每回合变动会炸掉 prompt cache，成本成倍）；
3. **hooks 做数据源**：`agent:start/step/end` 钩子写实况文件，1/2 共读。

实现载体：`channel_overrides.system_prompt`（官方注明 ephemeral）、`plugins/context_engine`（每回合上下文注入插件接口，仅 93 行）、或 MCP server。**turn loop / prompt 组装 / 压缩机制仍不动。**

### 12.5 agent.md 增补一节

"## 现有能力清单（自动生成）"：公开 URL 上动态列出已注册 MCP server 与能力，供新接入 agent 引用而非重复造。

---

## 13. 场景压测修订（2026-09-16）

九场景压测全文见 [05-场景可行性判定.md](05-场景可行性判定.md)。此处记录对本文档的**修订**：

### 13.1 分级原则升级为四级（取代 §10.4 与 §6.2 的两级表述）

```
地方过滤器（规则/关键词/静音时段，零成本）
  → 地方摘要 agent（高容量源专属：vendor harness 常驻 + 记忆，做跨时间关联与初筛）
    → VPS 核心裁决（全局汇总、跨源对照、打扰判断）← "智能集中"重新表述为"裁决集中"
      → 动作（MCP tool / A2A 委派，approval 守门）
```

### 13.2 三级上行协议（所有敏感通道的默认配置规范）

| 级别 | 内容 | 何时上行 | 隐私面 |
|---|---|---|---|
| A 元数据 | 发送者/会话/时间/类型/条数 | 全量（便宜无内容） | 低 |
| B 摘要 | 一句话/标签（地方生成） | 过滤后 | 中；敏感群可只上 A |
| C 原文 | 完整内容 | **按需**（agent MCP tool 现场拉） | 高，默认不出地方 |

per-channel 配置 `uplink_level: A|AB|ABC`；默认 AB。原始全量永远留在地方；多设备检阅用加密副本（VPS 存密文不可读）。

### 13.3 微信场景定稿

root 解码 DB 在地方即全量存档。地方常驻摘要 agent（值守形态与记忆规范见 contracts/edge-agent.md）只报 B 级摘要+指针；核心经 MCP 回查 C 级；回复经 MCP tool + approval。敏感群可设永久 A 级。

### 13.4 新增外围件清单（对 §3/§12 的补充）

- **list_manager**（场景 8/9）：带时间戳的全局清单 MCP server，注册表投影自动生成"接入服务清单"；
- **地方值守 agent 规范**（contracts/edge-agent.md）：值守循环、MEMORY.md schema、上报协议、回查 MCP。

### 13.5 待验证项（转入构造分析 docs/03）

- `/btw`、`/rollback`、bg 会话的上下文关系（场景 9 的 tree 雏形）；
- webhook subscribe 同名更新的确切语义；
- channel_overrides / config.yaml 经 dashboard PUT 后的热生效范围。

---

## 14. 汇报触发与反馈排队协议（2026-09-16）

### 14.1 汇报触发的三层结构

1. **通道级声明**（注册时，agent.md §2.1 的 `report_policy`）：模式 `notify|digest|alert|silent` + 机器可读 triggers + **语义说明**（程序用途、"什么叫重要"——agent 做超出列举式触发条件的判断所依赖的原料）；
2. **条目级提示**（每次上报）：`suggested_priority` / `requires_feedback` 可覆盖通道默认；
3. **核心裁决**：声明是原料；"此刻是否打扰"仍由核心 agent 综合用户状态、静音时段、跨源上下文裁决（裁决集中原则不变）。

### 14.2 notify 队列：并发与音频单工之间的串行化点

**物理依据：agent 并发，但用户的耳朵和嘴是单工的。** 所有汇报先入队，唯一消费者按序播报——并发产生与串行播报在此解耦，agent 间零互斥（场景 8"氛围"的执行机制）。

**条目状态机**：

```
queued → active ─┬─ 无需反馈 → resolved(logged)
                 └─ requires_feedback → awaiting_feedback ─┬─ 用户回复 → resolved(actioned/dismissed/snoozed)
                                                            └─ 超时 → resolved(expired → 默认降级 logged，不催)
```

**四条规则**：
1. **单活动锁**：至多一条 awaiting_feedback 处于活动态；后续汇报（含需反馈者）进 FIFO——排队顺序即决策顺序；
2. **alert 插队**：alert 级可打断当前活动项，被断者回队列头部；
3. **超时默认安静**：非 alert 项超时自动 logged，不重复打扰（是否改催由用户在 App 调）；
4. **闭环**：用户回"晚点提醒" → 条目 resolved(snoozed) + agent 调提醒程序 MCP tool 改期 → 到点新触发 → 新条目入队（与场景 2 闭环）。

**落点**：notify 队列为 list_manager 第一公民；播报走 deliver/ntfy；用户回复走 App 上行 webhook → Hermes 会话 → agent 调队列工具。VPS 核心（Hermes）零修改。
