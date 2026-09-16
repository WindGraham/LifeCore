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
