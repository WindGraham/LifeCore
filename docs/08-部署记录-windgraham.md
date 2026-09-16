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
