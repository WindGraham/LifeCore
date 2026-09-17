-- =====================================================================
-- LifeCore 主动行为调度器 — 数据库 schema 补丁
-- 来源:docs/13 §4 主动行为 + docs/11 §5 反馈闭环 + docs/15 §1 设备目录
-- 设计依据:prompts/proactive/README.md
-- 适用 DB:lifecore-server/lifecore.db (SQLite,单库,只追加不破坏)
-- 兼容:复用现有 notify_items / notify_threads / lists(name='user_model')
--      与 enqueue_notify / promote_notify / snooze_promote_loop
-- 上线:在 init_db() 的 executescript 末尾追加本文件内容即可
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. proactive_kinds —— 行为种类注册(L1-L4 + B1-B7 一一对应)
-- ---------------------------------------------------------------------
-- 每条 row 描述一种主动行为,字段含:层级/触发 SQL 模板/灰度状态/预算/
-- 冷却/兜底开关。proactive_loop 30s 扫描时只勾选 enabled=1 的 kind。
-- trust_stage 严格 T1→T2→T3,夜间批可前进一格,但绝不回退(回退只由
-- feedback_interpret 触发并显式标注 reason)。
CREATE TABLE IF NOT EXISTS proactive_kinds(
  kind           TEXT PRIMARY KEY,             -- 'L1.silent' / 'B1.inbox_summary' / ...
  layer          TEXT NOT NULL,                -- 'L1'|'L2'|'L3'|'L4'
  description    TEXT NOT NULL,
  trigger_sql    TEXT NOT NULL,                -- 30s 扫的只读 SQL
  enqueue_kind   TEXT NOT NULL,                -- 'normal'|'resume'|'proactive'
  enqueue_mode   TEXT NOT NULL,                -- 'silent'|'notify'|'decision'|'execute'
  cooldown_sec   INTEGER NOT NULL DEFAULT 0,   -- 同 kind 最小触发间隔
  daily_budget   INTEGER NOT NULL DEFAULT 5,   -- 每日 L2≤5/L3≤1 (B5/B7 ≤2)
  requires_undo  INTEGER NOT NULL DEFAULT 0,   -- L3/L4 必须 1
  enabled        INTEGER NOT NULL DEFAULT 1,
  trust_stage    TEXT NOT NULL DEFAULT 'T1',   -- T0 草案 / T1 灰度 / T2 谨慎 / T3 完全
  created_at     REAL NOT NULL,
  updated_at     REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pk_layer ON proactive_kinds(layer, enabled);
CREATE INDEX IF NOT EXISTS idx_pk_trust ON proactive_kinds(trust_stage, enabled);

-- ---------------------------------------------------------------------
-- 2. behavior_trust —— 灰度状态与评估指标(每 kind 累计)
-- ---------------------------------------------------------------------
-- 评估窗口默认 7 天:feedback_events 数 ≥ 10 才评估。
-- 通过 → trust_stage + 1 阶(封顶 T3);失败 → kind.enabled=0 回 L0。
-- 评估后 status='graduated' / 'rolled_back',reason 必填。
CREATE TABLE IF NOT EXISTS behavior_trust(
  kind                  TEXT PRIMARY KEY REFERENCES proactive_kinds(kind),
  stage_started_at      REAL NOT NULL,
  stage_feedback_count  INTEGER NOT NULL DEFAULT 0,
  stage_actioned_count  INTEGER NOT NULL DEFAULT 0,
  stage_dismissed_count INTEGER NOT NULL DEFAULT 0,
  stage_snooze_count    INTEGER NOT NULL DEFAULT 0,
  stage_failure_count   INTEGER NOT NULL DEFAULT 0,  -- 4 段式失败恢复触发
  eval_due_at           REAL NOT NULL,         -- stage_started_at + 7d
  status                TEXT NOT NULL DEFAULT 'active', -- active | graduated | rolled_back | disabled
  last_eval_at          REAL,
  last_eval_reason      TEXT,
  updated_at            REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bt_due ON behavior_trust(status, eval_due_at);

-- ---------------------------------------------------------------------
-- 3. feedback_events —— 反馈环主表(docs/11 §5 已定义,这里做对齐版)
-- ---------------------------------------------------------------------
-- 所有 notify_items 的反馈 + 行为触发评估都写这里。slot 是命中
-- user_model 哪个 key;无命中=NULL,走 inferred 路径。summary_hash 用
-- 于 A/B 模板对齐。24h cooling 规则也读这表(同 kind dismissed 计数)。
CREATE TABLE IF NOT EXISTS feedback_events(
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id         INTEGER,                     -- notify_items.id,可空(行为级反馈时为 NULL)
  thread_id       INTEGER,                     -- notify_threads.id
  kind            TEXT,                        -- 行为 kind
  slot            TEXT,                        -- 命中的 user_model slot;无=NULL
  action          TEXT NOT NULL,               -- actioned|dismissed|snooze|comment|failure|thumbs_up|thumbs_down|snooze_long|undo|settings_changed
  delta_conf      REAL NOT NULL DEFAULT 0.0,
  summary_hash    TEXT,                        -- A/B 对齐
  resolution_text TEXT,                        -- 自由文本(comment/矫正通道)
  created_at      REAL NOT NULL,
  source          TEXT NOT NULL DEFAULT 'app'  -- app|proactive_loop|nightly_learning
);
CREATE INDEX IF NOT EXISTS idx_fb_slot ON feedback_events(slot, created_at);
CREATE INDEX IF NOT EXISTS idx_fb_thread ON feedback_events(thread_id);
CREATE INDEX IF NOT EXISTS idx_fb_kind ON feedback_events(kind, action, created_at);
CREATE INDEX IF NOT EXISTS idx_fb_hash ON feedback_events(summary_hash, created_at);

-- ---------------------------------------------------------------------
-- 4. proactive_suggestions —— 主动建议的"待采纳队列"(L3 干预前必落)
-- ---------------------------------------------------------------------
-- L3/L4 行为在用户拍板前先落这一行(state=pending)。用户接受/拒绝/超时
-- 后改 state。24h 冷静期通过 cooldown_kind + cooldown_until 实现。
-- 矫正通道(action='feedback_text')也复用此表,只是 action 列不同。
CREATE TABLE IF NOT EXISTS proactive_suggestions(
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  kind            TEXT NOT NULL REFERENCES proactive_kinds(kind),
  thread_id       INTEGER,
  target_user_id  TEXT NOT NULL DEFAULT 'default',
  payload_json    TEXT NOT NULL,               -- 触发时的输入快照
  action          TEXT NOT NULL DEFAULT 'pending', -- pending|accepted|rejected|expired|feedback_text
  state           TEXT NOT NULL DEFAULT 'pending',
  confidence      REAL NOT NULL DEFAULT 0.5,
  created_at      REAL NOT NULL,
  resolved_at     REAL,
  expires_at      REAL,                        -- 通常 = created_at + 7d
  cooldown_kind   TEXT,                        -- 用户拒绝后同类冷却到
  cooldown_until  REAL,
  undo_token      TEXT,                        -- L4 execute 模式 5s 撤回凭据
  rollback_cmd    TEXT                         -- 5s 内可执行的反向命令
);
CREATE INDEX IF NOT EXISTS idx_ps_state ON proactive_suggestions(state, created_at);
CREATE INDEX IF NOT EXISTS idx_ps_kind ON proactive_suggestions(kind, state, created_at);
CREATE INDEX IF NOT EXISTS idx_ps_cooldown ON proactive_suggestions(cooldown_kind, cooldown_until);

-- ---------------------------------------------------------------------
-- 5. user_kill_switches —— 用户主动关 + 自动降级
-- ---------------------------------------------------------------------
-- 与 docs/15 §5 kill switch 对齐:device 级 + kind 级 + 全局三档。
-- emergency_disabled_until > now 时 proactive_loop 完全停;kind 级只关
-- 对应 kind。kill 触发时也写一条 feedback_events(action='settings_changed')。
CREATE TABLE IF NOT EXISTS user_kill_switches(
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  scope           TEXT NOT NULL,               -- 'global'|'device:<id>'|'kind:<kind>'
  enabled         INTEGER NOT NULL DEFAULT 1,
  reason          TEXT,
  expires_at      REAL,                        -- NULL=永久直到 user_revoke
  created_at      REAL NOT NULL,
  updated_at      REAL NOT NULL,
  UNIQUE(scope)
);
CREATE INDEX IF NOT EXISTS idx_ks_enabled ON user_kill_switches(enabled, expires_at);

-- ---------------------------------------------------------------------
-- 6. proactive_loop_state —— 调度器自身的运行态(单进程互斥用)
-- ---------------------------------------------------------------------
-- 30s 扫描的游标 + 上次预算计数 + 失败重试冷却。
CREATE TABLE IF NOT EXISTS proactive_loop_state(
  kind            TEXT PRIMARY KEY REFERENCES proactive_kinds(kind),
  last_fired_at   REAL,
  last_daily_date TEXT,                        -- 'YYYY-MM-DD' 本地日期
  last_daily_count INTEGER NOT NULL DEFAULT 0,
  last_failure_at REAL,
  consecutive_failures INTEGER NOT NULL DEFAULT 0,
  block_until     REAL,                        -- 4 段式恢复:停触达至此时
  last_scan_at    REAL,
  updated_at      REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pls_block ON proactive_loop_state(block_until);

-- ---------------------------------------------------------------------
-- 7. 视图:当前可触达 kind(供 state_block() 注入)
-- ---------------------------------------------------------------------
CREATE VIEW IF NOT EXISTS v_proactive_active AS
SELECT k.kind, k.layer, k.trust_stage, k.daily_budget,
       COALESCE(s.last_daily_count, 0) AS used_today,
       s.last_fired_at, s.block_until
FROM proactive_kinds k
LEFT JOIN proactive_loop_state s ON s.kind = k.kind
WHERE k.enabled = 1
  AND k.trust_stage IN ('T1','T2','T3')
  AND (s.block_until IS NULL OR s.block_until <= strftime('%s','now'));

-- ---------------------------------------------------------------------
-- 8. seed —— 4 个层 + 7 个行为注册(行级 idempotent,upsert by kind)
-- ---------------------------------------------------------------------
-- 字段语义摘自 docs/13 §4 + docs/11 §4。
INSERT OR REPLACE INTO proactive_kinds(kind, layer, description, trigger_sql,
  enqueue_kind, enqueue_mode, cooldown_sec, daily_budget, requires_undo,
  enabled, trust_stage, created_at, updated_at) VALUES

-- L1 安静收集(只写 state,silent 入队)
('L1.silent',          'L1','通用 L1 占位:仅写 user_state/schedule',
  'SELECT 1 WHERE 0','normal','silent', 0, 999, 0, 1,'T1', strftime('%s','now'), strftime('%s','now')),

-- L2 状态报告(resume 类不阻塞)
('L2.resume_report',   'L2','通用 L2 占位:同 thread_id resume',
  'SELECT 1 WHERE 0','resume','notify', 7200, 5, 0, 1,'T1', strftime('%s','now'), strftime('%s','now')),

-- L3 决策请求(active + 单活动锁)
('L3.decision_lock',   'L3','通用 L3 占位:阻塞等反馈',
  'SELECT 1 WHERE 0','normal','decision', 14400, 1, 0, 1,'T2', strftime('%s','now'), strftime('%s','now')),

-- L4 主动执行(事后报)
('L4.execute',         'L4','通用 L4 占位:执行+回传',
  'SELECT 1 WHERE 0','normal','execute', 21600, 1, 1, 0,'T3', strftime('%s','now'), strftime('%s','now')),

-- B1 收件箱摘要(L1,T1 灰度)
('B1.inbox_summary',   'L1','过去 24h 未读聚合,首屏展示,不弹通知',
  'SELECT COUNT(*) FROM notify_items WHERE state IN (''queued'',''awaiting_feedback'')
     AND created_at >= strftime(''%s'',''now'')-86400
     AND (SELECT MAX(f.created_at) FROM feedback_events f WHERE f.source=''app'') IS NULL',
  'normal','silent', 86400, 1, 0, 1,'T1', strftime('%s','now'), strftime('%s','now')),

-- B2 日历预备(L1,T1 灰度)
('B2.calendar_prep',   'L1','T-24h 日历条目无 actioned → 预取 context 缓存',
  'SELECT t.id FROM notify_threads t
     JOIN lists s ON s.name=''schedule'' AND s.resolved=0
       AND json_extract(s.value_json,''$.start_ts'') BETWEEN strftime(''%s'',''now'')+82800 AND strftime(''%s'',''now'')+90000
     WHERE NOT EXISTS (SELECT 1 FROM notify_items i WHERE i.thread_id=t.id AND i.resolution=''actioned'')',
  'normal','silent', 3600, 999, 0, 1,'T1', strftime('%s','now'), strftime('%s','now')),

-- B3 关系维护(L2,T2 谨慎)
('B3.relationship_nudge','L2','关系对象 >7d 无触达 → 同 thread_id resume 续报',
  'SELECT t.id FROM notify_threads t
     WHERE t.last_resolution IN (''snoozed'',''dismissed'')
       AND t.updated_at < strftime(''%s'',''now'')-604800
       AND NOT EXISTS (SELECT 1 FROM notify_items i WHERE i.thread_id=t.id
                         AND i.created_at > strftime(''%s'',''now'')-604800)',
  'resume','notify', 259200, 2, 0, 1,'T2', strftime('%s','now'), strftime('%s','now')),

-- B4 例行巡查(L2,绝对不弹,改 user_state 一行)
('B4.routine_check',   'L2','同 thread 14 天内 snooze>=4 → user_state 标记,等用户开 thread 时显示',
  'SELECT t.id, COUNT(*) c FROM notify_threads t
     JOIN notify_items i ON i.thread_id=t.id AND i.resolution=''snoozed''
     WHERE i.created_at > strftime(''%s'',''now'')-1209600
     GROUP BY t.id HAVING c >= 4',
  'normal','silent', 86400, 1, 0, 1,'T2', strftime('%s','now'), strftime('%s','now')),

-- B5 待跟进追踪(L3 决策,T3)
('B5.followup_tracker','L3','日程冲突静默备选,挂起 7 天等采纳',
  'SELECT s1.key FROM lists s1, lists s2
     WHERE s1.name=''schedule'' AND s2.name=''schedule''
       AND s1.key<>s2.key AND s1.resolved=0 AND s2.resolved=0
       AND json_extract(s1.value_json,''$.start_ts'') < json_extract(s2.value_json,''$.end_ts'')
       AND json_extract(s1.value_json,''$.end_ts'')   > json_extract(s2.value_json,''$.start_ts'')',
  'normal','decision', 604800, 1, 1, 1,'T3', strftime('%s','now'), strftime('%s','now')),

-- B6 主动建议(L2,T2,凌晨守护)
('B6.proactive_suggest','L2','凌晨 23-04 点 + 30min 无输入 + app_foreground → 起身提醒',
  'SELECT 1 FROM bridge_heartbeat WHERE last_seen > strftime(''%s'',''now'')-60
     AND strftime(''%H'',''now'',''localtime'') IN (''23'',''00'',''01'',''02'',''03'',''04'')
     AND NOT EXISTS (SELECT 1 FROM feedback_events WHERE created_at > strftime(''%s'',''now'')-1800)',
  'normal','notify', 7200, 2, 0, 1,'T2', strftime('%s','now'), strftime('%s','now')),

-- B7 跨通道去重(L2,T2,主题收敛)
('B7.cross_channel_dedup','L2','6h 内 ≥2 不同通道摘要语义同一主题 → 合并单条 thread',
  'SELECT t1.id, t2.id FROM notify_threads t1, notify_threads t2
     WHERE t1.channel_id <> t2.channel_id
       AND t1.updated_at > strftime(''%s'',''now'')-21600
       AND t2.updated_at > strftime(''%s'',''now'')-21600
       AND t1.id < t2.id',
  'resume','notify', 21600, 2, 0, 1,'T2', strftime('%s','now'), strftime('%s','now'));

-- 同步初始化 behavior_trust + loop_state
INSERT OR IGNORE INTO behavior_trust(kind, stage_started_at, eval_due_at, updated_at)
SELECT kind, strftime('%s','now'), strftime('%s','now')+604800, strftime('%s','now')
FROM proactive_kinds;
INSERT OR IGNORE INTO proactive_loop_state(kind, updated_at)
SELECT kind, strftime('%s','now') FROM proactive_kinds;
