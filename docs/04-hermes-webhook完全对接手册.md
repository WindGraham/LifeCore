# Hermes Webhook 完全对接手册（LifeCore 版）

> 定位：不走任何现成平台适配器，**一切能力自建 webhook 通道**时的权威写法与规则。
> 依据：上游官方文档 webhooks.md + 本仓库 `hermes/gateway/platforms/webhook.py` 源码实证（行号随上游漂移，以语义为准）。
> 读者：你自己 + 将来替你接入的 coding agent。**所有 curl 和代码可直接照抄。**

---

## 0. 总模型（先建立心智图）

```
你的服务 ──POST──▶ http(s)://vps:8644/webhooks/<route名>   ← 一个监听端口，N 个 route
                     │
                     ├─ 1. 验签（route 级 HMAC secret，401 直接拒）
                     ├─ 2. 限流（30/min/route）+ 幂等（delivery ID 1h）+ 体积（1MB）
                     ├─ 3. events 过滤 → filters 声明式过滤 → script 变换
                     ├─ 4. 分派三选一：
                     │    a) agent 模式：prompt 模板渲染 → 新 agent run → 响应 deliver
                     │    b) deliver_only：零 LLM，模板渲染结果直发投递目标
                     │    c) cron_job：触发已有 cron 任务，模板作 transient 上下文
                     └─ 5. 响应：200 delivered / 202 coalesced / 4xx 错误
```

**route = 一个虚拟通道**（对应 LifeCore 设计里的 push 通道）：一个 URL + 一个 secret + 一套处理规则。路由之间完全隔离，可单独吊销。

## 1. 启用与部署

### 1.1 最小启用（config.yaml 方式）

```yaml
# ~/.hermes/config.yaml
platforms:
  webhook:
    enabled: true
    extra:
      port: 8644            # 默认 8644；host 默认绑全部接口
      secret: "global-fallback-secret"   # route 未配 secret 时的兜底；每个 route 应有自己的
      rate_limit: 30        # 每 route 每分钟（默认 30）
      max_body_bytes: 1048576   # 默认 1MB
```

或纯环境变量：`WEBHOOK_ENABLED=true` + `WEBHOOK_PORT` + `WEBHOOK_SECRET`（setup 向导 `hermes gateway setup` 会带你配）。

### 1.2 验证

```bash
curl http://localhost:8644/health
# 期望：{"status": "ok", "platform": "webhook"}
```

### 1.3 公网暴露（生产姿势）

Caddy 反代，对外只露 443：

```
agent.example.com {
    reverse_proxy localhost:8644
}
```

上游服务推送地址即 `https://agent.example.com/webhooks/<route>`。TLS + 高熵路径 + HMAC 三层叠加。

## 2. Route 配置全字段参考

静态 route 写 `platforms.webhook.extra.routes.<名>`；动态 route 见 §11。**`extra:` 嵌套值优先于平级同名字段**（两处都写时 extra 赢）。

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `secret` | **是** | 继承全局 | route 级 HMAC 密钥。**任何 route 无 secret 则适配器拒绝启动**。测试专用值 `"INSECURE_NO_AUTH"` 仅当 host 绑 loopback 时被接受，绑 0.0.0.0 直接拒启动 |
| `events` | 否 | 空=全收 | 事件类型白名单。取 `X-GitHub-Event` / `X-GitLab-Event` / payload `event_type` |
| `prompt` | 否 | 全量 JSON | 模板（§4）。空则整个 payload 以缩进 JSON 倒入（截 4000 字符） |
| `filters` | 否 | — | 声明式载荷过滤（§5），不匹配返回 200 `{"status":"ignored","reason":"filter"}` |
| `script` | 否 | — | 本地变换脚本（§6），路径必须在 `~/.hermes/scripts/` 内 |
| `skills` | 否 | — | 该 route 触发的 agent run 加载的技能名列表 |
| `toolsets` | 否 | webhook 默认窄集 | **替换**（非合并）该平台工具集；只能在配置文件手写，`hermes webhook subscribe` 不接受此参数——防止 agent 自建订阅自我提权 |
| `deliver` | 否 | `log` | 响应投递目标（§8）。`deliver_only` 模式必填真实目标 |
| `deliver_extra` | 否 | — | 投递附加参数，值支持模板语法（§8） |
| `deliver_only` | 否 | false | 零 LLM 直投（§9a）。与 `cron_job`、`coalesce` 互斥 |
| `cron_job` | 否 | — | 触发已有 cron 任务（§9b）。与 `deliver_only` 互斥 |
| `coalesce` | 否 | — | 防抖合并（§7）。仅 agent 模式 |
| `profile` | 否 | default | 多 profile 路由绑定（POST 必须打 `/p/<profile>/webhooks/<route>` 前缀才收） |

## 3. 认证：五种验签方案（发送端写法）

适配器按以下顺序识别（源码 `webhook.py` 验签段）：

### 3.1 通用 V2（**推荐，自建服务的默认选择**）

```
X-Webhook-Signature-V2: <hex hmac_sha256(secret, "<unix_ts>.<raw_body>")>
X-Webhook-Timestamp: <unix 秒>
```

时间戳必须在服务器时钟 ±300 秒内，否则拒。发送端代码：

```python
import hashlib, hmac, time, requests

SECRET = b"s3cr3t-from-subscribe-response"
ts = str(int(time.time()))
body = '{"title":"hello","value":42}'
sig = hmac.new(SECRET, f"{ts}.{body}".encode(), hashlib.sha256).hexdigest()
requests.post(
    "https://agent.example.com/webhooks/my-route",
    data=body,
    headers={"Content-Type": "application/json",
             "X-Webhook-Signature-V2": sig,
             "X-Webhook-Timestamp": ts},
)
```

```javascript
// Node 发送端
const crypto = require("crypto");
const ts = String(Math.floor(Date.now() / 1000));
const body = JSON.stringify({ title: "hello", value: 42 });
const sig = crypto.createHmac("sha256", SECRET).update(`${ts}.${body}`).digest("hex");
await fetch("https://agent.example.com/webhooks/my-route", {
  method: "POST",
  headers: { "Content-Type": "application/json",
             "X-Webhook-Signature-V2": sig, "X-Webhook-Timestamp": ts },
  body,
});
```

### 3.2 GitHub

`X-Hub-Signature-256: sha256=<hex hmac_sha256(secret, raw_body)>`；事件头 `X-GitHub-Event: pull_request`；幂等 ID `X-GitHub-Delivery`。

### 3.3 GitLab

`X-Gitlab-Token: <secret 明文>`（精确字符串比对，不是 HMAC）；事件头 `X-GitLab-Event`。

### 3.4 Standard Webhooks（svix 规范）

三个头齐备才走此分支：`webhook-id`、`webhook-timestamp`（±300s 防重放）、`webhook-signature: v1,<base64(hmac_sha256(secret, "{id}.{ts}.{raw_body}"))>`。

### 3.5 通用 V1（遗留，勿用于新代码）

`X-Webhook-Signature: <hex hmac(secret, body)>`——无时间戳，被截获可无限重放。上游仍兼容但打弃用警告。

**规则**：secret 配了但请求头对不上任何已知方案 → 401。header 值用 `hmac.compare_digest` 常量时间比较（源码如此）。

## 4. Prompt 模板语言

- `{pull_request.title}` → `payload["pull_request"]["title"]`（点路径）
- `{__raw__}` → 整个 payload 缩进 JSON（截 4000 字符）
- 缺失键**原样保留** `{key}` 文本，不报错
- 嵌套 dict/list 序列化后截 2000 字符
- `deliver_extra` 的值同样支持模板

```yaml
prompt: |
  课程系统有新动态：
  课程：{course.name}
  作业：{assignments}
  时间：{published_at}
```

## 5. filters 声明式过滤

在验签、解析、events 之后，模板渲染之前执行。全部操作符：

```yaml
filters:
  - field: "payload.labels"        # payload.* 前缀：有顶层 payload 对象时读它，平铺载荷读根
    contains: "hermes"             # 字符串子串 / 列表元素 / dict 键
  - any:                           # 组：all / any / not 可嵌套
      - field: "payload.priority"
        equals: 4
      - field: "payload.project_id"
        in_file: "~/.hermes/data/todoist/watchlist.json"   # JSON数组/对象(取键)/换行文本文件
  - field: "event"                 # 解析后的事件类型
    not_equals: "ping"
  - field: "headers.X-Custom"      # 请求头
    exists: true
  - field: "payload.draft"
    missing: true                  # 键不存在
  - field: "payload.ref"
    regex: "^refs/heads/(main|release)$"
  - field: "payload.action"
    in: ["opened", "reopened"]
```

不匹配 → 200 `{"status":"ignored","reason":"filter"}`（上游视为成功，不再重试）。

## 6. script 变换（逃生舱）

- 文件必须在 `~/.hermes/scripts/` 下（越界拒绝）；`.sh/.bash` 用 bash，其余用 Python
- payload JSON 从 **stdin** 进；**stdout 出 JSON 对象则替换载荷**；出纯文本 → 挂到 `script_output` 字段
- 空 stdout / 精确 `[SILENT]` / `{"__hermes_ignore__": true}` / 非零退出 / 超时 → 忽略（200 `reason: script`）

```python
# ~/.hermes/scripts/course-normalize.py
import json, sys
p = json.load(sys.stdin)
if not p.get("assignments"):
    print("[SILENT]")          # 无作业的轮询噪音直接吞
    raise SystemExit(0)
p["body"] = "；".join(a["title"] for a in p["assignments"])
print(json.dumps(p))
```

## 7. coalesce 防抖（同源爆发合并）

```yaml
coalesce:
  key: "{repository.full_name}#{pull_request.number}"  # 点字段或模板都行
  window_seconds: 30      # 静寂窗（默认 30）
  max_wait_seconds: 300   # 从首事件起的派发上限（默认 300）
```

语义（源码实证）：
- 同 key 新事件**替换**待发事件并推回静寂窗；安静期满用**最新一次** payload 派发**一次** agent run
- key 解析不出来（payload 缺字段）→ 该事件**立即派发**，绝不误合并
- 合并过的事件在 prompt 里注明"已吞并 N 条更早事件"
- 期间收到重复 POST 返回 **202 `{"status":"coalesced"}`**（幂等检查先于 coalesce，provider 重试不算合并）
- 适配器断开（重启/stop）时**冲刷**待发组，不丢（内存态，硬 kill 最多丢当前窗）

## 8. 投递（deliver）

| 目标 | 说明 |
|---|---|
| `log` | 默认。记日志，调试用 |
| `telegram` / `discord` / `slack` / `signal` / `matrix` / `mattermost` / `ntfy` / `email` 等 | 跨平台投递（目标平台须已启用）；未给 chat_id 时落该平台 home channel |
| `github_comment` | 经 `gh` CLI 发 PR/issue 评论，需 `deliver_extra: {repo, pr_number}` |

```yaml
deliver: telegram
deliver_extra:
  chat_id: "{match.telegram_chat_id}"    # 模板从 payload 取值
  message_thread_id: "42"                # Telegram 论坛 topic
```

## 9. 两种零 LLM 模式

**a) `deliver_only: true`** — 渲染后的 prompt 模板**就是**最终消息，直发投递目标。亚秒级、零 token。适合：监控告警、任务完成通知、"纯转发"类通道。

**b) `cron_job: "<job名或ID>"`** — 触发已有 cron 任务，渲染 prompt 作该次运行的 transient 上下文（不改任务本体配置）；同一任务 at-most-once，webhook 爆发不会重入。适合：事件驱动的周期任务。

## 10. 动态订阅（agent 自服务的入口）

```bash
hermes webhook subscribe course-deadlines \
  --prompt "课程 {course.name} 新作业：{assignments}" \
  --events "assignment_published" \
  --deliver telegram --deliver-chat-id "123456"
# → 输出新 route 的 URL 和自动生成的 secret，立即生效，无需重启

hermes webhook list
hermes webhook test course-deadlines --payload '{"course":{"name":"数据库"}}'
hermes webhook remove course-deadlines
```

机制（源码实证）：写入 `~/.hermes/webhook_subscriptions.json`；适配器**每个请求处理时查 mtime，变了热加载**（坏块跳过该 route 而不 500）；静态 config route 与动态同名时**静态优先**；`toolsets` 字段只能手写进 JSON——CLI 不接受，防 agent 自我提权。

**该文件被 `agent/file_safety.py` 列为控制文件**（与 config.yaml、.env 同级保护），别把它的位置当普通数据目录。

## 11. 可靠性边界

| 项 | 值 | 行为 |
|---|---|---|
| 幂等 | delivery ID 缓存 1h | 认 `X-GitHub-Delivery` / `svix-id` / `webhook-id` / `X-Request-ID`，重复 → 200 `status=duplicate` 不重投 |
| 限流 | 30/min/route（可调） | 超限 429 |
| 体积 | 1MB（可调） | 超限 413 不读体 |
| 路由不存在 | — | 404 |
| 验签失败 | — | 401 |
| JSON 解析失败 | — | 400 |
| deliver_only 投递被拒 | — | 502（上游可智能重试） |

**响应码速查**：`200 delivered` / `200 ignored(filter|script)` / `200 duplicate` / `202 coalesced` / `202 cron_job 已受理` / `400` / `401` / `404` / `413` / `429` / `502`。

## 12. 安全模型（不可协商）

1. **验签只证明发送者，不证明内容**。PR 标题、通知文本、任何 payload 字段都是**不可信输入**（可能是 prompt injection）。
2. 因此 webhook 触发的 agent run 默认工具集是**收窄的**（web_search/web_extract/vision/clarify），无 terminal/file。
3. 信任的发送方（你自己的手机/电脑）需要动作能力时，**逐 route** 在配置文件加 `toolsets: ["terminal", "file", ...]`——记住这是替换语义。任何持有该 route secret 的人 = 持有一个带这些工具的 agent。
4. webhook 会话保持审批开启（exec approval），注入指令不能无人值守执行。
5. 模板收窄优先：用命名字段（`{title}`）而非 `{__raw__}`/空模板。
6. VPS 上跑 Docker/SSH terminal 后端做沙箱，暴露公网时这是底线。

## 13. 完整实例（四个可直接改造）

### 13.1 手机通知上报（信任源，要动作能力 → config 手写）

```yaml
platforms:
  webhook:
    enabled: true
    extra:
      port: 8644
      secret: "global-fallback"
      routes:
        phone-notify:
          secret: "phone-hmac-2026"          # 每设备一个，App 配置里写死
          toolsets: ["terminal", "file", "web"]   # 信任自己手机：替换窄集
          prompt: |
            手机通知 [{app}] {title}
            内容：{text}
            包名：{pkg}  时间：{post_time}
            判断重要性并决定：播报/记录/触发动作。
          deliver: log
```
手机端 POST 用 §3.1 V2 签名。会话判别留待任务 3（channel 注册表的 session 规则）。

### 13.2 电脑 agent 任务回报（信任源，零 LLM 直转）

```bash
hermes webhook subscribe pc-agent-report \
  --deliver telegram --deliver-chat-id "<你的chat_id>" \
  --deliver-only \
  --prompt "✅ 任务完成：{task_id} — {summary}"
```
worker 完成时 POST：`{"task_id":"rpt-42","summary":"报告已生成，桌面/报告/"}` → 秒级到 Telegram。

### 13.3 课程系统桥（不可信/半信任，过滤+变换）

```yaml
routes:
  course-deadlines:
    secret: "course-bridge-secret"
    events: ["assignment_published"]
    filters:
      - field: "payload.assignments"
        exists: true
    script: "course-normalize.py"      # §6：无作业 → [SILENT]
    prompt: "课程 {course.name} 新作业：{body}，截止 {deadline}"
    deliver: telegram
    coalesce:
      key: "{course.id}"
      window_seconds: 60
```

### 13.4 传感器/监控（deliver_only + ntfy 落地到手机）

```bash
hermes webhook subscribe sensor-alerts \
  --deliver ntfy --deliver-only \
  --prompt "🌡️ 温度告警：当前 {value}℃（阈值 {threshold}）@{device}"
```

## 14. 排障手册

| 症状 | 检查顺序 |
|---|---|
| POST 无响应/超时 | 端口暴露？`curl :8644/health`；Caddy 反代路径对不对 |
| 401 | route secret 与发送端是否一致；V2 的时间戳 ±300s？签名串是 `<ts>.<body>` 不是 `<body>`？body 必须**原始字节**，重新序列化会炸 |
| 收到 200 但 agent 没动 | `events` 白名单挡住了？filters `ignored`？script 输出了 `[SILENT]`？看 gateway 日志 `[webhook]` 前缀 |
| deliver_only 502 | 目标平台适配器没启用/没连上 |
| 重复触发 | 上游没发 delivery ID 头（Hermes 用时间戳兜底会重复）；检查 `X-Request-ID` |
| 改了配置不生效 | 动态订阅是否被同名静态 route 覆盖；文件 mtime 是否真的变了 |
| agent 行为怪异 | 默认窄工具集是否符合预期；payload 里有没有注入文本进了 `{__raw__}` |

## 15. 与 LifeCore 通道层的映射（设计对接点）

| 本手册概念 | LifeCore 设计（docs/02） |
|---|---|
| route | push 通道 |
| `hermes webhook subscribe` + 热加载 | `/v1/channels` registrar 的底座（registrar = HTTP 鉴权壳 + 调 CLI） |
| route secret 自动签发 | 通道凭证 |
| `script`/`prompt` 模板 | `format.mapping` 的第一代形态（结构化映射在 inbox 服务里补齐） |
| `deliver_only` / `cron_job` | 成本金字塔最底层的现成实现 |
| `toolsets` 逐 route 提权 | 通道权限分级（最小权限） |
| §12 安全模型 | agent.md 的三条铁律之一 |

**下一步**：registrar 实现时，subscribe 响应里的 URL+secret 原样回给调用方，并在注册表（SQLite）里登记通道元数据（name/archetype/session 规则/创建时间），供 inbox 与任务 3 使用。
