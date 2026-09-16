# Dashboard LifeCore 嵌入补丁

hermes 升级后重放（在服务器上）：
1. `python3 apply_lifecore_nav.py`（幂等锚点替换；锚点缺失会 assert 报错——说明 hermes 改了 App.tsx，需人工对照 App.tsx.lifecore.diff）
2. `cp LifeCorePage.tsx /usr/local/lib/hermes-agent/web/src/pages/`
3. `cd /usr/local/lib/hermes-agent/web && npm run build`
4. `systemctl restart hermes-dashboard`（dist 其实按请求读盘，不重启一般也生效）

嵌入链路：Dashboard 路由 /lc-* → LifeCorePage iframe → `/lc?embed=1#<view>`（console 去壳按 hash 渲染单视图）。
console.html 的 hash 路由与 embed 模式已入库（lifecore-server 热更新，无需补丁）。
