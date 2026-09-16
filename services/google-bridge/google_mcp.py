#!/usr/bin/env python3
"""google_mcp — 核心 agent 的 Google 执行臂（stdio MCP，挂 hermes mcp_servers，跑在 VPS）。

链路：工具调用 → 指令入队 lifecore.db(device_commands) → 用户 PC 上 google-bridge
长轮询拉走 → 本地 gws CLI 执行 → 回执写回 → 本工具轮询取回。
与 list_mcp 同一 venv（/opt/lifecore/venv）。依赖 LC_DATA_DIR、LC_GOOGLE_DEVICE。

安全约定（SOUL.md 同步声明）：
  - 系统不直接发邮件：gmail 只起草（draft）；
  - gmail_trash / calendar_delete / calendar_update 为破坏性操作，调用前必须征得用户确认；
  - google_raw 是逃生门，仅在 curated 工具覆盖不了时使用，并要在回复中说明原因。
"""
import json
import os
import sqlite3
import time
from pathlib import Path

DB = Path(os.environ.get("LC_DATA_DIR", "/opt/lifecore/data")) / "lifecore.db"
DEVICE = os.environ.get("LC_GOOGLE_DEVICE", "google-bridge")
WAIT_TIMEOUT = int(os.environ.get("LC_GOOGLE_WAIT", "45"))   # 秒；超时返回 pending + id
RESULT_BUDGET = 2500

def _conn() -> sqlite3.Connection:
    c = sqlite3.connect(DB, timeout=10)
    c.row_factory = sqlite3.Row
    return c

def _device_id() -> int:
    conn = _conn()
    r = conn.execute("SELECT id FROM devices WHERE name=?", (DEVICE,)).fetchone()
    conn.close()
    if not r:
        raise RuntimeError(f"device '{DEVICE}' not paired on server")
    return int(r["id"])

def _enqueue(action: str, args: dict) -> int:
    conn = _conn()
    t = time.time()
    cur = conn.execute(
        "INSERT INTO device_commands(device_id,action,args_json,created_at,updated_at)"
        " VALUES(?,?,?,?,?)",
        (_device_id(), action, json.dumps(args, ensure_ascii=False), t, t))
    conn.commit()
    cid = int(cur.lastrowid)
    conn.close()
    return cid

def _get(cid: int) -> sqlite3.Row:
    conn = _conn()
    r = conn.execute("SELECT * FROM device_commands WHERE id=?", (cid,)).fetchone()
    conn.close()
    return r

def _fmt(cid: int, action: str, row: sqlite3.Row | None) -> str:
    if row is None:
        return f"#{cid} {action}: 指令不存在"
    st = row["state"]
    if st == "pending":
        return f"#{cid} {action}: 已下发待执行（bridge 未取走；可用 google_get_result({cid}) 查询）"
    if st == "running":
        return f"#{cid} {action}: 执行中（可用 google_get_result({cid}) 查询）"
    if st == "failed":
        return f"#{cid} {action}: 失败 — {row['error']}"
    res = row["result_json"]
    if not res:
        return f"#{cid} {action}: 完成（无返回）"
    s = res if len(res) <= RESULT_BUDGET else res[:RESULT_BUDGET] + "…(截断)"
    return f"#{cid} {action}: 完成\n{s}"

def _exec(action: str, args: dict, wait: int = WAIT_TIMEOUT) -> str:
    cid = _enqueue(action, args)
    deadline = time.time() + wait
    while time.time() < deadline:
        row = _get(cid)
        if row and row["state"] in ("done", "failed"):
            return _fmt(cid, action, row)
        time.sleep(1.5)
    return _fmt(cid, action, _get(cid))

def main() -> None:
    from mcp.server.fastmcp import FastMCP
    mcp = FastMCP("lifecore-google")

    @mcp.tool()
    def google_status() -> str:
        """Google 桥健康状态：在线与否、四个监听端状态、gws 认证、最近事件。任何 google_* 工具异常时先查它。"""
        conn = _conn()
        dev = conn.execute("SELECT id FROM devices WHERE name=?", (DEVICE,)).fetchone()
        if not dev:
            conn.close()
            return f"device '{DEVICE}' 未配对"
        hb = conn.execute("SELECT * FROM bridge_heartbeat WHERE device_id=?", (dev["id"],)).fetchone()
        pend = conn.execute("SELECT COUNT(*) c FROM device_commands WHERE device_id=? AND state IN ('pending','running')",
                            (dev["id"],)).fetchone()["c"]
        conn.close()
        if not hb:
            return f"桥未上报过心跳（从未启动？）pending={pend}"
        import time as _t
        age = _t.time() - hb["last_seen"]
        detail = json.loads(hb["detail_json"] or "{}")
        return (f"{'在线' if age < 120 else '离线(心跳%.0f秒前)' % age} | 待执行 {pend} | "
                f"监听 {detail.get('watchers')} | gws_auth={detail.get('gws_auth')} | "
                f"spool={detail.get('spool_depth')} | 最近事件 {detail.get('last_event')}")

    @mcp.tool()
    def google_get_result(command_id: int) -> str:
        """按指令号查回执（google_* 工具超时返回 pending 时用它取结果）。"""
        return _fmt(command_id, "", _get(command_id))

    # ── Gmail（只起草不发信；IMAP 秒级监听见 google-gmail 通道汇报）──
    @mcp.tool()
    def google_gmail_search(query: str, max_results: int = 10) -> str:
        """Gmail 搜索（语法同 Gmail 搜索框，如 from:x subject:y newer:7d）。返回邮件 id 列表（可用 google_get_original 读正文）。"""
        return _exec("gmail_search", {"query": query, "max_results": max(1, min(max_results, 25))})

    @mcp.tool()
    def google_gmail_read(message_id: str) -> str:
        """按邮件 id 读完整邮件（含正文）。id 来自 google_gmail_search 或通道汇报 pointer(gmail:<id>)。"""
        return _exec("gmail_read", {"message_id": message_id})

    @mcp.tool()
    def google_gmail_draft(to: str, subject: str, body: str) -> str:
        """创建草稿（不发送）。系统惯例：需要发信时先建草稿并告知用户去 Gmail 确认发送。"""
        return _exec("gmail_draft", {"to": to, "subject": subject, "body": body})

    @mcp.tool()
    def google_gmail_archive(message_id: str) -> str:
        """把邮件移出收件箱（归档，非删除）。"""
        return _exec("gmail_archive", {"message_id": message_id})

    @mcp.tool()
    def google_gmail_trash(message_id: str) -> str:
        """【破坏性】移入废件箱。必须先征得用户确认。"""
        return _exec("gmail_trash", {"message_id": message_id})

    # ── 日历 ──
    @mcp.tool()
    def google_calendar_list(time_min: str, time_max: str, max_results: int = 25) -> str:
        """列出某时间段日程（RFC3339，如 2026-10-01T00:00:00+08:00）。"""
        return _exec("calendar_list", {"time_min": time_min, "time_max": time_max,
                                       "max_results": max(1, min(max_results, 50))})

    @mcp.tool()
    def google_calendar_create(title: str, start: str, end: str, description: str = "") -> str:
        """创建日程（RFC3339 时间；不发邀请邮件）。桥端会自动跳过自产事件的监听上报。"""
        return _exec("calendar_create", {"title": title, "start": start, "end": end,
                                         "description": description})

    @mcp.tool()
    def google_calendar_update(event_id: str, fields_json: str) -> str:
        """【破坏性】改日程（fields_json 为 Calendar event 的 patch 体，如 {"summary":"新标题"}）。改期/改人必须先征得用户确认。"""
        return _exec("calendar_update", {"event_id": event_id, "fields_json": fields_json})

    @mcp.tool()
    def google_calendar_delete(event_id: str) -> str:
        """【破坏性】删日程。必须先征得用户确认。"""
        return _exec("calendar_delete", {"event_id": event_id})

    # ── Tasks（通道为 silent，靠工具查）──
    @mcp.tool()
    def google_tasks_list(show_completed: bool = False) -> str:
        """列出 Google Tasks 任务。"""
        return _exec("tasks_list", {"show_completed": show_completed})

    @mcp.tool()
    def google_tasks_create(title: str, notes: str = "", due: str = "") -> str:
        """新建任务（due 为 RFC3339，可空）。"""
        return _exec("tasks_create", {"title": title, "notes": notes, "due": due})

    @mcp.tool()
    def google_tasks_complete(task_id: str) -> str:
        """标记任务完成。task_id 来自 google_tasks_list 或汇报 pointer(task:<id>)。"""
        return _exec("tasks_complete", {"task_id": task_id})

    # ── Drive / 回查 / 逃生门 ──
    @mcp.tool()
    def google_drive_search(query: str, max_results: int = 10) -> str:
        """Drive 搜索（如 name contains '报告'）。"""
        return _exec("drive_search", {"query": query, "max_results": max(1, min(max_results, 25))})

    @mcp.tool()
    def google_get_original(pointer: str) -> str:
        """回查原文（A/B/C 铁律的回查工具）。pointer 格式：gmail:<id> / cal:<id> / drive:<id> / task:<id>，来自任何 google-* 通道汇报。"""
        return _exec("get_original", {"pointer": pointer})

    @mcp.tool()
    def google_raw(argv_json: str, timeout: int = 120) -> str:
        """【逃生门】裸 gws CLI（argv 数组 JSON，如 ["calendar","events","list","--params","{...}"]）。
        仅当 curated 工具覆盖不了时使用，并在回复中向用户说明为什么用它。"""
        argv = json.loads(argv_json)
        return _exec("raw", {"argv": argv, "timeout": min(int(timeout), 300)})

    mcp.run()  # stdio

if __name__ == "__main__":
    main()
