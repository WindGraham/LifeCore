#!/usr/bin/env python3
"""LifeCore 嵌入补丁：给 hermes Dashboard App.tsx 加 5 个 iframe 路由 + 导航组。"""
p = "/usr/local/lib/hermes-agent/web/src/App.tsx"
s = open(p).read()

old = 'const ChatPage = lazy(() => import("@/pages/ChatPage"));'
new = old + '\nconst LifeCorePage = lazy(() => import("@/pages/LifeCorePage"));'
assert old in s, "lazy anchor missing"
s = s.replace(old, new, 1)

old = '  "/docs": DocsPage,\n};'
new = ('  "/docs": DocsPage,\n'
       '  "/lc-chat": LifeCorePage,\n'
       '  "/lc-notify": LifeCorePage,\n'
       '  "/lc-channels": LifeCorePage,\n'
       '  "/lc-jobs": LifeCorePage,\n'
       '  "/lc-settings": LifeCorePage,\n};')
assert old in s, "routes anchor missing"
s = s.replace(old, new, 1)

old = 'const BUILTIN_NAV_REST: NavItem[] = [\n  {\n    path: "/sessions",'
new = ('const BUILTIN_NAV_REST: NavItem[] = [\n'
       '  { path: "/lc-chat", label: "LifeCore·对话", icon: MessageSquare },\n'
       '  { path: "/lc-notify", label: "LifeCore·通知裁决", icon: Zap },\n'
       '  { path: "/lc-channels", label: "LifeCore·通道注册", icon: Database },\n'
       '  { path: "/lc-jobs", label: "LifeCore·任务队列", icon: Clock },\n'
       '  { path: "/lc-settings", label: "LifeCore·设置配对", icon: Shield },\n'
       '  {\n    path: "/sessions",')
assert old in s, "nav anchor missing"
s = s.replace(old, new, 1)

open(p, "w").write(s)
print("App.tsx patched: 5 routes + nav group")
