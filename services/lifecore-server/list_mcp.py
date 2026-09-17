#!/usr/bin/env python3
"""list_mcp — 核心 agent 写清单的 MCP 工具（stdio，挂 hermes mcp_servers）。
与 lifecore-server 共用同一 SQLite（LC_DATA_DIR）。只写不读全文——读由每回合注入块负责。"""
import json
import os
import sqlite3
import time
from pathlib import Path

DB = Path(os.environ.get("LC_DATA_DIR", "/opt/lifecore/data")) / "lifecore.db"

def _upsert(list_name: str, key: str, value) -> str:
    conn = sqlite3.connect(DB, timeout=10)
    t = time.time()
    vj = json.dumps(value, ensure_ascii=False)
    conn.execute("""INSERT INTO lists(name,key,value_json,created_at,updated_at)
                    VALUES(?,?,?,?,?)
                    ON CONFLICT(name,key) DO UPDATE SET value_json=?, updated_at=?, resolved=0""",
                 (list_name, key, vj, t, t, vj, t))
    conn.commit(); conn.close()
    return f"ok {list_name}/{key}"

def _resolve(list_name: str, key: str) -> str:
    conn = sqlite3.connect(DB, timeout=10)
    conn.execute("UPDATE lists SET resolved=1, updated_at=? WHERE name=? AND key=?", (time.time(), list_name, key))
    conn.commit(); conn.close()
    return f"resolved {list_name}/{key}"

def _thread_history(thread_key: str, n: int = 5) -> str:
    """查 notify_threads 得线程 id，返回最近 n 条 item 的 "id|created|summary|resolution"（新→旧）。"""
    conn = sqlite3.connect(DB, timeout=10)
    conn.row_factory = sqlite3.Row
    th = conn.execute("SELECT id FROM notify_threads WHERE thread_key=?", (thread_key.strip()[:128],)).fetchone()
    if not th:
        conn.close()
        return f"thread not found: {thread_key}"
    rows = conn.execute("""SELECT id,created_at,summary,resolution FROM notify_items
                           WHERE thread_id=? ORDER BY id DESC LIMIT ?""",
                        (th["id"], max(1, min(int(n), 20)))).fetchall()
    conn.close()
    return "\n".join(
        f"{r['id']}|{time.strftime('%m-%d %H:%M', time.localtime(r['created_at']))}|"
        f"{(r['summary'] or '')[:200]}|{r['resolution'] or '-'}" for r in rows) or "(empty)"

def main() -> None:
    from mcp.server.fastmcp import FastMCP
    mcp = FastMCP("lifecore-lists")

    @mcp.tool()
    def update_item(list_name: str, key: str, value: str) -> str:
        """更新清单条目（upsert，自动时间戳）。清单：user_state(用户状态) / running_items(运行中事项) / schedule(时间表)。"""
        return _upsert(list_name.strip()[:64], key.strip()[:128], value[:2000])

    @mcp.tool()
    def append_event(list_name: str, entry: str) -> str:
        """追加事件：key 自动取时间戳，适合 running_items 的流水记录。"""
        return _upsert(list_name.strip()[:64], time.strftime("%H%M%S"), entry[:2000])

    @mcp.tool()
    def resolve_item(list_name: str, key: str) -> str:
        """标记条目完成/结束（resolve 后不再出现在注入块里）。"""
        return _resolve(list_name.strip()[:64], key.strip()[:128])

    @mcp.tool()
    def thread_history(thread_key: str, n: int = 5) -> str:
        """查通知线程的决议历史（notify_threads/notify_items，非清单）。续报某议题时先调它拼上下文：
        返回该 thread_key 最近 n 条通知事项的 "id|时间|摘要|用户决议"（新→旧），
        resolution 列为用户上次的选择（actioned=已办/dismissed=忽略/snooze=稍后）。
        典型用法：用户说"稍后"过的话题再触发时，用此链写出"上次你说稍后，现在…"的续报文案。"""
        return _thread_history(thread_key, n)

    mcp.run()  # stdio

if __name__ == "__main__":
    main()
