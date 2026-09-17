# 核心 Agent 智能化架构报告

> 范围:Hermes 网关 + MiniMax-M3 核心裁决侧。约束复述:**Hermes 零修改**、**SQLite 单库**、**lifecore-server 单进程**、**lists 六清单 + notify_items + notify_threads + thread_history MCP 是基础不是终点**。

---

## 一、四个根本缺陷(根因级)

1. **"事件即会话"导致记忆被切碎**。Hermes 每 webhook 路由一个会话,核心每回合只看"刚到的这条 + 注入的六清单片段",**没有机制把今天第 12 条和上周第 3 条串成同一议题**。`notify_threads` 有完整 thread_key/last_resolution,但核心看不到全貌——只能通过 `thread_history` MCP 主动查,而 pre_llm_call 注入块**没有 threads 全量摘要**。根因:**长程记忆埋在 MCP 后,需要核心"记得查"才存在**。

2. **"反馈当命令"导致 learning signal 单向断裂**。`/v2/notify/items/{id}/feedback` 只写 actioned/dismissed/snooze,**用户为什么按、按了之后是总结写得好还是时机不对——零留痕**;自由文本反馈无入口。根因:**反馈回路只到动作层,不到特征层**,核心学不到东西,只会重复同等水平的裁决。

3. **"单活动锁 + 串行播报"导致主动性无承载**。`promote_notify` 严格 FIFO + 单锁 + snooze 续报,这管道**只服务"被动到达的事件"**——没有回路能产生"今天还没发生、但主人可能需要"的入队。`/v2/jobs` 走 Hermes cron,但**用户级软提醒(每天简报、周报)在 lifecore 侧没有运行时**。根因:**主动行为没有自己的触发器与持久调度**,复用 Hermes cron 等于绕过单活动锁——破坏"单核心裁决"。

4. **"清单当 KV"导致结构化偏好评不出来**。`lists.value_json` 是任意 JSON,核心写"主人喜欢短句"只能作为一条 running_items 字符串,下次新会话看到的是新条目而非"可复用偏好模板"。根因:**lists 是 agent 工作台,不是用户画像库**——画像和清单混在一起,衰减/合并/版本化都做不到。

---

## 二、三层记忆架构(复用 SQLite,零新组件)

**工作记忆(per-turn,已存在,补强)**
- 载体:`pre_llm_call` 注入块(`server.py:1115 state_block()`)。
- 升级:在 `[live]` 后追加 **`[threads]` 段**:`SELECT thread_key, last_resolution, last_summary, item_count, updated_at FROM notify_threads WHERE updated_at > now-7d ORDER BY updated_at DESC LIMIT 15`,每行 `tk | last_res | last_summary[:60] | N条 | 时间`。**核心一眼看到"近 7 天 15 个议题的最终命运"**,不需主动查 thread_history。
- 同时把 `bridge_heartbeat.last_seen < 120s ? 'online' : 'stale'` 拼进 `[live]`,让核心知道哪些数据源还活着。
- 预算:`STATE_BUDGET=2500` 不变,threads 段限 ≤600 字符。

**情境记忆(per-session-per-thread,新增轻量表)**
- 新表 `thread_snapshots(thread_id, turn_seq, role, summary, created_at)`——**核心每回合裁决末尾**由"主动写库小线程"异步落一条。一条会话 = 一个 thread_id 的若干 turn。
- 检索:核心调 `thread_history`(已存在)+ 新 MCP `thread_recent_context(thread_key)` 直接返回本 thread 最近 1-2 条核心自己的总结(≤300 字,用于"上次我说到…")。
- 衰减:`turn_seq > 50` 的旧快照凌晨迁移到 `thread_snapshots_archive`(同库只读),活跃会话只看最近 50 回合。

**长期记忆(跨日跨月,复用 lists 拆出 `user_model` 清单)**
- **复用 lists 表**,新增 `name='user_model'` 命名空间(核心通过 MCP 自管,白名单 `FULL_LISTS` 不增项)。key 固定为语义槽:`preference.tone`、`preference.notify.window`、`preference.feedback.style`、`tolerance.spam`、`priority.bias`、`recurring.{topic}`、`persona.traits`。
- 检索:核心调 `read_user_model(slots=[…])` MCP;同时注入块 `[user_model]` 段放摘要,避免每回合查。
- 衰减:value_json 带 `confidence:0..1` + `last_validated_at` + `evidence_count`。`confidence<0.3` 核心下次裁决可静默剔除;`evidence_count=1` 且 `last_validated_at>90d` 自动降权到 0.2(凌晨一次性 UPDATE)。

---

## 三、用户模型(可复用结构,不止 facts)

**形态**:`user_model/<slot>` = `{ value, confidence, evidence_count, last_validated_at, source }`。
- `value` 类型化:如 `notify.window: { start:"22:00", end:"08:00", tz:"Asia/Shanghai" }`、`tone: { verbosity:"short", emoji:"minimal", language:"zh-CN" }`。
- `confidence`:0..1,初值 0.5,正向证据 +0.1,否定 -0.2,封顶 1.0。
- `source`:枚举 `explicit(用户说过) / inferred(从反馈推断) / feedback(actioned) / rejected(dismissed 反推)`。

**学习路径(全部经现有 MCP 工具)**
1. **explicit**:用户说"以后 22 点后别吵我" → 核心 `update_item(user_model, "preference.notify.window", {…})`,source=explicit,confidence=0.8。
2. **feedback**:feedback handler 在写 `notify_items.resolution` 同时**算本条 summary 与 user_model 各 slot 的 Jaccard + 关键词相似度**,top-3 slot 增 evidence_count / 刷 last_validated_at;dismissed 反向 confidence-0.1,若 <0.3 直接 `resolve_item` 软删。
3. **inferred**:核心从连续 7 天 9 条 dismissed 发现都是"营销邮件"类 → 主动 `update_item(user_model, "tolerance.spam.marketing", 0)`,source=inferred。**写入前**裁决里加一句"我观察到您对 X 类普遍不感兴趣,以后默认降级,不同意请回复'撤销'"——24h 无反对才落库,confidence=0.6。

**检索复用**:注入块 `[user_model]` 段取 `confidence≥0.4` 的所有 slot(典型 ≤300 字符),核心"开箱"就用。

---

## 四、主动性触发器(7 条,全部走 notify_threads)

> 共同约束:触发后**先写 `running_items` 一条计划**,裁决后才入队 notify;触发时间窗与 `preference.notify.window` 取交集;**单设备活跃时段**(bridge_heartbeat + 用户 30min 内有反馈)反向加权,无活跃设备时降级为只写 running_items 不推送。

1. **晨间简报**(cron-style)
   - 触发:`user_model/preference.briefing.time`(默认 08:30,显式覆盖);±15min 漂移自适应(`evidence_count` 高 → 锁死)。
   - 行动:`/v2/jobs` 创建 Hermes cron_job,callback 入 `/hk/<briefing_channel>`(已存在的 sink 通道);callback payload 让核心拉"昨晚未决议题 + 今天 schedule 前 3 条 + bridge_heartbeat 异常" → 裁决入队。
   - 避免打扰:`notify.window` 覆盖则跳;`running_items` 已存"今日已发简报"则跳;非工作日用周末模板。

2. **议题续报"待办老化"**
   - 触发:`notify_threads` 中 `last_resolution='snoozed' AND snoozed_until IS NULL AND now - last_resolved_at > 4h`(已存在的 snooze_promote_loop 只看 snoozed_until,这里**新增老化扫描**与原循环并列)。
   - 行动:写 `kind='resume'` 入 notify_items,summary 前缀 `[老化]`,`thread_history` 拼"上次你说稍后,4h 过去了"。
   - 避免打扰:同 thread_key 24h 内最多 1 条;`notify.window` 内不触发。

3. **沉默问候**(情绪/状态类)
   - 触发:用户 24h 内无任何 feedback 且 `bridge_heartbeat` 全部 stale。
   - 行动:核心裁决"是否打扰"——查 `running_items` 近 7 天主人情绪关键词,**只在确实重要**(如健康/家庭类 running_items 静默超 24h)才入队;否则只更新 `user_state` 一条"用户离线"。
   - 避免打扰:7 天 1 次上限;`preference.proactive.greeting=false` 全局关。

4. **数据源异常告警**
   - 触发:`bridge_heartbeat.last_seen > 2*normal_interval`(各 source 自带 expected_interval,缺失用 6h 默认)。
   - 行动:核心按主人对该 source 的近 30 天频次(events 表)定级——高频 → alert 入队;低频 → 写 `running_items` 不推送。
   - 避免打扰:同 source 12h 内最多 1 次;批量异常合并一条 summary。

5. **上下文时机提醒**(预判)
   - 触发:`schedule` 中 `travel/flight/appointment` 类条目距离 ≤ 2h。
   - 行动:核心调 `thread_history` 看该条目之前是否有过"忘了/错过"类决议,有则升 priority=high;否则 normal。
   - 避免打扰:travel 类默认 2h 前 + 30min 前各 1 次,绝不超 2 次;`schedule` 条目 confidence<0.5 不触发。

6. **重复模式识别**("总是这样"提醒)
   - 触发:核心裁决时持续检测 running_items,出现 ≥3 次同模式(如"忘记带钥匙"每周 ≥2) → 写 `user_model/recurring.<topic>`。
   - 行动:核心在裁决输出里**附加一句"我注意到 X 经常发生,要不要我每周日晚提醒您准备?"**,用户回复同意 → 创建 cron_job。
   - 避免打扰:**绝不自动创建提醒**,永远等主人点头。

7. **学习信号确认**(主动教学)
   - 触发:`user_model` 某 slot `confidence` 跨过 0.7/0.9 阈值,或 30 天没被验证。
   - 行动:核心裁决时**偶发**写一条"我目前认为您偏好 X,基于过去 N 次反馈,准确吗?"入 notify(7 天 1 次,随机抖动避免定时感)。
   - 避免打扰:`notify.window` 内不触发;`preference.proactive.learning=false` 全局关。

---

## 五、反馈闭环(贝叶斯式校准)

**数据流**(全部走现有端点):
```
用户按 actioned/dismissed/snooze
  → POST /v2/notify/items/{id}/feedback  (server.py:647)
  → 写 notify_items.resolution
  → 同步取 notify_items.summary + notify_threads.thread_key
  → 同步从 lists(user_model) 拉所有 slot,做相似度(Jaccard + 关键词)
  → 选 top-3 slot 更新 evidence_count / confidence / last_validated_at
  → 写一条 feedback_events(item_id, thread_id, slot, action, delta_conf, summary_hash, created_at)
```

**新增轻量表**:
```sql
CREATE TABLE feedback_events(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id INTEGER NOT NULL,
  thread_id INTEGER,
  slot TEXT,                  -- 命中的 user_model slot,无命中为 NULL
  action TEXT NOT NULL,       -- actioned/dismissed/snooze
  delta_conf REAL NOT NULL,
  summary_hash TEXT,          -- 用于聚类"同类反馈"和 A/B 对齐
  created_at REAL NOT NULL
);
CREATE INDEX idx_fb_slot ON feedback_events(slot, created_at);
CREATE INDEX idx_fb_thread ON feedback_events(thread_id);
```

**自由文本反馈入口**:**复用 notify feedback**,扩 `action='comment'`(白名单扩为 `('actioned','dismissed','snooze','comment')`),`resolution` 存文本;comment 两条路径:① 关键词命中 slot → 同上更新 confidence;② 无命中 → 核心下次看到时把 comment 全文塞进注入块 `[user_comments]` 段(近 7 天 ≤10 条),核心**主动归纳**产出候选 slot,落 user_model 时 source=inferred,confidence=0.5。

**A/B/贝叶斯校准**
- slot posterior ∝ `prior(0.5) × likelihood^(evidence_count × action_weight)`,action_weight = actioned:+1 / snooze:0 / dismissed:-1。
- 增量更新:每条 feedback_events 一次 SQL `UPDATE slot confidence = clamp(confidence + delta_conf, 0, 1)`;凌晨日终 Python 脚本批量重算归一化。
- **A/B**:核心裁决模板变体 A vs B(同 thread_key 同 summary 长度)随机分发,`summary_hash` 对齐,7 天后看哪组 `dismissed_rate` 低 → 胜出模板写入 `user_model/preference.summary.template`。

---

## 六、实施路径(三期,最低投入产出比)

**P1 — 记忆可见 + 反馈回流(2-3 天,纯服务端)**
- 新增表 `feedback_events`。
- `state_block()` `[live]` 后追加 `[threads]` 段(≤600 字符)和 `[user_model]` 段(≤300 字符,confidence≥0.4)。
- `notify_feedback` handler 扩:写 feedback_events + slot 相似度匹配 + 增量更新 user_model;`action` 白名单加 `'comment'`,resolution 允文本文本。
- 产出:核心每回合自动看到"议题命运 + 用户画像",任何 actioned/dismissed 都回流学习。**核心不改、MCP 不改、App 不改**。

**P2 — 主动性运行时 + 老化扫描(2-3 天)**
- 新增 `proactive_loop` 协程(`snooze_promote_loop` 旁边),30s 周期,扫四类触发器(老化续报/沉默问候/数据源异常/上下文时机);**纯 SQL 决策**入队什么,核心 LLM 只在需"写一句人话"时通过 `proactive_seek_judgment(channel, payload)` 异步注入一个小事件给 Hermes 拿回裁决。
- 新增 MCP 工具 `read_user_model(slots: list[str]) → dict`。
- 触发器 1/2/4 上线(晨间简报、老化扫描、bridge 异常);触发器 5 上线(schedule 距离扫描)。
- 产出:7 条触发器中 3 条上线,核心能"主动"入队且不破坏单活动锁。

**P3 — 学习循环 + 偏好校准(3-5 天)**
- 日终任务 `nightly_learning.py`(同进程后台协程),每天 04:00 跑:① batch Bayesian 重算 slot confidence;② dismissed 聚类,产出 inferred 候选写入 `user_model_proposed`,等核心下次看到时落正表;③ A/B 模板胜出判定 → 写 `user_model/preference.summary.template`。
- 触发器 3/6/7 上线,全走 `proactive_seek_judgment`。
- 新增 MCP 工具 `thread_recent_context(thread_key)`(取 thread_snapshots 最近 1-2 条核心总结)。
- 产出:用户模型从"被告知"升级为"被学习出来",主动性从"按规则触发"升级为"按偏好触发"。

**总投入**:P1≈2 天 / P2≈3 天 / P3≈5 天,共 ≈10 人天。
**总产出**:核心 agent 拥有跨议题/跨日/跨月的三层记忆 + 主动行为承载运行时 + 可量化的偏好学习,**全程零 Hermes 改动、单 SQLite、单进程、六清单 + notify 表都是起点不是终点**。
