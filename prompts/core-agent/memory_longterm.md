# Long-term Memory · 长期记忆（user_model）注入模板

> 用途：跨日跨月的用户偏好 / 画像 / 决议模式
> 设计依据：docs/11 §二（长期记忆）+ docs/11 §三（用户模型）+ docs/13 §3
> 加载位置：MCP 工具 `read_user_model(slots=[...])` + state_block `[user_model]` 段

---

## 1. 数据源（复用 `lists` 表的命名空间）

```sql
-- 现有 lists 表（server.py 已建）
-- CREATE TABLE lists(name TEXT, key TEXT, value_json TEXT, ...);

-- user_model 命名空间：key = slot 名，value_json = 完整 entry（见 §2）
-- 写入路径：核心 LLM 调 list_mcp.update_item(name='user_model', key='<slot>', value=<entry>)
```

**白名单 slot**（核心必须使用这些 key，禁止自创以保持一致性）：

| slot | 含义 | value 类型 |
|---|---|---|
| `preference.tone` | 措辞偏好 | `{verbosity, emoji, language}` |
| `preference.notify.window` | 静音窗口 | `{start, end, tz}` |
| `preference.briefing.time` | 晨间简报时间 | `"HH:MM"` |
| `preference.feedback.style` | 反馈风格 | `{verbosity, emoji}` |
| `preference.summary.template` | 摘要模板（A/B 胜出） | 字符串模板 |
| `preference.proactive.greeting` | 是否允许沉默问候 | `bool` |
| `preference.proactive.learning` | 是否允许学习信号确认 | `bool` |
| `tolerance.spam.marketing` | 营销邮件容忍度 | `0..1` |
| `tolerance.spam.notification` | 推送通知容忍度 | `0..1` |
| `priority.bias` | 用户对议题的优先级偏向 | `{family, work, health, ...}` |
| `recurring.<topic>` | 重复模式（如"忘带钥匙"） | `{pattern, count, last_seen}` |
| `persona.traits` | 用户性格标签 | `["intj", "凌晨型", ...]` |
| `device.<id>.used_capabilities` | 设备能力使用记录 | `[{cap_id, last_used, ok}]` |

> 新增 slot 必须先在本表登记 → 通知核心 prompt 维护者。

---

## 2. entry 结构（value_json 内含）

```json
{
  "value": <类型化值>,
  "confidence": 0.82,
  "evidence_count": 12,
  "last_validated_at": 1726589400.0,
  "source": "explicit"  // explicit | inferred | feedback | rejected
}
```

**字段含义**：

- `value` —— 类型化值（不是字符串）
- `confidence` —— `0..1`，初值 0.5
- `evidence_count` —— 单调递增 ≥1（见 §3 累积规则）
- `last_validated_at` —— unix 秒
- `source` —— 写入来源（追溯用）

---

## 3. confidence 累积公式

每条 `feedback_events` 命中 slot 时：

```python
DELTA_CONF = {
    "explicit_strong":   +0.10,   # 用户原话明确说（如"以后 22 点后别吵我"）
    "explicit_weak":     +0.05,   # 用户暗示（如"这么晚还弹…"）
    "feedback_actioned": +0.05,   # 用户 actioned 命中
    "feedback_snooze":    0.00,   # 推后不做信号
    "feedback_dismissed": -0.05,  # dismissed 命中 → 反向证据
    "rejected_comment":  -0.10,   # 自由文本明确反对
    "inferred_24h_ok":   +0.03,   # 推断后 24h 无反对
}

def update_confidence(slot: dict, action: str) -> dict:
    delta = DELTA_CONF.get(action, 0)
    slot["confidence"] = max(0.0, min(1.0, slot["confidence"] + delta))
    slot["evidence_count"] += 1
    slot["last_validated_at"] = time.time()
    if delta < 0 and slot["confidence"] < 0.3:
        # 自动软删：resolved=1（不删行，留 audit）
        slot["_resolved"] = True
    return slot
```

---

## 4. 写入门槛（强约束）

> **必须 ≥2 个独立证据才建条目**。

- 第 1 次写：`confidence=0.3, evidence_count=1, source=inferred, _pending=true`
- 第 2 个独立证据到来：`confidence=0.5, evidence_count=2, source=explicit/inferred, _pending=false`
- 凌晨 batch：`confidence<0.3` 且 `evidence_count=1` 且 `last_validated_at > 90d` → 自动 `_pending=true` + 降权 `confidence=0.2`

> **绝对不要**：单条反馈直接写 `source=explicit, confidence=0.8`——这是幻觉路径。

---

## 5. 写入路径（核心 LLM 必须遵守）

```python
# 显式路径（用户说"以后…"）
update_item(name="user_model",
            key="preference.notify.window",
            value={
                "value": {"start": "22:00", "end": "08:00", "tz": "Asia/Shanghai"},
                "confidence": 0.5,
                "evidence_count": 2,
                "last_validated_at": time.time(),
                "source": "explicit"
            })

# 推断路径（核心从模式识别推断）
# 24h 反对方可落库——先写 running_items：
create_item(name="running_items",
            key="proposed.spam.marketing",
            value="观察到您对营销邮件普遍 dismissed 5 次，建议 tolerance.spam.marketing=0，回复'撤销'则不写")
# 24h 无反对 → 落 user_model（confidence=0.6, source=inferred）
```

---

## 6. 读取路径（state_block + MCP）

### 6.1 注入块（每回合自动）

```python
def _user_model_summary(min_conf=0.4, budget=300) -> str:
    rows = list_mcp.read(name="user_model", min_confidence=min_conf, order="confidence DESC")
    if not rows:
        return ""
    lines = []
    total = 0
    for r in rows:
        entry = json.loads(r["value_json"])
        line = f"  {r['key']}={json.dumps(entry['value'], ensure_ascii=False)[:50]} (conf={entry['confidence']:.2f}, n={entry['evidence_count']})"
        if total + len(line) > budget:
            lines.append("  …(剩余 slot 已隐去，需主动查 MCP)")
            break
        lines.append(line)
        total += len(line)
    return "[user_model]\n" + "\n".join(lines)
```

> **注入规则**：`confidence ≥ 0.4` 才进注入块（其余需主动 MCP 查）。

### 6.2 MCP 工具（按需查）

```python
# 核心裁决时如需完整 slot
list_mcp.read(name="user_model",
              keys=["preference.tone", "tolerance.spam.marketing"],
              min_confidence=0.0)  # 主动查询时不过滤
```

---

## 7. 衰减与维护（凌晨日终任务）

| 任务 | 触发 | 实现 |
|---|---|---|
| 90d 降权 | 每天 04:00 | `evidence_count=1 AND last_validated_at < now-90d` → `confidence=0.2` |
| 聚类推断 | 每天 04:30 | 同类 dismissed ≥3 → 候选 slot 写 `user_model_proposed`，等核心下次看到时确认 |
| A/B 模板胜出 | 每周一 04:00 | 按 `summary_hash` 配对，dismissed_rate 低者胜出 → 写 `preference.summary.template` |
| 归档 | 每天 04:45 | `thread_snapshots.turn_seq > 50` → archive 表 |

---

## 8. 边界

| 场景 | 行为 |
|---|---|
| 核心想写"我觉得用户喜欢 X"（无证据） | **拒绝**，记 `running_items.proposed.*`，等证据 |
| 核心想读 slot 但 `confidence<0.4` | MCP 返回空，核心必须显式 `read_user_model(slots=[...])` 才看得到 |
| 用户说"忘掉" | `DELETE FROM lists WHERE name='user_model' AND key=?` |
| slot 值类型不匹配（如 tone 写成字符串） | 写时 schema 校验失败 → 403，**绝不** 静默落 |

---

## 9. 与其他层的关系

```
Working (本回合可见)
   ↓ 写入 running_items
Contextual (同 thread 近期)
   ↓ 24h 模式聚合
Long-term (跨议题稳定) ←  本文件
   ↓ 反馈回路更新
feedback_events 表 (贝叶斯校准源)
```

> **不可直接跳跃**：Contextual 不能直接写 Long-term——必须经 feedback_events 累积。
