# LifeCore "真的智能" 综合方案（四报告合并）

> 2026-09-17 · 哲学/架构/App/主动行为 四份报告合并的产品+技术综合。
> 源报告：docs/10 通知线程延续 · docs/11 核心 agent 智能化架构 · docs/12 主面板重构 · `docs/14` 主动行为设计方案（综合本文）
> 一句话目标（哲学信条）：**沉默观察，替用户守住那个他自己都会忘掉的、体面的长期自己。**

## 0. 当前到目标的距离（一句话）

现在的 LifeCore 是个**"五等分的远程终端 + 单活动锁通知器"**——用户不打开 App 它就死，打开也不知点哪个；核心每次来事件都是新会话、靠 6 张清单过日子，记忆薄；系统被动响应、不主动、跨通道失忆。距离"沉默观察者 + 真正的长期关系"差三步：**主页面重构成"今日"**、**核心有长程记忆 + 用户模型**、**主动行为带信任梯度与失败兜底**。

## 1. 一条不变的信条（哲学）

- **沉默观察**，替用户守住那个他自己都会忘掉的、体面的长期自己。
- 反对：过度打扰的伪共情 / 假装个性化 / 幻觉式记忆 / 透明强迫症式解释 / 摘要复读机（用 LLM 把原文复述一遍不算智能）/ 跨通道失忆让用户自己做情报整合。
- 判别主动的金标准：**"若不主动，用户是否会因为没机会反应而付出可观的代价"**——能挽回的才主动，纯猜测不主动。

## 2. 三层主页面（App）—— "今日"

把底栏 5 等分 tab 收进抽屉，主屏是**三张主动卡片**自上而下：

| 卡片 | 内容 | 数据来源 | 频次 |
|---|---|---|---|
| A · **"今天已为你做完"** | 昨晚到今晨已处理的事件摘要、预加载的会议上下文、准备好的草稿 | `GET /v2/digest/today`（BFF 聚合：notify_history + calendar + drafts） | 用户打开 App 时即取 |
| B · **"现在需要你拍板"** | 单活动锁面板（最多一条，决议即可清空） | 复用现有 `/v2/notify/active` | 实时 |
| C · **"想问点什么"** | 3-5 个语境化 chip（"那封邮件回了吗？"）+ 输入框 + 🎙/📎/📍 | `GET /v2/prompts/suggested` + 用户输入 | 即时 |

抽屉式次级导航（按频次降序）：通知 → 会话 → 通道 → 任务 → 设置 → 设备。**垂直空间 +15%**，底栏不抢占。

四期路线：
- **M1**（今日页 + 抽屉 + 三卡片静态版）
- **M2**（真 digest + Share Intent + 通知分层：L0 静默入队 / L1 通知中心 / L2 抬头 TTS）
- **M3**（语音输入 + KWS 真唤醒）
- **M4**（锁屏 Glance widget）

## 3. 核心 agent 智能化（三层记忆 + 用户模型）

### 三层记忆（全部 SQLite，不动 Hermes）

| 层 | 用途 | 存储 | 注入点 |
|---|---|---|---|
| 工作 | 当前回合的活跃线程与即时画像 | `state_block` 增加 `[threads]`(≤600字) 与 `[user_model]`(≤300字) 两段 | 每回合 pre_llm_call hook |
| 情境 | 每个通知线程最近 N 轮上下文 | 新表 `thread_snapshots(thread_id, turn_seq, role, summary, created_at)` | 异步落库；读用现 MCP `thread_history` |
| 长期 | 跨日跨月的用户偏好、画像、决议模式 | 复用 lists 表，新增 `name='user_model'` 命名空间，slot = {value, confidence, evidence_count, last_validated_at, source} | core agent 自己查询/写 |

### 用户模型三类来源
- **explicit** 用户亲口说的
- **feedback** 通过 `feedback_events` 表（扩展 `notify_feedback` + free-text comment）
- **inferred** 行为推断——**必须 24h 反对方可落库**（冷却规则防幻觉）

### 7 条主动触发器（与下方 §4 行为一一对应）
详见 §4。所有"主动"的产生路径：`proactive_loop` 协程（30s 扫一次）→ SQL 决策 → enqueue_notify → 复用单活动锁 → 由核心 agent 在裁决轮次里生成**人话**（不直接吐结构化建议，让核心有"判断"在）。这与架构师的"核心只在写人话时调 proactive_seek_judgment"完全一致。

## 4. 主动行为（3 层 + 7 行为 + 信任梯度 + 失败兜底）

### 三层定义
- **L1 预防**：后台预取/聚合/草稿——**不弹通知**，用户打开 App 时才看见
- **L2 提示**：关键时间点的安静 nudge，可一键忽略，**入同 thread_id 续命**（P1/P2 已规划）
- **L3 干预**：可能产生副作用的主动建档——必须**5 秒内可撤**，默认 draft 态

### 7 个具体行为（每条都有触发信号/行动/不打扰约束）

| ID | 名称 | 层 | 触发信号（现有数据） | 行动 |
|---|---|---|---|---|
| B1 | T-24h 日历上下文预加载 | L1 | 日历线程 T-24h 且无 actioned | 本地缓存 `get_context(pointer,radius=10)` |
| B2 | 作业 DDL T-4h nudge | L2 | 学业监控 + thread 7 天内无 actioned + 时间窗 [DDL-4h,DDL-30min] | 同 thread_id 更新通知，options 可直接调 MCP 加日历 |
| B3 | 习惯性回避反思 | L3 | 同 thread 14 天 snooze≥4 且间隔在缩短 | 用户打开该 thread 时首行显示"已 4 次稍后，要拆解吗？"——**绝对不弹通知** |
| B4 | 日历冲突静默备选 | L3 | calendar 入队事件 X 与 confirmed 事件 Y 重叠 | 派生 `pending_suggestion` 挂起 7 天，采纳 = 调 MCP 改 X |
| B5 | 凌晨深度工作守护 | L2 | 23-04 点 + 30min 无用户输入 + app_foreground | 单条"已专注 90 分钟，要起身吗？"；focus=true 后 45min 内静默 |
| B6 | 未读聚合日报 | L1→弱 L2 | 每 24h 一次 + 用户未开 App >8h | **不弹通知**，首屏展示过去 24h 5 条候选 |
| B7 | 跨通道主题收敛 | L2 | 6h 内 ≥2 不同通道摘要语义同一主题 | 生成单条 thread 聚合三方要点；同主题后续自动归入 |

### 信任梯度（强制灰度上线）

| 阶段 | 启用范围 | 退出条件 |
|---|---|---|
| T1 灰度（首 14 天） | **仅 L1**（B1、B6）——纯后台 | 14 天核心调用率 ≥30%→T2；<10% 调查数据 |
| T2 谨慎（14-45 天） | L2 的 B2、B5、B7；每 kind 14 天最多 2 次，间隔 ≥72h | 采纳率 ≥40%、无"立即 dismissed + 情绪文本"→T3 |
| T3 完全（45 天起） | L3 的 B3、B4；**必须挂撤回按钮** | 用户说"别主动"→ 永久 disabled_until=user_revoke，重启核心也不复活 |

### 失败兜底四件套

1. **撤回**：任何 L3 副作用 5s 内可逆（同一 MCP 调反向；通知 30s 撤销条）
2. **道歉**：用户 60s 内对同 thread 连续两次 dismissed/snooze → 反向通知"抱歉打扰"+ 选项"以后别再发生"→ thread 永久 disabled + 写 running_items 事实
3. **矫正通道**：通知正文最后一行永远"这种提醒不合适？"，点击展开自由文本→ 写入 proactive_suggestions.action='feedback_text'，核心下轮裁决时自行处理；**用户给矫正后 24h 冷静期禁同类主动**
4. **礼貌预算**：每日 L2≤5 次 / L3≤1 次；用尽 → 核心日志 `[budget_exhausted]` 并停；App 设置页可视化

## 5. 现状资产复用（不造新系统）

- ✅ Hermes **零修改**：全部走 pre_llm_call hook + webhook + MCP
- ✅ SQLite 单库：仅新增 2 张表（`feedback_events`、`thread_snapshots`）+ 1 张归档 + 2 张主动行为表（`proactive_kinds`、`proactive_suggestions`）
- ✅ 单活动锁不绕过：主动行为入队走 enqueue_notify → promote_notify
- ✅ notify 线程续命（P1/P2）：L2 复用同 thread_id 更新通知
- ✅ channel 端 `get_status()` + `thread_history()` MCP：主动触发信号完全来源
- ✅ google-bridge 队列模式：边缘执行范式

## 6. 三期落地（最小投入）

| 期 | 内容 | 工日 | 价值 |
|---|---|---|---|
| **P1**（2-3 天） | `feedback_events` 表 + state_block 增 `[threads]`/`[user_model]` 两段 + feedback 端点扩 comment + 新 MCP `read_user_model`/`thread_recent_context` | ~3 | 核心开始"有记忆"，反馈闭环建立 |
| **P2**（3 天） | `proactive_loop` 协程（与 `snooze_promote_loop` 并列）+ 启用 B1/B6（T1 灰度）+ App M1 今日页骨架 + drawer | ~4 | 沉默观察 + App 不再是"5 等分终端" |
| **P3**（5 天） | `nightly_learning.py` 日终批（Bayesian 校准 + dismissed 聚类 + A/B 胜出）+ 启用 B2/B5/B7（T2）+ 道歉/矫正通道 + 礼貌预算 + App M2 真 digest | ~8 | 主动行为上线带安全网 + 跨通道整合（B7） |
| 后续 | L3 上线（T3 满 45 天）+ Glance widget（M4）+ 语音 + KWS | 待定 | 完整的"沉默观察者" |

**P1 + P2 即可让系统从"复读通知器"升级为"有记忆的反应者"；P3 才进入真正的"主动"层。** 把每日预算和信任梯度放在 P2 同周上线——避免"主动被滥用"毁了信任。

## 7. 反对清单（设计纪律）

- 不复读原始事件当摘要（要加判断/关联/结论）
- 不在"用户没机会反应才付出代价"以外的场景主动
- 不为显得聪明而过度打扰
- 不编造"我记得你说过"（记忆宁可少不可假）
- 不每次都解释为什么（长期关系里不需要）

## 8. 待你拍板的几个决策

1. **P1 立即做吗？**（纯服务端，3 天，端点+状态块扩展，零 APP 改动、最小风险）
2. **"今日"主页面你接受为 M1 方向吗？**（替代 5 等分底栏是 UX 大改）
3. **主动行为愿意从 T1（纯后台 L1）开始吗？** 14 天后无感知数据再决定上不上 T2
4. **跨通道主题收敛 B7 的语义判定**要不要先用一个轻量关键词+嵌入相似度（资源<LLM 一次）的方案兜底，等长程记忆起来再升级？

---

## 9. 实施落点（2026-09-17）

> 同一日内全部产出落地清单，与 docs/16/17/19 一并构成 P1+P2 的物理证据。

### 9.1 Prompt 套件（核心 agent + 主动行为调度器）

- `prompts/core-agent/`（9 文件）：
  - `system.md` — 核心 agent 总入口 prompt
  - `memory_working.md` — 工作记忆（per-turn）
  - `memory_contextual.md` — 上下文记忆（per-thread）
  - `memory_longterm.md` — 长期记忆（per-user）
  - `proactive_trigger.md` — 主动行为触发判定的 agent 侧 prompt
  - `feedback_loop.md` — 反馈闭环（👍/👎/⏰/矫正文本）
  - `subagent_dispatch.md` — 子 agent 派遣（MCP / google-bridge / cron）
  - `thread_continuity.md` — 线程续命（同 thread_id 复用）
  - `README.md` — 套件索引
- `prompts/proactive/`（14 文件 + schema.sql）：
  - 4 层行为分级：`L1_signal_collection.md` / `L2_status_report.md` / `L3_decision_request.md` / `L4_autonomous_execute.md`
  - 7 个行为触发：`B1_inbox_summary.md` / `B2_calendar_prep.md` / `B3_relationship_nudge.md` / `B4_routine_check.md` / `B5_followup_tracker.md` / `B6_proactive_suggest.md` / `B7_cross_channel_dedup.md`
  - 机制：`trigger_decision.md` / `kill_switch.md` / `feedback_interpret.md`
  - `README.md`（索引）+ `schema.sql`（5 表 + 1 view + seed，本次部署**不生效**，等下迭代注入 `init_db()`）

### 9.2 调研/规范文档

- `docs/16-hermes-UI规范.md` — Hermes 控制台 UI 规范（设计语言、组件、状态、a11y）
- `docs/17-hermes-页面模板.md` — Hermes 8 个页面的实现模板（Pair/Today/Chat/Notify/Channels/Jobs/Settings/index）

### 9.3 Hermes Web SPA（LC 私有 fork，路径 `/usr/local/lib/hermes-agent/web/`）

- `hermes/web/src/pages/lifecore/`（8 文件）：`index.tsx`（根重定向）+ `PairPage.tsx` / `TodayPage.tsx` / `ChatPage.tsx` / `NotifyPage.tsx` / `ChannelsPage.tsx` / `JobsPage.tsx` / `SettingsPage.tsx`
- `hermes/web/src/lib/lifecore-api.ts` + `hermes/web/src/lib/lifecore-pair-store.ts`
- `hermes/web/src/hooks/useVoiceConsent.ts`
- `hermes/web/src/components/lifecore/VoiceConsentBanner.tsx`
- `hermes/web/src/i18n/lifecore.ts`
- 接入：`hermes/web/src/App.tsx`（7 lazy imports + 7 routes + 6 nav + 根重定向）、`hermes/web/src/i18n/types.ts` + `en.ts` + `zh.ts`（lifecore 段扩 keys）

### 9.4 Android App（Pixel Android 17，APK 6.8 MB debug）

- 15 Kotlin：`Api.kt` / `BootReceiver.kt` / `ChatActivity.kt` / `FeedbackReceiver.kt` / `MainActivity.kt` / `Md.kt` / `PairStore.kt` / `PlayService.kt` / `ThreadDetailActivity.kt` + 5 个 `ui/<sub>/*Fragment.kt`（channels/jobs/notify/settings/today）
- 14 XML：`drawer_header.xml` / `activity_chat.xml` / `activity_main.xml` / `activity_pair.xml` / `activity_thread.xml` / `fragment_list.xml` / `fragment_notify.xml` / `fragment_settings.xml` / `fragment_today.xml` / `item_channel.xml` / `item_digest.xml` / `item_job.xml` + `drawer_nav.xml`（menu）+ `top_bar.xml`（menu）
- 重写要点：DrawerLayout 替代底栏 + PairStore（SharedPreferences token+fp 持久化）+ WS 单连接 + 单调 `lastSpokenId`（SharedPreferences）根治重启回放与空 active 清 guard 重播

### 9.5 后端 + 部署

- `deploy/nginx-lc-redirect.conf` — 旧 `/lc*` → `/console/lc-today` 301（4 行 location 块）
- `services/lifecore-server/static/console.html` — **删除**（-370 行，迁至 hermes SPA）
- `services/lifecore-server/server.py` — 移除 `/console` 端点（-9 行），保持数据 schema 不变

### 9.6 B4 实施偏离说明

> **修正**：2026-09-17 B4 实施为 L2/silent（详见 `prompts/proactive/B4_routine_check.md` §3/§7 + `prompts/proactive/README.md` §3.2）。

`docs/13 §6` 原描述 B3 — 习惯性回避反思为 **L3 干预**（"用户开 thread 时首行显示"），实际 `prompts/proactive/B4_routine_check.md` 落地时按以下偏离执行：

1. **层级下调**：L3 → L2，且 `enqueue_mode='silent'`（**绝对不入队、不写 notify_items、不调 promote_notify**），仅写 `user_state.thread_meta/<tid>/avoidance_flag` + 改 `notify_threads.title` 前缀为 `⚠️ 习惯性回避 → `。
2. **触发时机**：等用户**主动打开**该 thread 时才在首行显示反思提示（`shown_to_user` 字段置 true）。
3. **灰度阶段**：T2 谨慎（不是 T3 完全）。
4. **不打扰红线**：每 thread 7 天最多 1 次；问题必须具体可操作（"你还好吗" → failure）。

依据：`prompts/proactive/B4_routine_check.md §7` 与 docs/13 §1 信条"沉默观察"一致，且避免 thread 标题前缀变化影响 L2 同 thread 续命逻辑（只改 title 不改 thread_id）。
