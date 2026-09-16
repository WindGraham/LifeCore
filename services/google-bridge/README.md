# google-bridge — LifeCore 的 Google 边缘桥

> 跑在用户 PC 的常驻服务：监听 Google 生态（秒级~小时级），上报 B 级摘要事件到 LifeCore；
> 同时作为核心 agent 的"手"，长轮询接收指令、用 [gws CLI](https://github.com/googleworkspace/cli) 在本地执行 Google 写操作。
>
> 架构依据：`contracts/agent.md` v0.1、`docs/06` 定型决议；调研结论见 `Pixel-10-pro-xl/LifeCore-Google接入调研总结.md`（§2 勘误：官方 MCP 不存在，官方 CLI=gws）。

## 架构（只出不进，零入站端口）

```
本机 PC: google-bridge (纯 stdlib daemon)
   ├─ Gmail IMAP IDLE ──────────────秒级──┐
   ├─ Calendar syncToken 轮询 ──────60s──┤  B级摘要(HMAC-V2)   ┌─ VPS: lifecore-server
   ├─ Drive changes.list 轮询 ──────300s─┼─POST /hk/<ch>────▶│    ├─ events 留档 + notify 裁决
   ├─ Tasks 镜像 diff ────────────3600s─┘                    │    └─ 转发 hermes（{__raw__} prompt）
   └─ 长轮询 GET /v2/commands/pending ◀──device_commands(SQLite)◀── google_mcp.py（hermes MCP 工具）
        → 本地 gws CLI 执行 → POST /v2/commands/{id}/result ──────→  MCP 轮询取回给核心 agent
```

- 上行凭证 = 每通道 HMAC secret；下行凭证 = 设备 token（Bearer）。
- `gws_proxy`：本机 mihomo 代理（googleapis 直连不可达；留空则直连）。bridge 自己访问 VPS 永远不走代理，二者互不干扰。
- Gmail 监听走**应用专用密码**（IMAP，秒级、零 Cloud 项目）；其余走 **gws OAuth**（一个凭证通吃 Calendar/Drive/Tasks/Gmail API）。
- 原文（C 级）永不上 VPS：`google_get_original(pointer)` 现场回查。

## 一次性安装

### 1. 装 gws CLI

```bash
npm install -g @googleworkspace/cli      # 需要 root；免 root 就下 GitHub Releases 预编译二进制放 ~/.local/bin/
gws auth setup                            # 有 gcloud 时全自动；否则按提示去 Cloud Console 手配
# OAuth consent: External + Testing，把自己加 Test user（个人自用免审核）
gws auth login -s drive,gmail,calendar,tasks   # 未验证应用别贪多：recommended 85 scope 会炸
gws drive files list --params '{"pageSize": 3}'  # 验证
```

### 2. Gmail 应用专用密码（仅监听用）

Google 账号开 2FA → <https://myaccount.google.com/apppasswords> → 生成 16 位密码。

### 3. 配对设备 + 注册通道

```bash
# 浏览器打开 https://windgraham.art/pair （basic auth: lifecore / Terraria.）拿 8 位配对码
curl -X POST https://windgraham.art/v1/pair -H 'Content-Type: application/json' \
  -d '{"code":"<CODE>","device_name":"google-bridge"}'
# → device_token 写入 ~/.config/google-bridge/config.json 的 device_token 字段
python3 google_bridge.py register     # 幂等注册 google-gmail/calendar/drive/tasks 四通道，secret 自动写回 config
python3 google_bridge.py selftest     # HMAC/spool/全部动作形状(--dry-run)/服务器连通
```

config.json 模板（chmod 600）：

```json
{
  "api_base": "https://windgraham.art",
  "device_token": "<配对所得>",
  "device_name": "google-bridge",
  "gws_path": "gws",
  "gws_proxy": "http://127.0.0.1:7890",
  "gmail": {"enabled": true, "email": "you@gmail.com", "app_password": "xxxx xxxx xxxx xxxx"},
  "calendar": {"enabled": true, "poll_seconds": 60},
  "drive": {"enabled": true, "poll_seconds": 300},
  "tasks": {"enabled": true, "poll_seconds": 3600},
  "channels": {}
}
```

### 4. 常驻（systemd --user）

```bash
mkdir -p ~/.config/systemd/user ~/.local/bin
cp google_bridge.py ~/.local/bin/google-bridge && chmod +x ~/.local/bin/google-bridge
cp google-bridge.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now google-bridge
journalctl --user -u google-bridge -f
```

## 运维

| 事项 | 命令 |
|---|---|
| 单条指令调试 | `google-bridge exec gmail_search '{"query":"from:me"}'` |
| 合成测试事件 | `google-bridge send-test gmail`（走真实管道，会惊动核心） |
| 桥健康 | 控制台 Settings 或 `curl -H "Authorization: Bearer <token>" https://windgraham.art/v2/bridge/status` |
| 通道统计 | `GET /v1/channels/<id>`（事件计数、最近时间） |
| 日志 | `~/.local/state/google-bridge/bridge.log`（5MB×3 轮转，append-only） |
| 断网续传 | 发送失败自动落 spool.jsonl，恢复后自动补发 |

## 安全约定（与 SOUL.md 一致）

- 系统**不直接发邮件**：gmail 只起草稿（`google_gmail_draft`），发送由用户在 Gmail 里点。
- 破坏性操作（`gmail_trash` / `calendar_delete` / `calendar_update`）核心 agent 必须先征得用户确认。
- `google_raw`（裸 gws）是逃生门，仅 curated 工具覆盖不了时用，并要求 agent 说明理由。
- gws 凭证在 Linux 的加密密钥文件就在 `~/.config/gws/`（无系统钥匙串），本机按密码文件对待；该目录**不要**同步上云/进 git。

## VPS 侧接线（部署时已做，灾难恢复时照抄）

1. `hermes/config.yaml` 的 `mcp_servers` 加：
   ```yaml
   lifecore_google:
     command: /opt/lifecore/venv/bin/python
     args: [/opt/lifecore/LifeCore/services/google-bridge/google_mcp.py]
     env: {LC_DATA_DIR: /opt/lifecore/data, LC_GOOGLE_DEVICE: google-bridge}
   ```
2. SOUL.md 追加 `prompts/SOUL-google.md` 内容 → 重启 hermes-gateway（~8s，会话自动恢复）。
3. lifecore-server 需含 `/v2/commands/*` 与 `/v2/bridge/*` 端点（v1.1+）。

## 已知边界

- 联系人监听未做（小时级 syncToken，价值低，需要时照 drive 轮询加）。
- Gmail 分类过滤基于 X-GM-LABELS；自定义过滤器见 config `gmail.skip_labels`。
- Calendar 410 Gone 自动全量重同步；Drive pageToken 长期有效，重启不丢（游标在本地 state.db）。
