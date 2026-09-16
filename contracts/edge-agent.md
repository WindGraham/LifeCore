# 地方值守 Agent 规范 v0.1

> 适用：跑在地方设备（手机/电脑/服务器）上的常驻 agent——微信摘要、文件夹作业、巡查执行等。
> 选型原则（用户既定）：**vendor harness**（Kimi Code headless / DeepSeek harness / Claude Code 等以能力为目标的 harness），**不在地方部署 Hermes**。
> 本规范定义：值守形态、记忆载体、上报协议、回查义务。

## 1. 值守形态（推荐：常驻挂起循环）

```
loop:
  1. resume 持久会话（或新建并读 MEMORY.md 进上下文）
  2. 读自上次游标以来的新输入（微信增量 / 目录变更 / 轮询目标）
  3. 凭记忆判断：无效→更新记忆→回 1；有效→产出摘要条目
  4. POST 摘要到 VPS（HMAC V2，见 agent.md §2.2）→ 推进本地游标
  5. 更新 MEMORY.md（追加，不覆写）→ 挂起（事件唤醒或短 sleep）
```

- 选常驻而非 cron 冷启动：微信类源延迟敏感、上下文热、resume 成本低；
- 降级策略：静默时段拉长 sleep；低活跃源合并长周期摘要；无新输入不唤起 LLM（纯规则预筛在前）；
- 崩溃恢复：游标与 MEMORY.md 都在磁盘，重启从断点继续；VPS 侧重发风险由幂等 ID 兜。

## 2. 记忆载体：MEMORY.md（外置，不锁死在 harness）

放在程序数据目录，每次值守开始时整体进上下文，结束时追加。结构：

```markdown
# MEMORY — <程序名>（<设备名>）
> 上次更新：<ISO时间> · 游标：<源游标值>

## 人物与关系
- 张三(课程群): 同学，常问作业DDL …
## 会话/话题走向
- 课程群: 本周主题=数据库大作业，截止周五 …
## 我的偏好与教训
- 用户对广告号零容忍；误判教训：2026-09-10 把课程通知当广告 …
## 近期事件指针
- [2026-09-15 14:02] 作业改期 → wx:8832
## 待定/悬而未决
- 李四三次问同样的问题，下次出现应升级上报
```

纪律：每条记忆尽量带指针（可回查原文）；教训段是质量飞轮（场景 1 摘要 agent 的不确定性兜底）；文件即记忆，harness 可换、记忆不丢。

## 3. 上报协议（B 级摘要条目格式）

```json
{
  "level": "B",
  "archetype": "message",
  "pointer": "wx:8832",              // 精确可回查（铁律2）
  "summary": "课程群：数据库作业改到周五 23:59",
  "actors": ["张三"],
  "thread": "课程群",
  "occurred_at": "2026-09-15T14:02:00+08:00",
  "suggested_priority": "normal",     // 只是建议，裁决在核心
  "envelope": { "since": "wx:8801", "count_collapsed": 37 }
}
```

- `suggested_priority` 不越权：地方说"建议"，VPS 核心做打扰裁决（裁决集中原则）；
- 爆发合并：`envelope.count_collapsed` 告知吞并了多少条，核心播报"37 条里 1 条值得看"；
- A 级通道（敏感群）只发元数据信封（谁/何时/几条），summary 为 null。

## 4. 回查义务（C 级不出地方的落实）

值守 agent 必须在其 MCP server 暴露：

| 工具 | 语义 |
|---|---|
| `get_original(pointer)` | 按指针返回原文/原始载荷 |
| `get_context(pointer, radius)` | 原文 ±N 条上下文（判断"断章取义"用） |
| `search_logs(query, since)` | 程序自身日志检索（agent.md §6） |
| `get_status()` | 设备/程序健康、当前游标、最近上报时间（进接入服务清单） |

## 5. 作业型值守（场景 5 扩展）

接受任务单（goal/context/constraints/callback）后：本地开 workspace 执行 → 进度可选上报 → 完成 POST 结果到 callback URL（HMAC）→ 把经验写入 MEMORY.md 的"教训"段。任务级记忆与程序级记忆分开文件（`MEMORY.<task>.md`），任务结束归档。

## 6. 红线

- 不存 VPS 发来的任何秘密到日志/MEMORY（secret 只进内存）；
- 不可逆动作（发消息、删文件）执行前必须有 approval 信号（VPS 核心的 exec approval 或用户明确指令），执行结果进上报；
- 程序退出/结束条件达成：注销通道（DELETE /v1/channels/{id}）并说明原因。
