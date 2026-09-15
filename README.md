# LifeCore — 个人聚合 Agent 系统核心

> 本仓库是"个人聚合 Agent 系统"的实施仓库：Hermes 底座（裁剪快照）+ 自研对接层 + 设计文档。
> 设计文档见 [docs/](docs/)；上游 Hermes 完整仓库（含 tests/desktop/website 等被裁剪内容）：github.com/NousResearch/hermes-agent

## 结构

```
LifeCore/
├── hermes/              ← Hermes 最新快照（裁剪：去掉 tests/apps/ui-tui/website/evals 等重目录）
│                          修整原则：config 瘦身、不删核心代码；prompt 走配置层+动态注入
├── services/
│   ├── registrar/       ← 通道注册器（HTTP 薄壳包 hermes webhook subscribe 热加载机制）［待写］
│   ├── inbox/           ← 收件箱服务（CloudEvents 归一化 + since 增量同步 API）［待写］
│   └── pairing-dr/      ← 配对与灾难恢复 sidecar（QR/密钥对/待恢复模式）［待写］
├── contracts/
│   └── agent.md         ← 对外自描述契约（公开 URL 服务）［待写］
└── docs/                ← 设计文档（01 总纲 / 02 对接层设计记录 / 03 Hermes构造分析）
```

## 任务清单（详见 docs/02 §12）

- [ ] 任务 1：Hermes 构造分析（session/memory/database）→ prompt 配置化 + 动态注入
- [ ] 任务 2：三件套（agent.md / registrar / pairing-dr）
- [ ] 任务 3：身份-会话判别-断点续传与注册表统一（wrapper 层）
- [ ] 任务 4：手机 App + gateway hooks 扩展
