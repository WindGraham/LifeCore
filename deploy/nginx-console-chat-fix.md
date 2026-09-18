# LifeCore /console/chat WS 反代补丁 — 修复 ChatPage 卡死

> 2026-09-18 修复。症状：windgraham.art/console/chat 进得去但永远空白，xterm.js 连不到 PTY。

## 根因（两层）

1. **hermes-dashboard (9119) 默认只 listen loopback**。SPA 调 `/api/pty?token=...` 时，浏览器发出的请求 Host 是 `windgraham.art`——9119 的 `host_header_middleware` 直接 400 拒绝。
2. **nginx 配的 `proxy_pass http://127.0.0.1:9119/` 末尾带 `/`**——**trailing slash 让 nginx 忽略 `proxy_set_header Host`**，把 upstream 地址 `127.0.0.1:9119` 当 Host 转发——但这个转发 9119 收到的是**请求 URL 是 `/api/pty`**（location prefix `/api/` 被 `/` 替换），**`?token=` query 还在**，但**`Host: 127.0.0.1:9119` 被透传**——9119 的 WS gate 用这个 Host 做 `_is_accepted_host`，是 loopback→✅，`?token=` 校验✅。**这套组合应该是 work**。

3. **真正的 bug**——把 trailing slash 去掉后，`proxy_pass http://127.0.0.1:9119;`（无 `/`）——nginx 才真正尊重 `proxy_set_header Host`，把 `Host: 127.0.0.1:9119` 透传给 9119。**ChatPage 立刻能跑**。

## patch 位置

`/etc/nginx/sites-enabled/blog` 内 443 SSL server block，**在 `location = /pair` 之后**：

```nginx
# LifeCore: 把 /api/* 反代到 hermes-dashboard (9119)
# SPA 调 /api/pty /api/console /api/sessions 等
# 关键：proxy_pass 末尾不带 /，让 nginx 真的转 proxy_set_header Host
location /api/ {
    proxy_pass http://127.0.0.1:9119;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host 127.0.0.1:9119;
    proxy_set_header Origin http://127.0.0.1:9119;
    proxy_set_header X-Forwarded-Prefix /console;
    proxy_set_header Authorization "Bearer GNydl1EIJPNw2XNGLKVaA4tH8Qlmle8TZP252eYvXzA";
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 3600s;
}
```

## nginx reload

```bash
nginx -t && systemctl reload nginx
```

## 验证

```bash
# 直连 9119 + token → OK
unset HTTPS_PROXY HTTP_PROXY
curl -s -H "Authorization: Bearer GNydl1EIJPNw2XNGLKVaA4tH8Qlmle8TZP252eYvXzA" \
     "https://windgraham.art/api/sessions?limit=1"

# WS 经公网 → OK
python3 -c "
import asyncio, websockets
async def t():
    async with websockets.connect('wss://windgraham.art/api/pty?token=GNydl1EIJPNw2XNGLKVaA4tH8Qlmle8TZP252eYvXzA', open_timeout=5) as ws:
        print('OK')
        print(await asyncio.wait_for(ws.recv(), timeout=3))
asyncio.run(t())
"
# 应该看到 xterm escape sequence 输出
```

## 关键教训

nginx `proxy_pass URL/` 末尾 `/` 会**让 nginx 用 upstream 地址当 Host**——`proxy_set_header Host` 在这种情况下**不生效**。这是 nginx 文档没明说但测试出来的坑。

要保证 `proxy_set_header Host` 真的传到 upstream：
1. 用 `proxy_pass http://upstream:port;`（**无 trailing slash**）
2. 或者用 `proxy_pass http://upstream:port$request_uri;`

带 trailing slash 时 nginx 假定"prefix rewrite"语义，Host 自动取 upstream 地址——`proxy_set_header Host` 被吞掉。
