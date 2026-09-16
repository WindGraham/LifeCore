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

    mcp.run()  # stdio

if __name__ == "__main__":
    main()
