# hermes Dashboard（Web UI）接入记录 — 挂在 /console

> 2026-09-16。hermes 原生 Web UI 作为第四对话面上线，复用原 /console 路径（旧 lifecore 控制台移至 /lc）。

## 组件

- `hermes-dashboard.service`（系统级 systemd）：`hermes dashboard --port 9119 --skip-build`，
  环境 `HOME=/root HERMES_HOME=/root/.hermes`，token 在 `/etc/hermes-dashboard.env`（600，
  `HERMES_DASHBOARD_SESSION_TOKEN`）。首次启动自动 vite 构建 SPA 到 `hermes_cli/web_dist/`。
- 与 `hermes-gateway.service` 并存不冲突（独立进程，共享 state.db 会话）。

## nginx（443 server block 内，blog 文件）

```nginx
location = /console { return 302 /console/; }
location /console/ { proxy_pass http://127.0.0.1:9119/; proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";
    proxy_set_header Host 127.0.0.1:9119; proxy_set_header Origin http://127.0.0.1:9119;
    proxy_set_header X-Forwarded-Prefix /console;
    proxy_set_header Authorization "Bearer <HERMES_DASHBOARD_SESSION_TOKEN>";   # nginx 注入，绕过双重 Authorization 冲突
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; proxy_read_timeout 3600s;
    auth_basic "LifeCore"; auth_basic_user_file /etc/nginx/.htpasswd-lifecore; }
location = /lc { proxy_pass http://127.0.0.1:8790/console; auth_basic "LifeCore"; auth_basic_user_file /etc/nginx/.htpasswd-lifecore; }
```

## 关键坑（实测）

1. **SPA 支持路径前缀**：发 `X-Forwarded-Prefix: /console`，服务端重写 index.html 的
   `/assets/…` 绝对路径并注入 `window.__HERMES_BASE_PATH__`，无需重新构建。
2. **Host/Origin 校验**：dashboard 绑 127.0.0.1 时只认回环 Host/Origin → nginx 统一改写成
   `Host/Origin: 127.0.0.1:9119`（WS 升级请求同样过这道校验）。
3. **双重 Authorization 冲突**：basic auth 和 dashboard Bearer 共用 Authorization 头，
   客户端只能带一个 → 由 nginx 上行时强制注入 `Authorization: Bearer <token>`，
   对外只剩 basic auth 一道凭证（SPA 的 API/WS 调用经 nginx 自动获得授权）。
4. **ssh heredoc 变量陷阱**：`<<EOF`（不引用）会被远端 shell 展开吃掉 `$http_upgrade`
   等 nginx 变量；用 `<<'EOF'` 或 python 改写。
5. 假 WS 握手（curl 无 Sec-WebSocket-Key）会得到 404 `No such API endpoint`——属正常
   catch-all；真实握手实测 CONNECTED。

## 状态注入（所有对话面）

`config.yaml` 挂官方 shell hook：`hooks.pre_llm_call` →
`state_inject_hook.sh`（stdin JSON 守卫 `[live ` 前缀 → curl lifecore-server
`/v2/state-block` → stdout `{"context": …}` 注入用户消息尾部）。web UI/CLI/webhook/api
全部生效，与 BFF 注入双通道并存不重复（守卫互斥）。需 `hooks_auto_accept: true`。
