# Subagent Dispatch · 子 agent 分发模板

> 用途：核心 LLM 判定"需要隔离/并行/独立上下文"时，通过 `dispatch_subagent` 工具分发独立任务。
> 设计依据：docs/13 §3（本地优先）+ docs/15 §4（核心"知道它能做什么"）
> 加载位置：hermes `mcp_servers.subagent_dispatcher` 的 tool description + 本地脚本

---

## 1. 何时用子 agent（决策 Prompt 拼到 system append）

> **核心在以下三种场景调用 `dispatch_subagent`**——否则**禁止**：
>
> 1. **隔离** —— 任务可能弄脏当前 thread 的 contextual memory（如跨设备配置、SSH 操作预备）
> 2. **并行** —— 同一回合需要 ≥2 个独立 IO 等候（拉邮件 + 查日历 + 截图）
> 3. **独立上下文** —— 任务长达 ≥10 轮但不需要主线程状态（长报表生成、跨日整理）
>
> **不**调用的情况：
> - 单步 LLM 推理（直接做）
> - 短查询（直接调 MCP）
> - 需要持续与用户对话的任务（留在主线程）

---

## 2. 子 agent 调用模板（输入 JSON）

```json
{
  "task": "<一句话明确目标，必填>",
  "context": {
    "thread_key": "wx:8832",
    "user_state": "在写 docs/14",
    "must_have": ["不能装 APK", "必须 HMAC 签名"],
    "must_not": ["不删邮件", "不暴露设备 token"]
  },
  "tools_allowed": [
    "list_mcp.read",
    "list_mcp.update_item",
    "gws.users.get_profile",
    "screenshot.capture"
  ],
  "tools_forbidden": [
    "pm.install",
    "rm.account.token"
  ],
  "max_turns": 15,
  "timeout_sec": 300,
  "callback": {
    "type": "summary_to_running_items",
    "key": "subagent.result.<task_id>"
  },
  "red_lines_from_system": true
}
```

**字段含义**：

- `task` —— 一句话目标（不可省略）
- `context` —— 子 agent 的初始上下文（不带主线程历史）
- `tools_allowed` / `tools_forbidden` —— 显式枚举（**默认全部禁用**，白名单制）
- `max_turns` —— 上限（默认 15，> 30 需核心解释）
- `timeout_sec` —— 硬超时
- `callback` —— 子 agent 完成后的回写路径
- `red_lines_from_system=true` —— 自动注入 `system.md §4` 红线到子 agent system prompt

---

## 3. 子 agent 启动模板（hermes 侧）

```python
def dispatch_subagent(spec: dict) -> dict:
    """在独立 session 启动子 agent，60s 内返回 ack；后台 run。"""
    # 1. 校验 spec
    assert spec.get("task"), "task required"
    assert spec.get("red_lines_from_system"), "red_lines_from_system must be true"
    
    # 2. 拼子 agent 的 system prompt
    system = (
        open("/opt/lifecore/LifeCore/prompts/core-agent/system.md").read()  # 永驻部分
        + "\n\n## 你现在是子 agent（dispatched）\n"
        + f"任务：{spec['task']}\n"
        + f"上下文：{json.dumps(spec['context'], ensure_ascii=False)}\n"
        + f"允许工具：{spec.get('tools_allowed', [])}\n"
        + f"禁止工具：{spec.get('tools_forbidden', [])}\n"
        + f"最大回合：{spec.get('max_turns', 15)}\n"
        + "\n## 子 agent 纪律\n"
        + "- 任务明确完成即结束，不要无意义延展\n"
        + "- 任何 L3 副作用必须 5s 内可撤（rollback_cmd 必填）\n"
        + "- 失败 3 次同一动作 → 立刻回退报告，不再继续\n"
    )
    
    # 3. 创建独立 session，调 hermes CLI
    sid = _create_session(system=system, model="MiniMax-M3")
    _run_async(sid, spec)  # asyncio 后台跑
    
    return {"task_id": sid, "status": "dispatched", "ack": True}
```

---

## 4. 回传 schema（子 agent → 核心）

```json
{
  "task_id": "s_8f3a2b",
  "status": "done" | "failed" | "timeout" | "aborted",
  "summary": "<一句话核心结果，必填>",
  "artifacts": {
    "running_items_key": "subagent.result.<task_id>",
    "files_written": ["/tmp/result.txt"],
    "items_enqueued": [42, 43]
  },
  "errors": [
    {"tool": "gws.users.get_profile", "msg": "401 unauthorized"}
  ],
  "turns_used": 8,
  "elapsed_sec": 23.4
}
```

**回传路径**：
- 子 agent 跑完 → 写 `running_items[spec.callback.key]`（核心下次 state_block 看到）
- WS 推送 → App 端"今日已为你做完"卡片多一条
- 失败 → 核心本线程收到 `errors`，**核心自己接手回退**（见 §6）

---

## 5. 工具白名单/黑名单（默认 + 强禁）

**默认白名单**（任何子 agent 都可用）：
- `list_mcp.read` —— 读清单/通知/议题
- `list_mcp.search` —— 按 key 模式查
- `search_logs` —— 程序自身日志
- `get_status` —— 设备健康

**强禁（任何子 agent 都不可用，无论 `tools_allowed`）**：
- `pm.install` / `pm.uninstall`
- `am.force-stop` 对核心 App 自己
- `oauth.revoke`
- `email.delete`
- 任何 `ssh.*` 调用到边缘设备
- `hermes.config.put`（配置改动）

> 强禁在 dispatcher 端强制过滤，**子 agent 看不到这些工具描述**。

---

## 6. 失败回退策略

```python
def _on_subagent_failed(task_id, errors):
    """子 agent 失败：核心本线程接手，写 running_items 标注。"""
    summary = f"子任务 {task_id} 失败：{errors[0]['msg'][:120] if errors else 'unknown'}"
    list_mcp.create_item(
        name="running_items",
        key=f"subagent.failed.{task_id}",
        value=summary
    )
    # 不重试——核心直接接手或告诉用户
    return summary
```

**核心接手三选项**（写到 running_items）：

1. `subagent.failed.{task_id}.retry` —— 用户下回合可主动让核心重试
2. `subagent.failed.{task_id}.manual` —— 需用户手动操作（核心拼出步骤）
3. `subagent.failed.{task_id}.abandon` —— 放弃（核心说"我搞不定，跳过"）

---

## 7. 并行限制与公平

- **同时跑** 子 agent ≤ 3 个（核心主线程阻塞 1 + 后台 ≤ 2）
- 超 3 个 → FIFO 排队（`subagent_queue` 表）
- 单个核心主线程 1h 内 spawn ≤ 10 个子 agent（防滥用）

---

## 8. 边界

| 场景 | 行为 |
|---|---|
| 子 agent 需要主线程状态 | **不允许**——主线程不接受 callback，让子 agent 自己跑完 |
| 子 agent 写到 user_model | 仅写 `running_items.proposed.*`，24h 反对方可落库（与核心同纪律） |
| 子 agent 触发 L3 副作用 | 必须 `red_lines_from_system=true`，否则 dispatcher 拒绝启动 |
| 子 agent 超 `max_turns` | 强制 abort + `errors: [{tool: null, msg: "max_turns"}]` |

---

## 9. 与 system.md / memory_* 的关系

- 永驻 `system.md` → 红线由 `red_lines_from_system` 自动注入
- 子 agent 跑完后的回传 → 写 `running_items` → 下回合主线程 state_block 看到 → 核心自然纳入 working memory
- **子 agent 不写 long-term memory**——避免与核心决策冲突
