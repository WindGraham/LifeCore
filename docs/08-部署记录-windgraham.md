# 部署记录：windgraham.art（2026-09-16）

## 拓扑（与 docs/06 终审决议一致）
```
公网 ──NAT──▶ nginx:443（既有，复用 windgraham.art 证书）
                └─ 路径白名单 → 127.0.0.1:8790 lifecore-server（systemd，Restart=always）
                                    ├─ SQLite /opt/lifecore/data/lifecore.db
                                    ├─ registrar → hermes CLI subscribe/remove（热加载）
                                    └─ 回环转发 → 127.0.0.1:8644 hermes webhook（仅绑回环）
hermes-gateway.service（官方 --system --run-as-user root，Restart=always）
```

## 关键路径
- 代码：`/opt/lifecore/LifeCore`（git clone，公开库；服务源码 services/lifecore-server/）
- 数据：`/opt/lifecore/data/lifecore.db`（**备份=此文件 + /root/.hermes/**）
- 配置：`/root/.hermes/config.yaml`（webhook: 127.0.0.1:8644）
- nginx：白名单在 `/etc/nginx/sites-enabled/blog` 的 **443 server 块**内（该文件有 3 个 server 块，曾误插到 80 重定向块——改动前看结构）
- systemd：`lifecore-server.service` / `hermes-gateway.service`（均 enabled+active）

## 踩坑记录（后续部署者必读）
1. **服务器 bash 有 http(s)_proxy 环境变量**（mihomo），curl/urllib 默认走代理——测试一律 `unset http_proxy https_proxy` 或 `--noproxy "*"`
2. **nginx sites-enabled 里 blog.bak 备份文件会被 include**——备份必须移出 sites-enabled
3. blog 文件结构：server1(:80 内容) / server2(:443 ssl 裸块) / server3(:80 重定向)——白名单只能进 server2
4. Hermes 首次启动卡在 `uv pip install boto3`（镜像源问题）——手动 `kill` 后用 `--index-url https://pypi.org/simple` 重装即通
5. 系统 SQLite 3.40.1 有 WAL-reset bug → Hermes 自动降级 journal_mode=DELETE（日志有警告；后续外部只读 state.db 时注意）
6. Hermes 未配模型 provider：agent 模式路由会如实回复"未连接模型"（预期）；deliver_only/notify 链路不受影响

## 冒烟验证（全部通过）
配对 → 注册通道（hermes subscribe 热加载生效）→ HMAC V2 上行（验签/留档/转发）→ notify 待决策卡片（单活动锁）→ 反馈 actioned/snooze → since 游标同步 → 设备身份指纹一致。
外部公网路径（经 NAT）已验证可达：`https://windgraham.art/pair/code` 返回 200。

## 待办（下一步）
- [ ] 模型 provider key 写入 /root/.hermes/.env 后 agent 模式可用（`hermes setup`）
- [ ] TTS 预渲染 + App 播报三层保障（App 开发时）
- [ ] 加密备份导出（lifecore.db + .hermes → 加密包自动同步对象存储）
- [ ] voice.windgraham.art 子域已在 nginx 存在（LiveKit 项目）——App 语音管线注意别撞

## 第二次部署（同日）：api_server + MiniMax 语音 + Web 控制台

- **hermes api_server**：`:8642`（仅回环），key 在 `/etc/lifecore/env`（`LC_API_SERVER_KEY`）
- **MiniMax**：key 存 `/etc/lifecore/env`（`LC_MINIMAX_KEY`），**只在服务端**；TTS `speech-2.8-hd`（hex→mp3）、ASR `asr-1.0`（multipart，禁 webm/pcm——控制台侧已做 16k WAV 重编码）
- **Web 控制台**：`https://windgraham.art/console`（单文件，services/lifecore-server/static/console.html）
  - 免扫码配对（直接导入 code）；会话列表+流式对话（SSE：assistant.delta/tool.progress/assistant.completed）
  - **语音播报需用户显式同意**（localStorage + 页面横幅，浏览器自动播放策略）；句级 TTS 队列近流式输出
  - 通知队列/反馈、通道 CRUD+测试事件、cron 任务、设置（TTS 试听/能力清单/解绑）
- 已知：`GET /v2/models` 经 api_server 返回 401（hermes 侧 auth 面差异，待用）
- 安全提醒：MiniMax key 已在聊天中明文传输，建议尽快轮换

### 访问控制（2026-09-16 增补）
- `/console`、`/pair`、`/pair/*` 已加 nginx basic auth（用户 `lifecore`）
- 密码存于服务器 `/etc/lifecore/env`（`CONSOLE_PASSWORD`），htpasswd 在 `/etc/nginx/.htpasswd-lifecore`（644，www-data 可读）
- 注意：htpasswd 需 644（worker 以 www-data 读）；htpasswd 密码与 env 必须同一次生成（曾两次生成导致 401）
- API（/v1 /v2 /hk）仍走设备凭证层，不受影响

## 第三次部署：MiniMax-M3 + 缓存实测（2026-09-16）

### 模型接入（踩坑记录）
- 供应商：hermes 内置 `minimax-cn`（走 `api.minimaxi.com/anthropic` Anthropic 兼容端点）
- **坑1**：config 必须显式写 `model.provider: "minimax-cn"`——只写 `model.default` 不触发鉴权解析
- **坑2**：hermes 的 .env 自带**注释掉的占位行**（`# MINIMAX_CN_API_KEY=`），grep 判断会误判"已存在"而漏追加真实 key
- **坑3**：systemd 单元 Environment 是引号格式（`Environment="HERMES_HOME=..."`），sed 匹配要含引号；最终靠 hermes 自加载 .env 解决
- 验证：中文对话正常，agent 能读出注入的 [live]+清单全文块

### 缓存实测（MiniMax 自动前缀缓存）
- 阈值：~940 token 前缀不触发缓存；**3000+ token 稳定命中**
- 直连 API：turn1 input=3247 → turn2 起 cache_read=3328、每轮新增 input 仅 ~100-140 token；延迟 4.6s→2.6s
- **全链路**（控制台→BFF注入→hermes→M3）：turn1 5755ms → turn2 1772ms → turn3 1873ms（3倍提速）
- 结论：架构假设验证——动态 [live] 块在消息头（提示词末尾），系统提示+历史前缀整块命中缓存；清单全文注入不炸缓存

### 缓存深挖结论（2026-09-16 补充，抓包实证）
**抓包方法**：本地日志代理 + `MINIMAX_CN_BASE_URL` 指向它，捕获 hermes→MiniMax 原始请求。

| 实验 | 结果 |
|---|---|
| 我们侧前缀稳定性 | ✅ system 提示 16979 字符逐字节一致、23 tools 顺序一致、history 严格递增——**我们的组装完全缓存友好** |
| Anthropic 线 非流式 | ✅ 稳定全命中（3328/16000 tokens，重复多次） |
| Anthropic 线 流式（hermes 实际用法） | ❌ 永不命中（128 基线；beta header 无效） |
| OpenAI 线 流式 | ❌ 抽签：8 轮仅 1 轮命中（16128），其余 miss——**MiniMax 流式缓存是节点彩票** |

**结论：MiniMax 流式请求基本无可用前缀缓存 = 平台限制，非我方问题。** 非流式缓存完美证明我们的 prompt 工程是对的。

### 降本调整（已生效）
- `platform_toolsets` 瘦身：api_server/webhook 只留 [web, cronjob, skills, todo, clarify, tts]（砍 terminal/browser/execute_code/delegation 等执行类——与"核心只调度不执行"一致）
- 效果：**新会话每轮 input 13851 → 7160 tokens（-48%）**，工具 23 → 10 个
- 副作用注意：核心 agent 暂不能在 VPS 上跑 shell/浏览器（需要时改 toolsets 配置即恢复）

### 非流式切换（2026-09-16，缓存最终解决）
- `model.streaming: false`（agent_init 原生支持）→ hermes 全部走非流式
- **抓包+重放实证**：hermes 实际请求体（stream=None，beta=interleaved-thinking）直接重放到 MiniMax → cache_read=6912/7168 满命中。**缓存一直都在，之前只是 hermes 的 usage 报表不显示 cache 字段**
- 效果：每轮输入成本 ~7160 → ~200 token（**省 ~97%**）
- 代价：控制台聊天失去打字机流式效果（回复一次性出现）；功能/质量无影响
- 排障遗产：`model.streaming` 解析对 YAML bool 友好（str(False)="false"）；重启后立刻测会撞上 gateway 未就绪，等 10 秒

## 9. google-bridge 部署（2026-09-16）

**位置**：用户 PC（arch-asus），systemd user 常驻（linger 已开），repo 于 `services/google-bridge/`。

**实测要点（都是踩过或验证过的）**：
- gws CLI 子命令**空格分隔**（`gmail users messages list`，不是 `users.messages`）；`drafts.create`/`messages.modify` 必须补 `--params {"userId":"me"}`。全部 19 个动作形状用 `gws --dry-run` 验证（Discovery 动态构建请求，无需 OAuth）。
- 本机 googleapis 直连不可达：config `gws_proxy=http://127.0.0.1:7890` 只注入 gws 子进程；bridge 自身 urllib 用禁代理 opener（`_OPENER`）——两者必须隔离，否则 VPS 请求被 mihomo 搅黄。
- gws 免 root 安装：GitHub Releases 二进制解压到 `~/.local/opt/`，软链 `~/.local/bin/gws`（npm -g 前缀是 /usr 需 root）。
- lifecore-server 新增：`device_commands`（pending/running/done/failed，running 300s 超时自动回炉）、`bridge_heartbeat`、`/v2/commands/pending`（长轮询 wait≤30s）、`/v2/commands/{id}/result|{id}`、`/v2/bridge/heartbeat|status`；`create_channel` 增 `hermes_script` 透传。
- hermes MCP 挂载：`lifecore_google`（venv python + LC_DATA_DIR + LC_GOOGLE_DEVICE=google-bridge）；SOUL.md 追加 `SOUL-google.md`。
- E2E 已验：MCP 入队→bridge 长轮询→执行→回执→MCP 读回（echo 全链）；失败回执（gws 未授权错误透传）；spool 断点续传（selftest 的假事件在 run 启动时自动补发并真实投递）；hermes agent 实调 google_status 成功。
- 四通道已注册：google-gmail(ch_f1efbeb1) / google-calendar(ch_39157391) / google-drive(ch_e3a5f8d0) / google-tasks(ch_a0a9a797)，device google-bridge。
- 待用户：`gws auth login -s drive,gmail,calendar,tasks`（浏览器 OAuth）；Gmail 应用专用密码填 config 后 `gmail.enabled=true`；calendar/drive/tasks 已可开（依赖 gws OAuth）。

### 9.1 gws OAuth 实操坑（2026-09-16 实测）

- **流程**：装 gcloud（Release 二进制免 root 解压 ~/opt 即可）→ `gcloud auth login` → `gcloud projects create <id>`（gws setup 不自动建项目）→ `gws auth setup --project <id>`（能自动开 API；OAuth 客户端建不了，要 console 手点）→ console 建 Desktop OAuth client → `gws auth login -s drive,gmail,calendar,tasks`。
- **Testing 模式 403 access_denied**：必须把登录账号加进 OAuth consent screen 的 Test users（受众群体→测试用户）。
- **token 交换也要代理**：gws 换 code 走 oauth2.googleapis.com，systemd 跑时忘设 HTTPS_PROXY 会报 "OAuth flow failed: Hyper error: client error (Connect)"。
- **后台授权进程用 systemd-run --user**（plain setsid/nohup 会随 DSH 执行器的 scope 回收被杀；user unit 稳）。
- **远程浏览器授权**：Mac `ssh -L <port>:localhost:<port> -N ...` 隧道 + 本机打印的 URL；gws 每轮端口随机（8085/39397/41209 都出现过）。
- **client_secret.json 有时不被认**（0.22.5）：用 GOOGLE_WORKSPACE_CLI_CLIENT_ID/SECRET 环境变量最稳；token 落盘（credentials.enc）后普通调用不再需要这对 env。
- 登录后 `gws drive files list` 实测通；桥四线程（commands+calendar+drive+tasks）心跳全 ok，gws_auth=true。
- 账号 windgraham648@gmail.com，项目 lifecore-gws-648。

## 10. hermes Dashboard 上线（2026-09-16）

原生 Web UI 挂 `/console`（旧控制台移 `/lc`），systemd 常驻，nginx basic auth +
上行 Bearer 注入。全部实测：SPA 前缀改写 / API 200 / 真实 WS 握手 CONNECTED。
详见 `deploy/hermes-dashboard-接入记录.md`。
状态注入升级为 `pre_llm_call` shell hook（覆盖 web UI 等所有对话面，与 BFF 双通道守卫互斥）。

## 11. 数据盘迁移（2026-09-17）

VPS 挂载 60G 数据盘 /dev/vdb1 → /data（与 docker 等共用）。系统盘 20G 曾告警 97%，
迁移后 55%（8.4G 可用）。全部用 rsync + 绝对软链（路径不变，服务无感）：

- `/root/AIIC-Project` → /data/AIIC-Project（2.3G）
- pnpm 全局库 → /data/pnpm-store（1.4G）；Playwright 浏览器 → /data/ms-playwright（1.3G）
- `/opt/lifecore/data` → /data/lifecore-data（SQLite+密钥，停 lifecore-server 2s 搬迁）
- `/root/.hermes` → /data/hermes（state.db/凭证/SOUL/config，停 gateway+dashboard ~30s）

迁移后全量自检：三服务 active、state-block 注入端点活、桥心跳新鲜（本机 google-bridge
无感续跑）、5 通道在网、MCP 配置完好。journald 已封顶 100M 防复发。
恢复注意：灾难重建时先做 /data 软链再启服务；备份应涵盖 /data/lifecore-data 与 /data/hermes。
