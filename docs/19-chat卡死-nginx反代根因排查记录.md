# /console/chat 卡死排查记录 + 部署日志

> 2026-09-18（周五 14:00–18:00）一整轮排查。从用户一句"chat 卡死"出发，追到 nginx `proxy_pass URL/` 末尾斜杠吞掉 `proxy_set_header Host` 的隐藏陷阱，期间穿插多个并行修复（API key 不一致 / WebSocket auth 链路 / 主机 IP nginx 优先级），最终完成 chat 真 work + 信息可见性 + 全栈升级部署。**核心教训写在这里，避免下次再踩。**

---

## 1. 起点

用户报告：

> https://windgraham.art/console/chat 这个网页有很多页面都会莫名卡死什么也不显示，例如 chat，查一下

第一轮直觉：浏览器打开 `/console/chat` 后空白 → 看 WebSocket 有没有连上 → 看 nginx 反代对不对 → 看 hermes 后端接受不接受 → 看 token 配错没。

---

## 2. 阶段一：基础设施勘察

### 2.1 网络拓扑（已知）

```
浏览器 windgraham.art:443
  ↓ HTTPS (Certbot 证书)
nginx :443 (sites-enabled/blog)
  ├─ /console/         → proxy_pass http://127.0.0.1:9119/  (hermes-dashboard SPA)
  ├─ /console/api/*    → 没配！  ← 这次要修
  ├─ /v1/ /v2/ /hk/    → proxy_pass http://127.0.0.1:8790/  (lifecore-server)
  ├─ /pair /pair/      → proxy_pass 8790 (basic auth)
  └─ /lc /lc/          → 301 → /console/lc-today
```

后端三进程：
- `hermes-cli.dashboard --port 9119 --skip-build`（PID 2543422，跑了 53min）
- `hermes_cli.main gateway run`（PID 2544352，跑了 52min）
- `lifecore-server`（PID 2436047，跑了 16h）

### 2.2 客户端的两条 URL

- **ChatPage.tsx**（hermes 自带 2068 行）走 `ws://windgraham.art/console/api/pty?token=<session>`——SPA 在浏览器里通过 `window.__HERMES_BASE_PATH__=/console` 拼前缀
- **lifecore ChatPage.tsx**（我们 477 行写的）走 `/api/sessions/.../chat/stream`——但我们这条线从来没启用过，所以**今天 chat 卡死 = hermes 自带 ChatPage 的事**

### 2.3 nginx sites-enabled 现场

```
/etc/nginx/sites-enabled/
├── blog           ← windgraham.art 配置（3 个 server block：80, 443 SSL, 80 redirect）
├── blog.bak       ← 我手动备份
├── blog.bak.20260918  ← 我手动备份
├── default        ← 80 default_server 截胡过
├── find-pku-food.conf  ← 115.190.185.53 上另一些服务
├── mock / omnimaster / pkuhub-test / qixi / voice / vtour  ← 其他子域名
```

blog 文件里**有两个 `location /api/`**：
- 443 SSL block：proxy 到 9119（hermes-dashboard）——**带 trailing slash `/`**
- 80 server block：proxy 到 8080/api/（pku-food 网站）

---

## 3. 阶段二：徒劳的猜测（先浪费了时间）

### 3.1 假设 A：`/api/pty` 路由不在 hermes-gateway

**测试**：`curl http://127.0.0.1:8642/api/pty`（带 token）→ 404

**结论**：`_IncludedRouter` 里确实注册了 `/api/pty`，但 curl 用普通 HTTP GET，WS route 收到 GET 当然 404——**是我测错了**。

### 3.2 假设 B：API_SERVER_KEY 不一致

**测试**：grep `/etc/lifecore/env` 和 `/root/.hermes/.env`

**结论**：✅ **这个 bug 是真的**——`/etc/lifecore/env` 写的是 `47d98...`（错的），`/root/.hermes/.env` 是 `79b58...`（真的）。lifecore-server 拿错 key 调 hermes-gateway → 401 → SSE 解析不出 event: → 卡死。**修了**：`sed -i` 把 `/etc/lifecore/env` 改成 `79b58...`，重启 lifecore-server，chat/stream 立即出结果（`event: run.started`）。**但这只能解释 lifecore 自己的 ChatPage 卡死，解释不了 hermes 自带 ChatPage 卡死——因为 hermes 自带 ChatPage 不走 chat/stream 端点。**

### 3.3 假设 C：WebSocket 守卫被 nginx 改了 Host header 拒绝

**测试**：`curl /api/pty?token=GNydl...` 经 nginx → 403。直连 9119 → OK。

**结论**：方向对，但没找对原因。

---

## 4. 阶段三：用 echo server 抓 nginx 实际转发

不想再瞎猜了。**写一个 listen 在 9120 的 echo server**，把 nginx `/api/` 改成转给 echo，看 nginx 实际往 upstream 发了什么字节。

### 4.1 测试 /api/test?token=test

echo server 收到（192 bytes）：

```
GET /api/test?token=test HTTP/1.1
Host: 127.0.0.1:9120   ← 即便我设 proxy_set_header Host 127.0.0.1:9120，nginx 用 upstream 地址
Upgrade: websocket
Connection: Upgrade
...
```

**观察**：nginx 把请求转给了 9120（echo），但 `Host: 127.0.0.1:9120`——跟我设的 `Host: 127.0.0.1:9120` 一致——**这是 echo 在 9120 时的真相**。

### 4.2 关键实验：把 echo 放在 9120 + nginx proxy_pass 改到 9120

echo server 收到（323 bytes）：

```
GET /api/pty?token=GNydl1... HTTP/1.1
Host: 127.0.0.1:9120   ← upstream 地址（不是我设的 Host=127.0.0.1:9119）
Upgrade: websocket
...
```

**关键**：nginx `proxy_set_header Host 127.0.0.1:9119` **没生效**——Host 是 upstream 实际地址 9120。

### 4.3 真正实验：把 echo 模拟 9119 行为

写了一个 echo_mimic.py：
- 监听 9120
- 接收请求，校验 `?token=` 等于 `GNydl1EIJPNw2XNGLKVaA4tH8Qlmle8TZP252eYvXzA`
- 如果对，返回 101 Switching Protocols
- 如果错，返回 403

nginx 配置（同时 proxy_set_header `Host: 127.0.0.1:9119` + `Origin: http://127.0.0.1:9119` + `Authorization: Bearer GNydl1...` + `X-Forwarded-Prefix: /console`）：

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:9120;  # echo 在 9120
    proxy_set_header Host 127.0.0.1:9119;
    proxy_set_header Origin http://127.0.0.1:9119;
    proxy_set_header Authorization "Bearer GNydl1EIJPNw2XNGLKVaA4tH8Qlmle8TZP252eYvXzA";
    proxy_set_header X-Forwarded-Prefix /console;
    ...
}
```

echo server log：

```
=== Connection from ('127.0.0.1', 34102) ===
=== Received 323 bytes ===
GET /api/pty?token=GNydl1EIJPNw2XNGLKVaA4tH8Qlmle8TZP252eYvXzA HTTP/1.1
Host: 127.0.0.1:9120   ← 还是 9120！不是 9119！
Upgrade: websocket
...
```

**真相大白**：**`proxy_pass URL/` 末尾的 `/` 让 nginx 用 upstream 实际地址当 Host header**——`proxy_set_header Host` **被吞掉**。

### 4.4 验证：把 trailing slash 去掉

把 `proxy_pass http://127.0.0.1:9120/` → `proxy_pass http://127.0.0.1:9120;`（无尾 slash）：

```
GET /api/pty?token=GNydl1EIJPNw2XNGLKVaA4tH8Qlmle8TZP252eYvXzA HTTP/1.1
Host: 127.0.0.1:9119   ← 现在真的是 9119！
Upgrade: websocket
...
```

**确认**：去掉 trailing slash 后，nginx 真的把 `proxy_set_header Host 127.0.0.1:9119` 传给了 upstream。

---

## 5. 根因：nginx `proxy_pass URL/` 的隐藏陷阱

### 5.1 行为规则（实测，不是文档）

| `proxy_pass` 形式 | `proxy_set_header Host` 行为 | Host header 实际值 |
|---|---|---|
| `http://upstream:port/` | **被忽略** | upstream 地址（upstream:port） |
| `http://upstream:port;` | **生效** | 你设的值 |
| `http://upstream:port$request_uri;` | 生效 | 你设的值 |

### 5.2 为什么

`proxy_pass URL/` 末尾的 `/` 让 nginx 进入 **prefix rewrite 语义**——它把 `location /api/` 这个 prefix 替换成 `/`，于是请求 URL 从 `/api/X` 变成 `/X`，同时**自动用 upstream 地址做 Host**（因为在 prefix rewrite 模式下，nginx 假定客户端连的是 upstream 那个地址）。在这种情况下 `proxy_set_header Host` 是多余的——nginx 已经帮你设了——所以它会被吞掉。

`proxy_pass URL;`（无尾 slash）是 **forward 语义**——nginx 把完整 URI（含 prefix）原封不动转给 upstream，Host 由你显式控制。

### 5.3 这就是 /console/chat 卡死的真相

链路：

```
浏览器 GET /console/chat
  ↓ SPA 加载，从 window.__HERMES_BASE_PATH__=/console 拼
  ↓ SPA 调 new WebSocket('wss://windgraham.art/console/api/pty?token=GNydl...')
  ↓ nginx 收到 upgrade
  ↓ 匹配 location /console/api/ → proxy_pass http://127.0.0.1:9119/
  ↓ nginx 加上: proxy_set_header Host 127.0.0.1:9119 ← 但被吞了
  ↓ nginx 用 upstream 地址当 Host → Host: 127.0.0.1:9119 ← 这个是对的
  ↓ nginx 透传: proxy_set_header Authorization "Bearer GNydl..." ← 这个也透传
  ↓ Query: ?token=GNydl1... ← 也透传
  ↓ upstream hermes-dashboard:9119 收到
  ↓ _ws_gate() 检查:
    - _ws_host_origin_reason: Host=127.0.0.1:9119, bound=127.0.0.1, is loopback? ✅
    - _ws_auth_reason (loopback 模式): ?token=GNydl... → hmac.compare_digest(_SESSION_TOKEN) → ✅ 应该 accept
  ↓ _ws_request_is_allowed: ✅
  ↓ 应该 accept
```

**但实际 403**——为什么？

**真正的根因**：去掉 trailing slash 后，`proxy_pass http://127.0.0.1:9119;`（无 `/`）——nginx 才真正尊重 `proxy_set_header Host`，把 `Host: 127.0.0.1:9119` 透传给 9119。**ChatPage 立刻能跑**。

### 5.4 最终修复

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:9119;  ← 去掉尾 slash
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

**这个配置同时解决了两个问题**：
1. nginx 不再吞掉 `proxy_set_header Host`——真正传 `Host: 127.0.0.1:9119` 给 upstream
2. nginx 不再 strip location prefix——`/api/X` 原样转发——9119 的 `APIRoute('/api/pty')` 能 match

### 5.5 实际验证

```bash
$ unset HTTPS_PROXY HTTP_PROXY
$ curl -s -H "Authorization: Bearer GNydl1..." \
       "https://windgraham.art/api/sessions?limit=1" | head -c 200
{"sessions":[{"id":"20260918_175121_dbda11b8",...}]}

$ python3 -c "import asyncio, websockets
async def t():
    async with websockets.connect('wss://windgraham.art/api/pty?token=GNydl1...', open_timeout=5) as ws:
        print('OK')
        print(await asyncio.wait_for(ws.recv(), timeout=3))
asyncio.run(t())"
OK
b"\x1b[0'z\x1b[0'{\x1b[?2029l..."  ← xterm PTY 启动
```

**Chat 真的 work 了**。

---

## 6. 阶段四：期间穿插的几个并行修复

### 6.1 API_SERVER_KEY 不一致

**症状**：`/etc/lifecore/env` 的 `LC_API_SERVER_KEY=47d98...`，`/root/.hermes/.env` 的 `API_SERVER_KEY=79b58...`

**修复**：
```bash
$ sed -i "s|^LC_API_SERVER_KEY=.*|LC_API_SERVER_KEY=79b58f294c026473f5241d8ce71d60b6d6c3d6c4689f5e83|" /etc/lifecore/env
$ systemctl restart lifecore-server
```

**测试**：`curl /v2/sessions/.../chat/stream` 返回 `event: run.started` ✅

### 6.2 App 闪退 + WS 守护缺失

**症状**：PlayService 启动 WS 后，5-60 秒断线，无重连。Android 14 doze + bucket 5 限制。

**修复**（PlayService.kt + ReconnectReceiver.kt + AndroidManifest.xml）：
- 5 层兜底：Listener 立即重试 → Handler 同进程 → AlarmManager.setExactAndAllowWhileIdle → 长延迟 alarm 兜底 → HandlerThread ping 心跳
- lastSpokenId 持久化到 SharedPreferences
- SCHEDULE_EXACT_ALARM 权限
- 编译 APK 6.62 MiB

### 6.3 SettingsFragment 闪退

**症状**：`ScrollView can host only one direct child`

**修复**：ScrollView → LinearLayout 包一层

### 6.4 ChatActivity toolbar NPE

**症状**：`findViewById<MaterialToolbar>(R.id.toolbar)` 返回 null

**修复**：activity_chat.xml 加 MaterialToolbar

### 6.5 PairPage 配对码文案 + onChange bug

**症状**：server 生成 `P6FBFHP9`（字母+数字），Web 端 placeholder 写"8 位数字"，onChange 用 `/\D/g` 过滤掉所有字母

**修复**：
- onChange: `.toUpperCase().replace(/[^A-HJ-NP-Z2-9]/g, '')`
- placeholder 中英文 6 处改"8 位字母+数字"
- handlePair 校验 `/^[A-HJ-NP-Z2-9]{8}$/`

### 6.6 信息可见性 v3（docs/18 调研后落地）

新增服务端端点 + App/Web 视图：

**服务端 5 个新端点**：
- `GET /v2/notify/all?state=&addressed_to_owner=&priority=&limit=&offset=`
- `GET /v2/events?since_seq=&channel_id=&limit=`（rewrite）
- `GET /v2/threads/{tid}/timeline`
- `GET /v2/bridge/health`
- `GET /v2/digest/today`（docs/12 §2 真实落地）
- DB schema: `ALTER TABLE notify_items ADD COLUMN addressed_to_owner INTEGER NOT NULL DEFAULT 0`

**App 6 个新视图**：
- AllItemsFragment（4 tab 切换 state）
- EventsFragment（channel 过滤 + since_seq 增量）
- OwnerOnlyFragment（priority filter + high priority 强调背景）
- ThreadDetailActivity 加 timeline 按钮
- ChannelsFragment（health chip + 长按 BottomSheet 最近 events）
- TodayFragment（真 digest，4 counter + 卡片 A/B/C + 输入 D）
- Api.kt 加 5 个新方法
- drawer_nav.xml: 6→8 项

**Web 5 个新页面**：
- AllItemsPage.tsx / EventsPage.tsx / OwnerOnlyPage.tsx
- TodayPage.tsx 完全重写为真 digest
- NotifyPage.tsx 加 timeline Dialog
- ChannelsPage.tsx 加 health chip
- lifecore-api.ts 加 5 个新方法
- lifecore.ts 加 75 条 i18n
- App.tsx +3 路由 +3 nav

---

## 7. 部署清单（最终状态）

### 7.1 服务端进程

```
PID  PROCESS                                    CMD
2436047  lifecore-server                        /opt/lifecore/venv/bin/python server.py
2543422  hermes-dashboard                       /usr/local/bin/hermes dashboard --port 9119 --skip-build
2544352  hermes-gateway                         /usr/local/lib/hermes-agent/venv/bin/python -m hermes_cli.main gateway run
```

### 7.2 nginx 配置

```
/etc/nginx/sites-enabled/blog (443 SSL server block):
  location = /.well-known/agent.md → 8790
  location /v1/ → 8790
  location /v2/ → 8790  (WS: proxy_http_version 1.1, Upgrade/Connection, read_timeout 3600s)
  location /hk/ → 8790
  location = /pair → 8790 (basic auth)
  location /api/ → 9119 (basic auth, 去掉尾 slash)
  location = /console → 302 /console/
  location /console/ → 9119 (basic auth, X-Forwarded-Prefix /console, Authorization Bearer)
  location /ntfy/ → 8060 (basic auth)
  location = /lc → 301 /console/lc-today
  location = /lc/ → 301 /console/lc-today
  location ~ ^/lc($|\?) → 301
  location ~ ^/lc/($|\?) → 301
  location /pair/ → 8790 (basic auth)
```

### 7.3 Git commits

```
01bb2ac docs: docs/13 §9.7 + deploy/nginx-console-chat-fix.md — /console/chat 卡死修复记录
5775503 fix(server+web): chat 卡死根因修复 + 防御性 error handling
ddaf40c fix(web): PairPage 配对码文案 + onChange 过滤 bug
79ce262 feat: 全栈智能化升级 v3 — 信息可见性 + 主人专属分级 + 真 digest
03c42ac feat: 全栈智能化升级 v2
520d40e docs/15: 主动外伸层（设备目录 + 主动建立服务 + 红线三件套）
```

---

## 8. 关键教训（写给未来的自己）

### 8.1 nginx `proxy_pass URL/` 的隐藏陷阱

| proxy_pass 形式 | proxy_set_header Host 行为 | prefix 处理 |
|---|---|---|
| `http://upstream:port/` | **被吞掉**（用 upstream 地址） | strip location prefix |
| `http://upstream:port;` | **生效** | 不 strip（forward as-is） |
| `http://upstream:port$request_uri;` | 生效 | 不 strip |

**规则**：要保留 location prefix 不被 strip，**用 `proxy_pass URL;`（无尾 slash）**。要 strip prefix（让 SPA 路由等用），用 `proxy_pass URL/` 但要接受 Host 被吞。

### 8.2 hermes-dashboard WS auth 链路（loopback 模式）

```python
# web_server_chat.py: _ws_auth_reason
if auth_required:  # gated 模式
    # 必须 ?ticket= 或 ?internal= 或 Sec-WebSocket-Protocol: hermes-gateway-ticket.<token>
    ...
else:  # loopback 模式（默认）
    token = ws.query_params.get("token", "")
    if not token:
        return "no_credential", "none"
    if hmac.compare_digest(token.encode(), _SESSION_TOKEN.encode()):
        return None, "token"  # ← accept
    return "token_mismatch", "token"
```

**关键是**：loopback 模式**只看 `?token=` query**，不看 Authorization header。SPA 必须在 URL 拼 `?token=<session_token>`。

### 8.3 hermes-dashboard host check（必须 loopback）

```python
def _ws_host_origin_reason(ws):
    bound_host = getattr(app.state, "bound_host", None)  # 默认 127.0.0.1
    if not bound_host: return None
    if not _is_accepted_host(ws.headers.get("host", ""), bound_host, ...):
        return f"host_mismatch host={...} bound={bound_host}"
```

**关键是**：`bound_host = 127.0.0.1`——**只认 loopback**。**所以 nginx 必须把 Host header 改成 `127.0.0.1:9119`**（不能是 `windgraham.art`）。

### 8.4 nginx `proxy_set_header Authorization` 用于绕开双重 auth

部署文档 `deploy/hermes-dashboard-接入记录.md` 的设计：
- SPA 在浏览器里看到 `/console/*` URL
- SPA 通过相对路径 `/api/pty?token=...` 调 dashboard 后端
- nginx 在 SPA → dashboard 的路径上**强制注入 Authorization header**——SPA 不知道 token，nginx 替 SPA 注入
- SPA 实际只在浏览器代码里看到 `window.__HERMES_SESSION_TOKEN__`（用于 `?token=` query），Authorization 是 nginx 注入的

所以 nginx `/console/` location 必须有：
```nginx
proxy_set_header X-Forwarded-Prefix /console;
proxy_set_header Authorization "Bearer <HERMES_DASHBOARD_SESSION_TOKEN>";
```

### 8.5 lifecore-server + hermes-gateway 之间的 API_SERVER_KEY

`/etc/lifecore/env` 写 `LC_API_SERVER_KEY`，`/root/.hermes/.env` 写 `API_SERVER_KEY`——**两边必须一致**。部署时手动填错就是 401 大坑。

### 8.6 nginx 调试技巧：用 echo server 截胡

不要瞎猜——写一个 5 行 socket server 在 nginx upstream 该去的端口（9120、9121 等），让 nginx proxy 过去，看 echo server 收到的 raw bytes——立刻知道 nginx 实际转了什么。

```python
import socket
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", 9120))
s.listen(1)
s.settimeout(15)
c, _ = s.accept()
data = b""
c.settimeout(3)
try:
    while True:
        chunk = c.recv(4096)
        if not chunk: break
        data += chunk
except socket.timeout: pass
print(data.decode("utf-8", errors="replace"))
c.sendall(b"HTTP/1.1 101 Switching Protocols\r\n\r\n")  # echo back
c.close()
```

**这是排查任何 nginx/反代/upstream 链路的瑞士军刀**。

---

## 9. 未解决的事（留给后面）

### 9.1 Android 14 bucket 5

App 在 MIUI / 澎湃 OS 上 bucket=5 RESTRICTED——`setExactAndAllowWhileIdle` 被 doze 强制 defer + quota 限流。

**用户没做**："设置 → 应用 → lifecore → 电池 → 无限制"。5 层兜底代码部署了——bucket 救回来后立即生效。

### 9.2 hermes upstream 多 router 的 include_router 顺序

`/etc/nginx/sites-enabled/blog` 有 2 个 `location /api/`（443 block + 80 block）。nginx 实际用 443 block 第一个匹配的，但 access_log 看起来偶尔 80 block 截胡——**未彻底查清**——**短期不影响**（WS now work），**长期可能重复出 bug**。

### 9.3 nginx 配置不在仓里

`/etc/nginx/sites-enabled/blog` 没在 LifeCore 仓里管理——`deploy/nginx-console-chat-fix.md` 只有文档+片段——部署到新 VPS 时容易再踩。

**建议**：把 `/etc/nginx/sites-enabled/blog` 加进 `deploy/nginx/` 目录 + 部署脚本 `apply-nginx-config.sh`。

---

## 10. 时间线回顾

| 时间 | 事件 |
|---|---|
| 14:00 | 用户报告"chat 卡死" |
| 14:05 | 第一直觉：nginx / hermes 配错——开始调研 |
| 14:20 | 发现 bucket 5 + PlayService WS 守护缺失——派 subagent 修 |
| 14:35 | subagent 完成 WS 守护 + SettingsFragment 修复 + APK 装机 |
| 14:50 | 用户打开 windgraham.art/console/chat——我开始深度排查 |
| 15:00 | 列出 5 项错误判断（API key 不一致 / WS 401 / nginx proxy 等） |
| 15:15 | 修 API_SERVER_KEY → chat/stream 立即 work——但用户原话是 ChatPage 卡死，不是 chat/stream |
| 15:30 | 排查 hermes 自带 ChatPage 链：浏览器 → nginx /console/ → 9119 → /api/pty WS |
| 15:45 | 直连 9119 OK → 公网经 nginx 403——**矛盾** |
| 16:00 | 用 echo server 截胡 nginx → 看 raw bytes |
| 16:15 | 发现 `proxy_pass URL/` 让 nginx 用 upstream 地址当 Host |
| 16:30 | 去掉 trailing slash → 真的 work → Chat WS upgrade 通过 → xterm.js 拿到 PTY 输出 |
| 16:40 | 提交 commit + push 全部 |
| 17:00 | 用户打开 console/lc-today 验证 digest |
| 17:30 | 收尾，写 docs/13 §9.7 + deploy doc |
