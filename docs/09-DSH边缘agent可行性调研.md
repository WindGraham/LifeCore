# DSH（DeepSeek Harness）作为地方值守 Agent 全面调研

> 版本：v1.0（2026-09-17）· 三轮验证合并：契约对照 + 理想特性对照 + 调用/记忆机制源码级确证
> 依据：contracts/edge-agent.md v0.1 逐条对照 + DSH v0.1.5-rc.2 源码实证
> （本机即有一个 DSH 常驻实例已连续承载数小时多轮自治任务，是"能长期值守"的活体证据）

## 0. 总结论

**DSH 直接作为符合 edge-agent.md 的地方值守 agent：可行性 ~90%。**
选型原则要求 vendor harness（以能力为目标、不在地方部署 Hermes）——DSH 正是此类。
剩余的 10% 不是能力缺口，是软件年轻度（v0.1.5-rc.2），需一周试点验证清单（§6）兜底。

## 1. 契约逐条对照（edge-agent.md v0.1）

| 契约要求 | DSH 对应 | 评价 |
|---|---|---|
| §1 常驻挂起循环 | `web` profile 长驻会话 + `schedule` 包（会话级持久 reminder：延时/绝对时间/**固定间隔**，到期以普通消息进入会话唤醒 agent；重启存活） | ✅ 几乎一比一实现"挂起→唤醒→巡逻→再挂起" |
| §1 事件唤醒 | `webhook` 包：验签的提供者事件 → 程序化规则 → 新建/派发会话（自带 GitHub 签名适配器可作范本） | ✅ 备选（我方铁律是地方主动外拨，主链路用不上） |
| §1 降级 cron 冷启动 | `dsh --profile headless`：单任务持久会话跑完打印退出，配合 OS cron | ✅ 契约认可的降级形态 |
| §2 MEMORY.md 外置 | DSH 会话即文件系统工作区，MEMORY.md 是普通文件；持久会话 + resume 自动续上下文 | ✅ "文件即记忆，harness 可换"直接落地 |
| §3 B 级上报 HMAC | agent 持有 bash/python，照抄 google-bridge 的 sign/post_event（本仓库现成实现） | ✅ 零新开发 |
| §4 回查四工具 | **反查模式**（推荐，已在 google-bridge 实证）：VPS 侧 MCP 入队 → 边缘轮询 `device_commands` → 执行 → 回执；另有 `mcp` 客户端包可把工具挂给值守 agent 自用 | ✅ 管道现成 |
| §5 作业型值守 | `goal`（自治续轮，跨重启存活）+ `workflow`/`subagent`（扇出）+ `jobs`（后台进程）+ 完成回调 POST | ✅ |
| §6 红线（approval） | `guard`/`sandbox`/`interaction` 包 + exec approval 走 notify 反馈闭环（VPS 用户回复 → 队列下发 → 边缘执行） | ✅ 机制齐备，需流程纪律 |
| 模型路由 | `llm-pi-ai`：pi-ai 目录 / **OpenAI 兼容网关** / 自托管，声明式自定义路由，改配置即生效 | ✅ MiniMax 直接接入；巡逻轮便宜模型、摘要轮好模型 |

## 2. 理想特性对照（用户追加五条，全部命中）

| 理想特性 | DSH 实证 | 分量 |
|---|---|---|
| 核心多次发信、地方有记忆 | 会话天然持久多轮；队列无限下发（FIFO 逐条进会话）；跨会话引用是正式能力（context 包：其他会话作为有界快照进入上下文）；记忆=持久会话+MEMORY.md+官方 memory MCP 叠层（config/examples/mcp-memory） | ✅ |
| 自己分布 subagent | subagent 包：进程内子代理 / **带历史种子的子代理** / **出进程子代理（ACP / Codex / Claude Code / 另一套 Harness）**；模型可给邻接 agent 发消息、中断、列状态 | ✅ 硬通货，跨 harness 派发 |
| 自动 compact | compaction 包：**token 压力升高自动压缩历史（dsh-base 默认启用）** + /compact 手动 + 超大工具输出先裁剪 | ✅ 默认自动 |
| 设定定时任务 | schedule 包：agent 自建延时/定点/间隔 reminder，持久化、重启存活 | ✅ |
| 核心一条命令调用 + 选择会话记忆 | 见 §3（ACP） | ✅ |

## 3. 调用机制源码级确证（三轮验证的关键修正）

### 3.1 修正：headless 不能选会话

`packages/bundle/headless/src/startup.ts` 的 commander 定义**只有 task 位置参数，
没有 --resume**——`dsh --profile headless "..."` 每次冷启动全新会话。
"指定历史会话"的正解是 **ACP profile**。

### 3.2 ACP：程序化控制的官方通道

`packages/acp/README.md` 原文：
> "A client can **create, list, resume, and close sessions**; attach standard MCP servers;
> select model options; **send text and image prompts**; receive semantic updates; answer
> permission prompts; and cancel work **without a human in the loop**.}"

即：ACP 是 JSON-RPC stdio 的自动化协议，**会话按 id 精确选择**，完全无人值守可用。
`subagent/subagent-acp` 还提供了从另一个 harness  spawn ACP server 的客户端
（跨 harness 调度链路的官方实现）。

### 3.3 三条调用路径

| 路径 | 命令形态 | 记忆选择 | 适用 |
|---|---|---|---|
| 冷 | `dsh --profile headless "任务"`（一条命令） | workspace 目录隔离（cd 不同目录=不同 MEMORY.md/指令文件） | 一次性作业、低频 |
| 温 | 临时起 `dsh --profile acp` → resume 会话X → send prompt | **按会话 id** | 按需唤起 |
| 热（推荐值守形态） | `dsh --profile acp` 长驻（systemd）+ 会话持久在线 | **按会话 id**，上下文全热 | 值守主形态 |

### 3.4 核心 agent 的"一个命令"全链

```
核心 agent 调 MCP 工具 lifecore_dsh_exec(session, instruction)
  → VPS device_commands 队列入队
  → 边缘桥（systemd 小脚本）长轮询取令
  → 向本机 ACP server 发 JSON-RPC：resume(session) → prompt(instruction)
  → 拿到最终回复 → POST /v2/commands/{id}/result 回执
```
与今日 google-bridge 管道**完全同构**，执行体从 gws 换成 ACP 调用。
人在 web GUI 里可以和核心走同一个会话（会话 id 是天然汇合点）。

## 4. 推荐部署形态

```
寝室 PC
├─ systemd --user: dsh --profile acp        （长驻自动化端口；Restart=always）
├─ systemd --user: lifecore-edge-bridge      （队列轮询 ↔ ACP 桥，~80 行 python）
│    └─ 会话 "wechat-watch"（持久）：MEMORY.md 人物关系/教训 + 游标
│         ├─ schedule 间隔唤醒巡逻（活跃 60s，静默拉长）——验证项见 §6
│         ├─ 规则预筛（纯脚本）→ 命中才调 LLM 摘要
│         ├─ HMAC V2 上报 VPS /hk/<ch>（复用 google_bridge.py 的 signer）
│         └─ 回查/approval：桥代答（get_original/search_logs 是确定性脚本的活）
└─ （可选）web profile 供人查看/干预同一批会话
```

## 5. 风险与对策（诚实清单）

1. **软件年轻**（v0.1.5-rc.2；schedule/webhook 属新子系统）→ 契约要求记忆外置+标准协议，
   换 harness 成本低，这是设计红利。
2. **常驻资源占用**高于裸脚本 → 无脑轮询源继续用纯脚本；判断密集源（微信摘要、人物关系、
   教训飞轮）才是 DSH 甜区，恰是最有价值的场景。
3. **成本**：每次唤醒 ≥1 次 LLM 调用（MEMORY.md 全文在上下文）；MiniMax 非流式+尾部注入
   缓存友好（实测输入缓存命中 97%）；静默期拉长+规则预筛可压到可忽略。
4. **回查执行体是脚本不是 DSH agent**——特性不是缺陷：回查要确定性，摘要是 LLM 的活。

## 6. 试点验证清单（第一周逐项打勾）

- [ ] ACP 会话内 schedule 间隔 reminder，**断开客户端后**是否自动派发（源码机制支持，
      未实测——**第一项验它**）
- [ ] `resume 会话X → prompt → 拿最终回复` 的端到端时延与成功率
- [ ] 队列冷路径：`headless` + 目录隔离记忆的可行性手感
- [ ] compaction 在值守长会话（含 MEMORY.md 全文反复注入）下的实际触发频率
- [ ] ACP server 长跑稳定性（内存泄漏/挂起观察一周）
- [ ] 核心侧 MCP 工具（入队/等回执）与 notify approval 闭环演练一次

## 7. 结论

DSH 作为地方值守 agent：**能力面 100% 覆盖契约 + 理想特性，可行性 ~90%**。
推荐首个试点：微信摘要值守（MEMORY.md 的人物关系/教训段与 DSH 工作方式完全同构），
上报/回查/approval 复用 google-bridge 已实证管道，新增工作量 ≈ 值守 prompt + ACP 桥
+ systemd 单元。剩余 10% 由 §6 清单在试点周验证消除。
