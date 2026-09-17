# Feedback Loop · 反馈事件读取 + 写入 Prompt

> 用途：用户对通知按 actioned/dismissed/snooze/comment 时，**handler 同步**写 `feedback_events` 表 + 更新 `user_model`；核心被唤醒时读取历史 feedback 做判断。
> 设计依据：docs/11 §五（反馈闭环）+ docs/13 §3（用户模型三类来源）+ docs/11 §三（用户模型）
> 加载位置：`/v2/notify/items/{id}/feedback` handler + `feedback_events` MCP 读取工具

---

## 1. 数据源（新建 `feedback_events` 表）

```sql
CREATE TABLE IF NOT EXISTS feedback_events(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id INTEGER NOT NULL,         -- → notify_items.id
  thread_id INTEGER,                -- → notify_threads.id（可空：dismissed 后 thread 可能已清）
  slot TEXT,                        -- 命中的 user_model slot，无命中为 NULL
  action TEXT NOT NULL,             -- actioned | dismissed | snooze | comment
  delta_conf REAL NOT NULL,         -- 实际 confidence 变化量（带符号）
  comment TEXT,                     -- 自由文本（仅 action='comment'）
  summary_hash TEXT,                -- notify_items.summary 的 sha256（前 80 字）
  created_at REAL NOT NULL
);
CREATE INDEX idx_fb_slot ON feedback_events(slot, created_at);
CREATE INDEX idx_fb_thread ON feedback_events(thread_id);
CREATE INDEX idx_fb_action ON feedback_events(action, created_at);
```

> **扩展现有 handler**（`server.py:647`）：原 `action ∈ {actioned, dismissed, snooze}` 白名单扩为加 `'comment'`。

---

## 2. handler 必做动作（4 步同步）

`POST /v2/notify/items/{id}/feedback` 收到 body 时：

```python
async def notify_feedback(item_id: int, req: Request) -> dict:
    body = req.scope.get("_json") or {}
    action = body.get("action")
    comment = body.get("comment", "")[:1000] if action == "comment" else None
    
    conn = db()
    item = conn.execute("SELECT * FROM notify_items WHERE id=?", (item_id,)).fetchone()
    if not item:
        raise HTTPException(404, "item not found")
    
    # ── Step 1: 写原 resolution（已有逻辑）──
    conn.execute("UPDATE notify_items SET state='resolved', resolution=?, resolved_at=? WHERE id=?",
                 (action, now(), item_id))
    conn.execute("""UPDATE notify_threads SET last_resolution=?, last_resolved_at=?, updated_at=?
                    WHERE id=?""", (action, now(), now(), item["thread_id"]))
    
    # ── Step 2: 算 Jaccard + 关键词相似度 → top-3 slot ──
    target_slots = []
    if action != "comment":
        target_slots = _match_user_model_slots(item["summary"], top_n=3)
    
    # ── Step 3: 写 feedback_events + 更新 user_model ──
    delta = _compute_delta(action, comment)   # 见 §3
    summary_hash = hashlib.sha256((item["summary"] or "")[:80].encode()).hexdigest()[:16]
    conn.execute("""INSERT INTO feedback_events
                    (item_id, thread_id, slot, action, delta_conf, comment, summary_hash, created_at)
                    VALUES (?,?,?,?,?,?,?,?)""",
                 (item_id, item["thread_id"],
                  target_slots[0] if target_slots else None,
                  action, delta, comment, summary_hash, now()))
    
    for slot_key in target_slots:
        _apply_delta_to_user_model(conn, slot_key, action)
    
    # ── Step 4: 推 throttle / rollback 信号（写入 proactive_suggestions）──
    if action == "dismissed":
        _maybe_throttle_proactive(conn, item, target_slots)
    if action == "comment":
        _enqueue_user_comment_for_review(conn, item_id, comment)
    
    promote_notify(conn)
    conn.commit()
    
    # WS 广播（同源单活动锁）
    await ws_broadcast_snapshot()
    return {"resolved": action, "next_active": _next_active(conn)}
```

---

## 3. confidence delta 计算

| 命中情况 | delta | 说明 |
|---|---|---|
| actioned + 命中 slot | +0.05 | 正向反馈 |
| actioned + 无命中 | 0 | 不更新任何 slot |
| snooze + 命中 slot | 0.00 | 推后不构成信号 |
| snooze + 无命中 | 0 | 同上 |
| dismissed + 命中 slot | -0.05 | 反向证据 |
| dismissed + 无命中 | 0 | 不扣分（避免误伤） |
| comment 关键词命中 slot | ±0.05 ~ ±0.10 | 看语义（积极/消极） |
| comment 无命中 | 0 | 留 `feedback_events.comment` 待核心下次看到 |

```python
def _compute_delta(action: str, comment: str | None) -> float:
    if action == "comment":
        if not comment:
            return 0.0
        # 关键词命中 slot（命中由 Step 2 完成）→ delta 由 Step 3 应用
        return 0.0
    return {
        "actioned": 0.05,
        "snooze": 0.0,
        "dismissed": -0.05,
    }.get(action, 0.0)
```

---

## 4. slot 相似度匹配（Step 2 实现）

```python
def _match_user_model_slots(summary: str, top_n: int = 3) -> list[str]:
    """返回 top_n 个最相关的 user_model slot key。"""
    summary_tokens = set(_tokenize(summary))
    rows = list_mcp.read(name="user_model")
    scored = []
    for r in rows:
        slot_key = r["key"]
        slot_tokens = set(_tokenize(r["value_json"]))
        # Jaccard
        union = summary_tokens | slot_tokens
        if not union:
            continue
        jacc = len(summary_tokens & slot_tokens) / len(union)
        # 关键词命中加成（slot 名含 summary 中关键词）
        keyword_hit = sum(1 for tok in summary_tokens if tok in slot_key.lower())
        score = jacc + keyword_hit * 0.1
        scored.append((score, slot_key))
    scored.sort(reverse=True)
    return [k for s, k in scored[:top_n] if s > 0.05]
```

> 阈值 `0.05`：避免过弱匹配误伤。命中即更新，无命中不更新（**不强写**）。

---

## 5. 自由文本 comment 路径（**重要**）

`action='comment'` 时不走 slot 匹配，走人工归纳：

```python
def _enqueue_user_comment_for_review(conn, item_id, comment):
    """把 comment 写进 user_comments 队列，等核心下次看到时归纳。"""
    conn.execute("""INSERT INTO user_comments
                    (item_id, comment, processed, created_at)
                    VALUES (?,?,0,?)""",
                 (item_id, comment, now()))
```

**注入位置**：state_block 末尾追加 `[user_comments]` 段（近 7d ≤10 条）。

```
[user_comments]
  09-17 14:20: "这个提醒太频繁了" (item=42, processed=0)
  09-17 13:55: "下次别打扰周末上午" (item=38, processed=0)
```

> **核心每回合**主动读 `unprocessed_user_comments` → 归纳成候选 slot → 24h 反对方可落 `user_model`（`source=inferred, confidence=0.6`）。

---

## 6. 主动行为频率调节（Step 4 实现）

```python
def _maybe_throttle_proactive(conn, item, target_slots):
    """用户 dismissed 后，看是否触发主动行为降频。"""
    # 同 kind 24h 内 ≥ 2 次 dismissed → 写 throttle 信号
    recent = conn.execute("""SELECT COUNT(*) c FROM feedback_events
                            WHERE action='dismissed' AND created_at > ?
                            AND item_id IN (SELECT id FROM notify_items
                                            WHERE kind=? OR summary_hash=?)""",
                          (now() - 86400, item.get("kind"), _hash(item["summary"]))).fetchone()["c"]
    if recent >= 2:
        conn.execute("""INSERT INTO proactive_suggestions
                        (kind, action, reason, created_at)
                        VALUES (?, 'throttle', ?, ?)""",
                     (item.get("kind", "unknown"),
                      f"24h 内 {recent} 次 dismissed，触发降频", now()))
```

---

## 7. 核心读取 feedback_events 的时机（裁决 Prompt）

> 核心看到 `[threads]` 段包含某 thread_key → 调 MCP `feedback_history(thread_key, n=10)` → 拿到近期 10 条反馈 → 用于判断"该用户对这类议题最近态度"。

**读取 Prompt**（拼到核心 system append）：

> 你刚拿到了 thread `<TK>` 的近 10 条反馈历史：
> ```
> 09-15 14:20 actioned (slot=preference.tone, +0.05)
> 09-15 14:18 dismissed (slot=tolerance.spam.marketing, -0.05)
> 09-14 09:00 snooze (no slot hit)
> ```
> **判断**：该议题对用户来说是否仍重要？最近 3 次反馈的态度倾向？
> - 3 次 actioned → 重要，继续主动
> - 3 次 dismissed → 降级到 L0/L1
> - 2 次 snooze + 1 次 dismissed → 中性，默认 L2 但等用户主动

---

## 8. 边界与禁止

| 禁止 | 原因 |
|---|---|
| 单条反馈直接写 `source=explicit, confidence=0.8` | 幻觉路径——必须 ≥2 个独立证据 |
| 不命中 slot 也强行更新 | 避免噪声污染 user_model |
| `comment` 直接进 user_model 而不经 24h 反对方 | 红线 7（编造记忆） |
| 写 feedback_events 阻塞 LLM 回合 | 异步——handler 已同步落库，核心读时无延迟 |

---

## 9. 与其他文件协作

- `memory_longterm.md` → 接收本文件写入的 slot 更新
- `proactive_trigger.md` §6 throttle/rollback 信号 → 本文件产生
- `system.md` §4 红线 → 红线 7 禁止幻觉写库
