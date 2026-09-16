# DSH（DeepSeek Harness）作为地方值守 Agent 可行性调研

> 2026-09-17 · 依据：contracts/edge-agent.md v0.1 逐条对照 + DSH v0.1.5-rc.2 源码实证
> （本机即有一个 DSH 常驻实例已连续承载数小时多轮自治任务，是"能长期值守"的活体证据）

## 结论：可能性高（~85%），且与选型原则天然吻合

用户选型原则要求 **vendor harness**（以能力为目标、不在地方部署 Hermes）。DSH 正是此类：
GUI/headless 双形态、多模型路由、持久会话、定时唤醒、外部事件、子代理——值守 agent 的
每一块拼图在 packages/ 里都有正式包，不是边角的半成品。

## 逐条对照（edge-agent.md → DSH 能力 → 评价）

| 契约要求 | DSH 对应 | 评价 |
|---|---|---|
| §1 常驻挂起循环 | `web` profile 长驻会话 + `schedule` 包（会话级持久 reminder：延时/绝对时间/**固定间隔**，到期以普通消息进入会话唤醒 agent；重启存活） | ✅ 几乎一比一实现"挂起→定时唤醒→巡逻一轮→再挂起" |
| §1 事件唤醒 | `webhook` 包：验签的提供者事件 → 程序化规则 → 新建/派发会话（自带 GitHub 签名适配器可作范本） | ✅（我方架构铁律是地方主动外拨，主链路用不上，作备选） |
| §1 降级 cron 冷启动 | `dsh --profile headless`：单任务持久会话跑完打印退出；配合 OS cron | ✅ 契约认可的降级形态 |
| §2 MEMORY.md 外置 | DSH 会话即文件系统工作区，MEMORY.md 就是普通文件；持久会话 + resume 自动续上下文 | ✅ 契约的"文件即记忆，harness 可换"设计直接落地 |
| §3 B 级上报 HMAC | agent 持有 bash/python，照抄 google-bridge 的 sign/post_event（本仓库现成实现） | ✅ 零新开发 |
| §4 回查四工具 | 两种落地：**(a) 反查模式**（推荐，今日已在 google-bridge 实证）：VPS 侧 MCP 入队 → 边缘轮询 `device_commands` → 执行 → 回执，agent 无感；(b) DSH 的 `mcp` 客户端包把回查工具挂给值守 agent 自用 | ✅ 管道现成 |
| §5 作业型值守 | `goal`（自治续轮）+ `workflow`/`subagent`（扇出）+ `jobs`（后台进程）+ 完成回调 POST | ✅ |
| §6 红线（approval） | `guard`/`sandbox`/`interaction` 包 + 工作流上 exec approval 经 notify 反馈闭环（VPS 用户回复 → 队列下发 → 边缘执行） | ✅ 需流程纪律，机制齐备 |
| 模型路由 | `llm-pi-ai`：pi-ai 目录 / **OpenAI 兼容网关** / 自托管，声明式自定义路由，改配置即生效 | ✅ MiniMax（OpenAI/Anthropic 兼容线）可直接接入；巡逻轮可用便宜模型，摘要轮用好模型 |

## 形态建议（若采用）

```
寝室 PC：systemd user 服务跑 dsh --profile web（或桌面自启）
  └─ 持久会话 "wechat-watch"（webhook 平台自治产出的会话同样可用）
       ├─ 启动读 MEMORY.md + 游标
       ├─ schedule 固定间隔唤醒（活跃期 60s，静默期拉长）
       ├─ 巡逻：规则预筛（纯脚本）→ 命中才调 LLM 摘要
       ├─ HMAC V2 上报 VPS /hk/<ch>（复用 google_bridge.py 的 signer）
       ├─ MEMORY.md 追加 + 游标推进
       └─ 回查/approval：轮询 VPS /v2/commands/pending（复用 device_commands 管道）
```

## 风险与对策（诚实清单）

1. **软件年轻**（v0.1.5-rc.2，schedule/webhook 属新子系统）：升级可能改行为。
   → 契约本就要求记忆外置 + 标准协议（HMAC/HTTP/MCP/MEMORY.md），换 harness 成本低——这是设计红利。
2. **常驻资源占用**高于裸脚本（node 运行时 + 热会话）。
   → 无脑轮询源（如 drive delta）继续用纯脚本；**判断密集**的源（微信摘要、人物关系、
   教训飞轮）才是 DSH 的甜区——正好是最有价值的场景。
3. **harness 自身崩溃**：schedule 记录持久化、会话持久化，systemd Restart=always 兜底重启；
   首次实测前建议跑一周观察唤醒可靠性。
4. **成本**：每次唤醒 = ≥1 次 LLM 调用（MEMORY.md 全文在上下文）。MiniMax 非流式 + 尾部注入
   缓存友好（实测输入缓存命中率 97%）；静默期拉长 + 规则预筛可压到可忽略。
5. **回查模式 (a) 的执行体是脚本不是 DSH agent**——这是特性不是缺陷：回查要确定性，
   摘要是 LLM 的活，两者分开正好。

## 结论

DSH 直接作为符合 edge-agent.md 的地方值守 agent：**可行，且是判断密集类值守源的最优解**。
建议首个试点：微信摘要值守（MEMORY.md 的人物关系/教训段与它的工作方式完全同构），
上报/回查/approval 全部复用今日已实证的 google-bridge 管道，新增工作量约一个
值守 prompt + schedule 配置 + systemd 单元。

## 补充对照（用户追加的五条理想特性，2026-09-17 二轮验证）

| 理想特性 | DSH 实证 | 判定 |
|---|---|---|
| 核心多次发信、地方有记忆 | 会话即持久对话：队列可无限下发指令（FIFO 逐条进会话）；跨会话引用是正式能力（context 包：其他会话可作为有界快照进入上下文）；记忆=持久会话+MEMORY.md+memory MCP（config/examples/mcp-memory 叠层） | ✅ |
| 自己分布 subagent | subagent 包：进程内新子代理 / **带历史种子的子代理** / **出进程子代理（ACP / Codex / Claude Code / 另一套 Harness）**；模型可给邻接 agent 发消息、中断、列状态 | ✅ 且能跨 harness 派发 |
| 自动 compact | compaction 包：**token 压力升高自动压缩历史（dsh-base 默认启用）** + /compact 手动 + 超大工具输出先裁剪 | ✅ 默认自动 |
| 设定定时任务 | schedule 包：agent 自建延时/定点/间隔 reminder，持久化、重启存活 | ✅ |

**二轮结论**：这五条里最重的"自动 compact + 自主派 subagent"在 DSH 里是默认开启/正式包的级别，
不是边角功能——**可能性上调至 ~90%**。剩余风险仍是软件年轻度（schedule/subagent 交互建议
先一周试点），而非能力缺口。
