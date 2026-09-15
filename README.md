# 个人聚合 Agent 系统 · 工作区索引

> 本工作区记录"个人聚合 Agent 系统"从调研、设计到实施的全过程。
> 当前阶段：**设计定稿 + Hermes 底座选型已定，进入实施**。

## 文档导航

| 文件 | 内容 | 状态 |
|---|---|---|
| [docs/01-架构调研与设计文档.md](docs/01-架构调研与设计文档.md) | 原始总纲：愿景、协议选型（CloudEvents/MCP/A2A）、Node-RED 网关、部署、路线图 | 调研完成，设计定稿 |
| [docs/02-核心VPS对接层设计记录.md](docs/02-核心VPS对接层设计记录.md) | 增补篇：agent.md 自描述契约、通道注册协议、配对/灾难恢复、任务清单（§12） | 讨论定稿，随实施滚动更新 |
| [docs/03-Hermes构造分析.md](docs/03-Hermes构造分析.md) | Hermes 底座分析：agent/session/memory/database 构造 + 该碰/不该碰边界 | 进行中 |

## 当前架构决策（截至 2026-09-16）

1. **底座**：Hermes Agent 原样运行（黑盒），config 瘦身，不 fork 不删代码；
2. **增量全在四个自研服务**：agent.md 契约 + channels-registrar（HTTP 薄壳包 `hermes webhook subscribe` 热加载机制）+ inbox 服务 + pairing/DR sidecar；
3. **手机 App** 自研，靠 gateway hooks/插件对接，核心补丁为最后手段；
4. prompt 定制走配置层 + 动态注入（工具优先、瞬时注入兜底），不动 turn loop。

## 任务清单（详见 docs/02 §12）

- [ ] 任务 1：Hermes 构造分析 → prompt 配置化定制 → 外围分级策略层
- [ ] 任务 2：三件套（agent.md / registrar / pairing-DR）
- [ ] 任务 3：身份-会话判别-断点续传与注册表统一（wrapper 层）
- [ ] 任务 4：手机 App + hooks 扩展

## 外部资源

- Hermes 源码浅克隆：`/tmp/hermes-agent`（调查用，随时可重新 clone）
