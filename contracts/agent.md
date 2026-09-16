# LifeCore 对接契约 v0.1

> 本文件是 LifeCore VPS 对外的**唯一权威对接文档**，公开于 `https://<你的域名>/.well-known/agent.md`。
> 版本：0.1（2026-09-16 草案）· `/v1/*` 端点一旦发布永不破坏，本文档只增不改。
> 读者：任何要接入本系统的 coding agent。写法约定：**命令优先，解释殿后，全部样例可直接照抄。**

## 0. 铁律（违反任一视为接入失败）

1. 上行事件必须三级分级的思维：元数据（A）可全量；摘要（B）经地方初筛；原文（C）**默认不出地方**，核心需要时经你暴露的 MCP tool 现场返回；
2. 你上报的每条摘要必须带**精确指针**（你的系统里能定位到原文的 ID/时间段/路径），核心随时可能回查；
3. 所有 HTTP 上行必须 HMAC 签名（见 §2）；
4. 你的程序必须自维护合规日志并暴露 `search_logs` 工具（见 §6）；
5. 汇报必须有触发条件与结束条件，允许被删除（见 §5 汇报设计规范）。

## 1. 系统是什么

一个以 Hermes 为网关核心的个人聚合 agent 系统：核心负责全局汇总、打扰裁决、播报与任务分派；你们是它的眼睛（数据源）和手（动作能力）。**你不关心 VPS 内部实现**，只对接本文档描述的表面对象。

## 2. 认证与上行

### 2.1 拿凭证（首次，人工一次性）

用户在 App「设置→开发者」生成注册令牌（10 分钟有效）。你用它换长期通道凭证：

```bash
curl -X POST https://<域名>/v1/channels \
  -H "Authorization: Bearer <注册令牌>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "wechat-monitor",
    "host": "我的手机（root 解码，常驻）",
    "direction": "source",
    "archetype": "message",
    "uplink_level": "AB",
    "report_policy": {
      "mode": "notify | digest | alert | silent",
      "triggers": [{ "field": "$.severity", "op": "gte", "value": "high" }],
      "semantics": "这个程序的用途是什么、什么情况算重要（自然语言，agent 靠它做判断）",
      "digest_window": "15m",
      "requires_feedback": false
    },
    "format": { "raw": true, "sample": { "sender": "张三", "group": "课程群", "text": "作业明天交" } },
    "session": { "discriminator": "$.group", "fallback": "$.sender" }
  }'
# → 200 { "channel_id": "ch_x9f2",
#         "ingest_url": "https://<域名>/hk/ch_x9f2",
#         "secret": "<hmac-secret>",
#         "query_tools_hint": "请在你的 MCP server 中暴露 get_original(pointer) 等回查工具" }
```

字段：`host` = 这个程序跑在哪台设备/位置（接入服务清单的"哪台设备"维度，必填）；`direction` = source（只报）/ sink（只收动作）/ both；`archetype` = message|metric|file|task|calendar|alert|result 七选一；`uplink_level` = A|AB|ABC（默认 AB）；`report_policy` = **汇报策略声明**（见 §2.1a，缺省按 mode=notify 处理）；`format` 三档声明（直接 CloudEvents / mapping / raw+样本，注册器会回复建议映射）；`session.discriminator` = 你数据里当"会话/话题"用的字段（JSONPath），用于核心的上下文归属。

### 2.1a 汇报策略声明（report_policy，核心裁决的原料）

你比核心更懂你的数据——注册时必须声明"什么情况下值得汇报"：

| 字段 | 语义 |
|---|---|
| `mode` | `notify` 每条都是候选，核心裁（默认）；`digest` 合并为定期摘要播报；`alert` 可破静音时段（必须配 triggers 说明升级条件）；`silent` 永不主动汇报，只能被查询（隐私敏感通道的归宿） |
| `triggers` | 机器可读的触发条件（JSONPath + 比较算子），列举你认为重要的情形 |
| `semantics` | **必填自然语言**：这个程序是干什么的、什么情况算重要。核心 agent 用它做 triggers 没列举到的判断 |
| `digest_window` | digest 模式的合并窗口 |
| `requires_feedback` | 默认 false；true 表示你的汇报通常需要用户决策（做/不做/稍后），播报将排队等待回复 |

**注意**：声明是建议不是保证——最终打扰裁决在核心 agent（它要综合用户状态、静音时段、跨源上下文）。条目级上报可带 `suggested_priority`/`requires_feedback` 覆盖通道默认。

### 2.2 上行（HMAC，通用 V2）

```
X-Webhook-Signature-V2: hex_hmac_sha256(secret, "<unix_ts>.<raw_body>")
X-Webhook-Timestamp: <unix 秒>     # ±300s 内有效
```

```python
import hashlib, hmac, time, requests
ts = str(int(time.time())); body = '{"level":"B","pointer":"wx:8832","summary":"课程群：作业改到周五"}'
sig = hmac.new(SECRET.encode(), f"{ts}.{body}".encode(), hashlib.sha256).hexdigest()
requests.post(INGEST_URL, data=body, headers={
    "Content-Type": "application/json",
    "X-Webhook-Signature-V2": sig, "X-Webhook-Timestamp": ts})
```

响应：`200 delivered` / `202 coalesced` / `401` 验签失败 / `429` 限流。幂等：带 `X-Request-ID` 可避免重试重复处理。

### 2.3 下行（核心调用你的能力）

把你程序的能力包成 MCP server（stdio 或 HTTP），注册：

```bash
curl -X POST https://<域名>/v1/mcp-servers \
  -H "Authorization: Bearer <用户token>" \
  -d '{"url": "http://<你的内网穿透或tailscale地址>:port/mcp", "auth": "<bearer>"}'
```

注册后核心 agent 立即多出新工具。**强烈建议暴露的最小工具集**：`get_original(pointer)`（回查原文，§0 铁律 2 的落实）、`search_logs(query, since)`（§0 铁律 4）、`get_status()`（设备/程序健康与当前状态——它会进入"接入服务清单"）。

## 3. 自检与排障

```bash
curl -X POST https://<域名>/v1/test-event -H "Authorization: Bearer <token>" \
  -d '{"channel_id": "ch_x9f2"}'          # 注入合成事件，走完整管道
curl https://<域名>/v1/channels/ch_x9f2 \  # 事件计数、最近错误
  -H "Authorization: Bearer <token>"
```

## 4. 客户端重连与断点续传

- 网络错误：指数退避 2s→5s→10s→30s→60s 封顶，静默重试；
- 401/凭证被拒：**停止重试**，报告"凭证失效需重新配对"（安全事件，重试=把吊销当故障）；
- 服务器指纹变化：停止并报警（可能换机或中间人）；
- 你的上行如需保证不丢：本地先落盘（append-only + 游标），发送成功才推进游标；重连后用 `since` 语义补发（向注册器声明你的游标字段）。

## 5. 汇报设计规范（场景 4 的程序 agent 必读）

当用户对你说"给这个程序设计汇报"时：

1. **读程序的输出/日志**，识别事件类型，归入某个 archetype；
2. **触发条件**：明确什么事件值得上报（变化、越阈、完成、异常），什么不上（心跳、重复、无变化——静默）；
3. **结束条件**：这个汇报关系何时消亡（任务完成、程序退出、条件不再成立）；
4. **自删除**：结束条件达成时，调 `DELETE /v1/channels/{id}` 注销，并说明原因；
5. **汇报条目带反馈提示**：若该条需要用户决策，附 `"requires_feedback": true, "feedback_options": ["做", "不做", "稍后提醒"]`——播报将排队等待回复（单活动锁，见 VPS 侧 notify 队列协议）；
6. **验收**：发测试事件 → 查 `/v1/channels/{id}` 计数 → 报告用户"已上线/测试事件可见"。

## 6. 日志契约

- 你的程序自维护 append-only 日志（轮转、保留期 ≥7 天）；
- 暴露 `search_logs(query, since, limit)` MCP tool；
- 日志不进上报内容，除非核心回查；
- VPS 不留你的详细日志，只留你注册时声明的状态摘要。

## 7. 现有能力清单（自动生成，接入前先读）

> 本节目录由注册表自动生成：当前接入的 MCP server、工具名、所属设备与用途。
> 接入前先查这里，**复用已有能力，不要重复造**。

（实现：registrar 从注册表渲染本节。）

## 8. 灾难恢复 runbook（如果你是一台刚恢复的空服务器代理）

1. 若你持有用户的加密备份：进入待恢复模式，等待用户确认后 `POST /v1/restore`；
2. 恢复后所有旧 `ch_*` URL 与凭证自动复活，客户端无感重连；
3. 本文件是系统唯一永恒常量：域名 + 本路径永不变。

## 附录：事件负载原型（archetype 字段骨架）

message: actor/title/body/thread · metric: name/value/unit/tags · file: path/op/size · task: state/summary/progress · calendar: title/start/end/location · alert: severity/source/description · result: ok/error/detail
