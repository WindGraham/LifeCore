# lifecore-server

LifeCore 外围单进程（终审拓扑单元 4+2+3 合并部署）：pairing+BFF / registrar / inbox+notify / core_adapter。
- 设计依据：docs/02 §3/§13/§14、contracts/agent.md、docs/06 定型决议
- 数据源：单一 SQLite（/opt/lifecore/data/lifecore.db）；无状态门面原则
- Hermes 交互：仅 `hermes webhook subscribe/remove` CLI + 回环 HTTP 转发（零修改）

## 运行
```bash
python3 -m venv /opt/lifecore/venv
/opt/lifecore/venv/bin/pip install -r requirements.txt
LC_DATA_DIR=/opt/lifecore/data LC_PUBLIC_BASE=https://your-domain \
  /opt/lifecore/venv/bin/python server.py
```

## 端点
- 公开：`GET /health` `GET /pair`（二维码配对页）`GET /pair/code` `GET /pair/qr?code=` `POST /v1/pair` `GET /.well-known/agent.md`
- 设备凭证（Bearer）：`POST /v1/channels` `GET/DELETE /v1/channels/{id}` `POST /v1/test-event` `GET /v2/notify/active` `POST /v2/notify/items/{id}/feedback` `GET /v2/events?since=` `GET /v2/me`
- 通道 HMAC：`POST /hk/{channel_id}`（X-Webhook-Signature-V2 / X-Webhook-Timestamp，±300s）

## 保活
systemd 单元见 deploy/lifecore-server.service（Restart=always）。
