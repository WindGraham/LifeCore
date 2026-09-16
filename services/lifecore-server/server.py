#!/usr/bin/env python3
"""
lifecore-server — LifeCore 外围单进程（终审拓扑：pairing+BFF / registrar / inbox+notify / core_adapter）
设计依据：docs/02 §3、§13、§14；contracts/agent.md v0.1；docs/06 定型决议。
原则：无状态 BFF 门面 + SQLite 单一数据源；Hermes 零修改（只通过 webhook CLI 与回环 HTTP 交互）。
"""
from __future__ import annotations

import base64
import logging
import hashlib
import hmac as hmac_mod
import json
import os
import secrets
import sqlite3
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx
import segno
import uvicorn
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, Response, StreamingResponse

# ───────────────────────── 配置 ─────────────────────────
DATA_DIR = Path(os.environ.get("LC_DATA_DIR", "/opt/lifecore/data"))
DB_PATH = DATA_DIR / "lifecore.db"
KEY_PATH = DATA_DIR / "server_key.pem"
PUBLIC_BASE = os.environ.get("LC_PUBLIC_BASE", "https://windgraham.art").rstrip("/")
BIND_HOST = os.environ.get("LC_BIND_HOST", "127.0.0.1")
PORT = int(os.environ.get("LC_PORT", "8790"))
HERMES_BASE = os.environ.get("LC_HERMES_BASE", "http://127.0.0.1:8644")
HERMES_HOME = os.environ.get("LC_HERMES_HOME", "/root/.hermes")
AGENT_MD_PATH = Path(os.environ.get("LC_AGENT_MD", "/opt/lifecore/LifeCore/contracts/agent.md"))
STATIC_DIR = Path(os.environ.get("LC_STATIC_DIR", str(Path(__file__).parent / "static")))
RESTORE_MODE = os.environ.get("LC_RESTORE_MODE", "0") == "1"
API_SERVER_BASE = os.environ.get("LC_API_SERVER_BASE", "http://127.0.0.1:8642")
API_SERVER_KEY = os.environ.get("LC_API_SERVER_KEY", "")
MINIMAX_BASE = os.environ.get("LC_MINIMAX_BASE", "https://api.minimaxi.com")
MINIMAX_KEY = os.environ.get("LC_MINIMAX_KEY", "")
MINIMAX_TTS_MODEL = os.environ.get("LC_MINIMAX_TTS_MODEL", "speech-2.8-hd")
MINIMAX_TTS_VOICE = os.environ.get("LC_MINIMAX_TTS_VOICE", "male-qn-jingying")
PAIR_CODE_TTL = 600          # 10 分钟（docs/02 §5）
SIG_TOLERANCE = 300          # HMAC V2 ±300s（agent.md §2.2）

DATA_DIR.mkdir(parents=True, exist_ok=True)

# ───────────────────────── SQLite ─────────────────────────
def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=5000")
    return conn

def init_db() -> None:
    conn = db()
    conn.executescript("""
    CREATE TABLE IF NOT EXISTS devices(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      token_hash TEXT NOT NULL UNIQUE,
      created_at REAL NOT NULL,
      last_seen REAL
    );
    CREATE TABLE IF NOT EXISTS pair_codes(
      code TEXT PRIMARY KEY,
      expires_at REAL NOT NULL,
      used INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS channels(
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL UNIQUE,
      archetype TEXT NOT NULL DEFAULT 'message',
      direction TEXT NOT NULL DEFAULT 'source',
      uplink_level TEXT NOT NULL DEFAULT 'AB',
      report_policy TEXT NOT NULL DEFAULT '{}',
      session_discriminator TEXT,
      route_name TEXT NOT NULL UNIQUE,
      secret TEXT NOT NULL,
      created_at REAL NOT NULL,
      revoked INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS events(
      seq INTEGER PRIMARY KEY AUTOINCREMENT,
      channel_id TEXT NOT NULL,
      payload TEXT NOT NULL,
      upstream_status TEXT,
      received_at REAL NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_events_channel ON events(channel_id);
    CREATE TABLE IF NOT EXISTS notify_items(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      event_seq INTEGER NOT NULL,
      channel_id TEXT NOT NULL,
      state TEXT NOT NULL DEFAULT 'queued',   -- queued | awaiting_feedback | resolved(logged/actioned/dismissed/snoozed/expired)
      summary TEXT,
      options TEXT NOT NULL DEFAULT '[]',
      requires_feedback INTEGER NOT NULL DEFAULT 0,
      priority TEXT NOT NULL DEFAULT 'normal',
      resolution TEXT,
      created_at REAL NOT NULL,
      resolved_at REAL
    );
    CREATE INDEX IF NOT EXISTS idx_notify_state ON notify_items(state);
    -- 下行命令队列（sink 通道执行臂：MCP/核心入队 → 边缘服务长轮询拉走 → 回执）
    CREATE TABLE IF NOT EXISTS device_commands(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id INTEGER NOT NULL,
      action TEXT NOT NULL,
      args_json TEXT NOT NULL DEFAULT '{}',
      state TEXT NOT NULL DEFAULT 'pending',   -- pending | running | done | failed
      result_json TEXT,
      error TEXT,
      created_at REAL NOT NULL,
      updated_at REAL NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_cmd_dev_state ON device_commands(device_id, state);
    -- 边缘桥心跳（google-bridge 等 sink 执行器的健康面）
    CREATE TABLE IF NOT EXISTS bridge_heartbeat(
      device_id INTEGER PRIMARY KEY,
      name TEXT NOT NULL,
      detail_json TEXT NOT NULL DEFAULT '{}',
      last_seen REAL NOT NULL
    );
    """)
    # lists 表（六清单本体：user_state/running_items/schedule/...）
    conn.execute("""CREATE TABLE IF NOT EXISTS lists(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      key TEXT NOT NULL,
      value_json TEXT NOT NULL,
      created_at REAL NOT NULL,
      updated_at REAL NOT NULL,
      resolved INTEGER NOT NULL DEFAULT 0,
      UNIQUE(name, key))""")
    # 迁移：host 字段（哪台设备——接入服务清单的必需维度）
    try:
        conn.execute("ALTER TABLE channels ADD COLUMN host TEXT NOT NULL DEFAULT ''")
        conn.commit()
    except sqlite3.OperationalError:
        pass
    conn.commit()
    conn.close()

# ───────────────────────── 服务器身份（Ed25519 + 指纹）─────────────────────────
def load_or_create_keypair() -> tuple[Ed25519PrivateKey, str]:
    if KEY_PATH.exists():
        priv = serialization.load_pem_private_key(KEY_PATH.read_bytes(), password=None)
        assert isinstance(priv, Ed25519PrivateKey)
    else:
        priv = Ed25519PrivateKey.generate()
        KEY_PATH.write_bytes(priv.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption()))
        os.chmod(KEY_PATH, 0o600)
    pub_raw = priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    fp = base64.urlsafe_b64encode(pub_raw).decode().rstrip("=")
    return priv, fp

PRIV_KEY, FINGERPRINT = load_or_create_keypair()

def now() -> float:
    return time.time()

def iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).isoformat()

# ───────────────────────── core_adapter：敲 Hermes 门的唯一接缝 ─────────────────────────
def hermes_cli(*args: str) -> subprocess.CompletedProcess:
    """调用 hermes CLI（webhook subscribe/remove 热加载机制，docs/04 §10）。"""
    env = dict(os.environ, HOME=HERMES_HOME, HERMES_HOME=HERMES_HOME)
    return subprocess.run(["hermes", *args], capture_output=True, text=True, timeout=60, env=env)

async def forward_to_hermes(route_name: str, body: bytes, ts: str, sig: str, req_id: str | None) -> tuple[int, str]:
    """把已验签事件原样转发给 hermes webhook（同源签名头，hermes 复验）。"""
    headers = {"Content-Type": "application/json",
               "X-Webhook-Signature-V2": sig, "X-Webhook-Timestamp": ts}
    if req_id:
        headers["X-Request-ID"] = req_id
    try:
        async with httpx.AsyncClient(timeout=15) as cli:
            r = await cli.post(f"{HERMES_BASE}/webhooks/{route_name}", content=body, headers=headers)
            return r.status_code, r.text[:500]
    except Exception as e:  # hermes 不在也不丢事件：已留档
        return 502, f"hermes_unreachable: {e}"

# ───────────────────────── FastAPI ─────────────────────────
logger = logging.getLogger("lifecore")
app = FastAPI(title="lifecore-server", docs_url=None, redoc_url=None)
init_db()

def auth_device(request: Request) -> sqlite3.Row:
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        raise HTTPException(401, "missing bearer token")
    th = hashlib.sha256(auth[7:].encode()).hexdigest()
    conn = db()
    dev = conn.execute("SELECT * FROM devices WHERE token_hash=?", (th,)).fetchone()
    if dev:
        conn.execute("UPDATE devices SET last_seen=? WHERE id=?", (now(), dev["id"]))
        conn.commit()
    conn.close()
    if not dev:
        raise HTTPException(401, "invalid device token")
    return dev

def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")

# ── 配对（docs/02 §5：QR = {v,url,fp,code}；code 一次性 10 分钟）──
PAIR_PAGE = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LifeCore 配对</title>
<style>body{font-family:system-ui;background:#0d1117;color:#e6edf3;display:flex;flex-direction:column;align-items:center;padding:2rem}
.qr{background:#fff;padding:16px;border-radius:12px;margin:1rem}code{background:#161b22;padding:2px 6px;border-radius:6px}
.fp{font-size:.8rem;word-break:break-all;max-width:340px;color:#8b949e}</style></head>
<body><h2>LifeCore 设备配对</h2>
<p>用 LifeCore App 扫描下方二维码（10 分钟内有效，自动刷新）</p>
<div class="qr" id="qr">加载中…</div>
<p>服务器指纹（核对防假冒）：</p><p class="fp" id="fp">%FP%</p>
<p style="color:#8b949e;font-size:.85rem">无 App？curl 模拟：<br>
<code>curl -X POST %BASE%/v1/pair -d '{"code":"&lt;CODE&gt;","device_name":"test"}'</code></p>
<script>
async function refresh(){
  const r = await fetch('%BASE%/pair/code'); const j = await r.json();
  const q = await fetch('%BASE%/pair/qr?code=' + j.code); const svg = await q.text();
  document.getElementById('qr').innerHTML = svg;
  document.querySelector('code').textContent =
    "curl -X POST %BASE%/v1/pair -H 'Content-Type: application/json' -d '{\\"code\\":\\"" + j.code + "\\",\\"device_name\\":\\"test\\"}'";
}
refresh(); setInterval(refresh, 90000);
</script></body></html>"""

@app.get("/health")
def health() -> dict:
    return {"ok": True, "fp": FINGERPRINT, "time": iso(now()), "restore_mode": RESTORE_MODE}

@app.get("/pair", response_class=HTMLResponse)
def pair_page() -> str:
    return PAIR_PAGE.replace("%FP%", FINGERPRINT).replace("%BASE%", PUBLIC_BASE)

@app.get("/pair/code")
def pair_code() -> dict:
    code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(8))
    conn = db()
    conn.execute("INSERT INTO pair_codes(code,expires_at) VALUES(?,?)", (code, now() + PAIR_CODE_TTL))
    conn.execute("DELETE FROM pair_codes WHERE expires_at < ?", (now(),))
    conn.commit(); conn.close()
    return {"code": code, "expires_in": PAIR_CODE_TTL}

@app.get("/pair/qr")
def pair_qr(code: str) -> Response:
    payload = json.dumps({"v": 1, "url": PUBLIC_BASE, "fp": FINGERPRINT, "code": code}, separators=(",", ":"))
    svg = segno.make(payload, error="m").svg_inline(border=2, scale=6)
    return Response(svg, media_type="image/svg+xml")

@app.post("/v1/pair")
async def pair_submit(req: Request) -> dict:
    body = await req.json()
    code, name = str(body.get("code", "")), str(body.get("device_name", "device"))[:64]
    conn = db()
    row = conn.execute("SELECT * FROM pair_codes WHERE code=? AND used=0", (code,)).fetchone()
    if not row or row["expires_at"] < now():
        conn.close(); raise HTTPException(401, "invalid or expired pairing code")
    conn.execute("UPDATE pair_codes SET used=1 WHERE code=?", (code,))
    token = secrets.token_urlsafe(32)
    conn.execute("INSERT INTO devices(name,token_hash,created_at) VALUES(?,?,?)",
                 (name, hashlib.sha256(token.encode()).hexdigest(), now()))
    conn.commit(); conn.close()
    return {"device_token": token, "fingerprint": FINGERPRINT, "api_base": PUBLIC_BASE}

# ── 契约文件（唯一永恒常量，docs/02 公理一）──
@app.get("/.well-known/agent.md", response_class=PlainTextResponse)
def agent_md() -> str:
    if AGENT_MD_PATH.exists():
        return AGENT_MD_PATH.read_text(encoding="utf-8")
    raise HTTPException(404, "agent.md not deployed")

# ── registrar：通道注册（agent.md §2.1）──
@app.post("/v1/channels")
def create_channel(req: Request, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    body = req.scope.get("_json") or {}
    name = str(body.get("name", "")).strip()[:64]
    if not name:
        raise HTTPException(400, "name required")
    archetype = str(body.get("archetype", "message"))
    direction = str(body.get("direction", "source"))
    uplink = str(body.get("uplink_level", "AB"))
    rp = json.dumps(body.get("report_policy", {}), ensure_ascii=False)
    disc = body.get("session", {}).get("discriminator") if isinstance(body.get("session"), dict) else None
    host = str(body.get("host", ""))[:128]
    script = str(body.get("hermes_script", ""))[:128]
    if ".." in script or script.startswith("/"):
        raise HTTPException(400, "bad hermes_script")
    conn = db()
    if conn.execute("SELECT 1 FROM channels WHERE name=? AND revoked=0", (name,)).fetchone():
        conn.close(); raise HTTPException(409, "channel name exists")
    ch_id = "ch_" + secrets.token_hex(4)
    route_name = f"lc_{ch_id}"
    secret = "lc_" + secrets.token_hex(24)
    # hermes webhook subscribe：热加载，免重启（docs/04 §10）
    subscribe_args = ["webhook", "subscribe", route_name,
                      "--prompt", "{__raw__}",
                      "--deliver", "log",
                      "--secret", secret,
                      "--description", f"lifecore channel {name}"]
    if script:
        subscribe_args += ["--script", script]
    proc = hermes_cli(*subscribe_args)
    if proc.returncode != 0:
        conn.close()
        raise HTTPException(502, f"hermes subscribe failed: {proc.stderr[:300]}")
    conn.execute("""INSERT INTO channels(id,name,archetype,direction,uplink_level,report_policy,
                    session_discriminator,host,route_name,secret,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                 (ch_id, name, archetype, direction, uplink, rp, disc, host, route_name, secret, now()))
    conn.commit(); conn.close()
    return {"channel_id": ch_id, "ingest_url": f"{PUBLIC_BASE}/hk/{ch_id}",
            "secret": secret, "host": host, "created": iso(now())}

@app.get("/v1/channels/{ch_id}")
def channel_stats(ch_id: str, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    ch = conn.execute("SELECT * FROM channels WHERE id=?", (ch_id,)).fetchone()
    if not ch:
        conn.close(); raise HTTPException(404, "channel not found")
    stats = conn.execute("SELECT COUNT(*) c, MAX(received_at) last FROM events WHERE channel_id=?", (ch_id,)).fetchone()
    conn.close()
    return {"channel_id": ch_id, "name": ch["name"], "archetype": ch["archetype"],
            "host": ch["host"] if "host" in ch.keys() else "",
            "uplink_level": ch["uplink_level"], "revoked": bool(ch["revoked"]),
            "event_count": stats["c"], "last_event_at": iso(stats["last"]) if stats["last"] else None}

@app.delete("/v1/channels/{ch_id}")
def delete_channel(ch_id: str, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    ch = conn.execute("SELECT * FROM channels WHERE id=? AND revoked=0", (ch_id,)).fetchone()
    if not ch:
        conn.close(); raise HTTPException(404, "channel not found")
    proc = hermes_cli("webhook", "remove", ch["route_name"])
    conn.execute("UPDATE channels SET revoked=1 WHERE id=?", (ch_id,))
    conn.commit(); conn.close()
    return {"revoked": True, "hermes_remove_rc": proc.returncode}

# ── 数据面：HMAC 验签上行（agent.md §2.2）+ 留档 + 转发 + notify 入队 ──
def verify_sig(secret: str, body: bytes, headers) -> None:
    sig = headers.get("x-webhook-signature-v2", "")
    ts = headers.get("x-webhook-timestamp", "")
    if not sig or not ts:
        raise HTTPException(401, "missing signature headers")
    try:
        ts_i = int(ts)
    except ValueError:
        raise HTTPException(401, "bad timestamp")
    if abs(now() - ts_i) > SIG_TOLERANCE:
        raise HTTPException(401, "timestamp outside ±300s")
    expected = hmac_mod.new(secret.encode(), ts.encode() + b"." + body, hashlib.sha256).hexdigest()
    if not hmac_mod.compare_digest(sig, expected):
        raise HTTPException(401, "signature mismatch")

def promote_notify(conn: sqlite3.Connection) -> None:
    """单活动锁（docs/02 §14）：无活动项时把最老的 queued 提为 awaiting_feedback。"""
    busy = conn.execute("SELECT 1 FROM notify_items WHERE state='awaiting_feedback' LIMIT 1").fetchone()
    if busy:
        return
    nxt = conn.execute("""SELECT id FROM notify_items WHERE state='queued' AND requires_feedback=1
                          ORDER BY id LIMIT 1""").fetchone()
    if nxt:
        conn.execute("UPDATE notify_items SET state='awaiting_feedback' WHERE id=?", (nxt["id"],))

def enqueue_notify(conn: sqlite3.Connection, ch, payload: dict, seq: int) -> None:
    mode = "notify"
    try:
        mode = (json.loads(ch["report_policy"]) or {}).get("mode", "notify")
    except Exception:
        pass
    if mode == "silent":
        return
    req_fb = 1 if payload.get("requires_feedback") else 0
    summary = str(payload.get("summary", ""))[:500] or None
    options = json.dumps(payload.get("feedback_options", []), ensure_ascii=False)
    state = "queued" if req_fb else "logged"
    conn.execute("""INSERT INTO notify_items(event_seq,channel_id,state,summary,options,
                    requires_feedback,priority,created_at)
                    VALUES(?,?,?,?,?,?,?,?)""",
                 (seq, ch["id"], state, summary, options, req_fb,
                  str(payload.get("suggested_priority", "normal"))[:16], now()))
    if req_fb:
        promote_notify(conn)

@app.post("/hk/{ch_id}")
async def ingest(ch_id: str, request: Request) -> dict:
    body = await request.body()
    conn = db()
    ch = conn.execute("SELECT * FROM channels WHERE id=? AND revoked=0", (ch_id,)).fetchone()
    if not ch:
        conn.close(); raise HTTPException(404, "unknown channel")
    verify_sig(ch["secret"], body, request.headers)
    try:
        payload = json.loads(body)
    except Exception:
        payload = {"raw": body.decode("utf-8", "replace")[:2000]}
    cur = conn.execute("INSERT INTO events(channel_id,payload,received_at) VALUES(?,?,?)",
                       (ch_id, json.dumps(payload, ensure_ascii=False), now()))
    seq = cur.lastrowid
    enqueue_notify(conn, ch, payload if isinstance(payload, dict) else {}, seq)
    conn.commit()
    # 转发 hermes（agent.md §2.2 同源签名；hermes 复验后按 route 规则处理）
    status, text = await forward_to_hermes(
        ch["route_name"], body,
        request.headers.get("x-webhook-timestamp", ""),
        request.headers.get("x-webhook-signature-v2", ""),
        request.headers.get("x-request-id"))
    conn.execute("UPDATE events SET upstream_status=? WHERE seq=?", (f"{status}:{text[:200]}", seq))
    conn.commit(); conn.close()
    return {"status": "delivered", "seq": seq, "upstream": status}

@app.post("/v1/test-event")
async def test_event(req: Request, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    body = await req.json()
    ch_id = str(body.get("channel_id", ""))
    conn = db()
    ch = conn.execute("SELECT * FROM channels WHERE id=? AND revoked=0", (ch_id,)).fetchone()
    if not ch:
        conn.close(); raise HTTPException(404, "channel not found")
    payload = {"level": "B", "archetype": ch["archetype"], "pointer": "test",
               "summary": f"测试事件（{ch['name']}）", "suggested_priority": "normal"}
    cur = conn.execute("INSERT INTO events(channel_id,payload,upstream_status,received_at) VALUES(?,?,?,?)",
                       (ch_id, json.dumps(payload, ensure_ascii=False), "internal:test", now()))
    enqueue_notify(conn, ch, payload, cur.lastrowid)
    conn.commit(); conn.close()
    return {"status": "injected", "seq": cur.lastrowid}

# ── lists：清单读写（agent 走 MCP 工具 list_mcp.py；人/App 走这里）──
@app.get("/v2/lists")
def lists_all(name: str = "", dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    if name:
        rows = conn.execute("SELECT * FROM lists WHERE name=? ORDER BY updated_at DESC", (name,)).fetchall()
    else:
        rows = conn.execute("SELECT * FROM lists ORDER BY name, updated_at DESC").fetchall()
    conn.close()
    return {"lists": [{"list": r["name"], "key": r["key"], "value": json.loads(r["value_json"]),
            "resolved": bool(r["resolved"]), "created_at": iso(r["created_at"]),
            "updated_at": iso(r["updated_at"])} for r in rows]}

@app.put("/v2/lists/{name}/items/{key}")
def list_upsert(name: str, key: str, req: Request, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    body = req.scope.get("_json") or {}
    value = body.get("value", "")
    vj = json.dumps(value, ensure_ascii=False)
    t = now()
    conn = db()
    conn.execute("""INSERT INTO lists(name,key,value_json,created_at,updated_at)
                    VALUES(?,?,?,?,?)
                    ON CONFLICT(name,key) DO UPDATE SET value_json=?, updated_at=?, resolved=0""",
                 (name, key, vj, t, t, vj, t))
    conn.commit(); conn.close()
    return {"ok": True, "list": name, "key": key}

@app.post("/v2/lists/{name}/items/{key}/resolve")
def list_resolve(name: str, key: str, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    conn.execute("UPDATE lists SET resolved=1, updated_at=? WHERE name=? AND key=?", (now(), name, key))
    conn.commit(); conn.close()
    return {"ok": True, "resolved": f"{name}/{key}"}

@app.delete("/v2/lists/{name}/items/{key}")
def list_delete(name: str, key: str, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    conn.execute("DELETE FROM lists WHERE name=? AND key=?", (name, key))
    conn.commit(); conn.close()
    return {"ok": True, "deleted": f"{name}/{key}"}

# ── BFF：App 面（docs/06 接口原则；seq 单调游标，断网重连自动补齐）──
def notify_item_json(r: sqlite3.Row) -> dict:
    return {"id": r["id"], "event_seq": r["event_seq"], "channel_id": r["channel_id"],
            "state": r["state"], "summary": r["summary"], "options": json.loads(r["options"]),
            "requires_feedback": bool(r["requires_feedback"]), "priority": r["priority"],
            "resolution": r["resolution"], "created_at": iso(r["created_at"])}

@app.get("/v2/notify/active")
def notify_active(dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    active = conn.execute("SELECT * FROM notify_items WHERE state='awaiting_feedback' ORDER BY id LIMIT 1").fetchone()
    queue = conn.execute("SELECT * FROM notify_items WHERE state='queued' ORDER BY id LIMIT 20").fetchall()
    conn.close()
    return {"server_time": iso(now()),
            "active": notify_item_json(active) if active else None,
            "queue": [notify_item_json(r) for r in queue]}

@app.post("/v2/notify/items/{item_id}/feedback")
def notify_feedback(item_id: int, req: Request, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    body = req.scope.get("_json") or {}
    action = str(body.get("action", ""))
    if action not in ("actioned", "dismissed", "snooze"):
        raise HTTPException(400, "action must be actioned|dismissed|snooze")
    conn = db()
    item = conn.execute("SELECT * FROM notify_items WHERE id=? AND state='awaiting_feedback'", (item_id,)).fetchone()
    if not item:
        conn.close(); raise HTTPException(409, "item not awaiting feedback")
    conn.execute("UPDATE notify_items SET state='resolved', resolution=?, resolved_at=? WHERE id=?",
                 (action, now(), item_id))
    promote_notify(conn)
    nxt = conn.execute("SELECT * FROM notify_items WHERE state='awaiting_feedback' ORDER BY id LIMIT 1").fetchone()
    conn.commit(); conn.close()
    return {"resolved": action, "next_active": notify_item_json(nxt) if nxt else None}

@app.get("/v2/events")
def events_since(since: int = 0, limit: int = 200, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    rows = conn.execute("SELECT * FROM events WHERE seq>? ORDER BY seq LIMIT ?",
                        (since, min(limit, 1000))).fetchall()
    maxseq = conn.execute("SELECT COALESCE(MAX(seq),0) m FROM events").fetchone()["m"]
    conn.close()
    return {"server_time": iso(now()), "max_seq": maxseq,
            "events": [{"seq": r["seq"], "channel_id": r["channel_id"],
                        "payload": json.loads(r["payload"]), "at": iso(r["received_at"])} for r in rows]}

@app.get("/v2/me")
def me(dev: sqlite3.Row = Depends(auth_device)) -> dict:
    return {"device": dev["name"], "fingerprint": FINGERPRINT,
            "registered_at": iso(dev["created_at"]), "server_time": iso(now())}

# ── 下行命令队列 + 桥心跳（sink 执行臂；google-bridge 等边缘服务只出不进，主动长轮询）──
CMD_STALE_SEC = 300   # running 超时自动回收（bridge 崩溃兜底）

@app.get("/v2/commands/pending")
def commands_pending(wait: int = 0, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    """边缘服务取指令：返回本设备最老 pending 并标记 running；wait≤30s 长轮询。
    running 超过 CMD_STALE_SEC 自动回炉 pending（执行端掉线不丢令）。"""
    deadline = now() + min(max(wait, 0), 30)
    conn = db()
    try:
        while True:
            conn.execute("""UPDATE device_commands SET state='pending', updated_at=?
                            WHERE device_id=? AND state='running' AND updated_at<?""",
                         (now(), dev["id"], now() - CMD_STALE_SEC))
            conn.commit()
            row = conn.execute("""SELECT * FROM device_commands WHERE device_id=? AND state='pending'
                                  ORDER BY id LIMIT 1""", (dev["id"],)).fetchone()
            if row:
                conn.execute("UPDATE device_commands SET state='running', updated_at=? WHERE id=?",
                             (now(), row["id"]))
                conn.commit()
                return {"command": {"id": row["id"], "action": row["action"],
                                    "args": json.loads(row["args_json"] or "{}"),
                                    "created_at": iso(row["created_at"])}}
            if now() >= deadline:
                return {"command": None}
            time.sleep(1)
    finally:
        conn.close()

@app.post("/v2/commands/{cid}/result")
def command_result(cid: int, req: Request, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    body = req.scope.get("_json") or {}
    status = str(body.get("status", ""))
    if status not in ("done", "failed"):
        raise HTTPException(400, "status must be done|failed")
    conn = db()
    row = conn.execute("SELECT * FROM device_commands WHERE id=? AND device_id=?",
                       (cid, dev["id"])).fetchone()
    if not row or row["state"] != "running":
        conn.close()
        raise HTTPException(409, "command not running")
    conn.execute("""UPDATE device_commands SET state=?, result_json=?, error=?, updated_at=?
                    WHERE id=?""",
                 (status, json.dumps(body.get("result"), ensure_ascii=False)[:20000],
                  str(body.get("error", ""))[:1000], now(), cid))
    conn.commit(); conn.close()
    return {"ok": True, "id": cid, "status": status}

@app.get("/v2/commands/{cid}")
def command_get(cid: int, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    """MCP 侧查回执（含第三方代取的指令，只要是本设备名下）。"""
    conn = db()
    row = conn.execute("SELECT * FROM device_commands WHERE id=? AND device_id=?",
                       (cid, dev["id"])).fetchone()
    conn.close()
    if not row:
        raise HTTPException(404, "command not found")
    return {"id": row["id"], "action": row["action"], "state": row["state"],
            "result": json.loads(row["result_json"]) if row["result_json"] else None,
            "error": row["error"], "updated_at": iso(row["updated_at"])}

@app.post("/v2/bridge/heartbeat")
def bridge_heartbeat(req: Request, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    body = req.scope.get("_json") or {}
    name = str(body.get("name", dev["name"]))[:64]
    detail = json.dumps(body.get("detail", {}), ensure_ascii=False)[:2000]
    conn = db()
    conn.execute("""INSERT INTO bridge_heartbeat(device_id,name,detail_json,last_seen) VALUES(?,?,?,?)
                    ON CONFLICT(device_id) DO UPDATE SET name=?, detail_json=?, last_seen=?""",
                 (dev["id"], name, detail, now(), name, detail, now()))
    conn.commit(); conn.close()
    return {"ok": True}

@app.get("/v2/bridge/status")
def bridge_status(dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    row = conn.execute("SELECT * FROM bridge_heartbeat WHERE device_id=?", (dev["id"],)).fetchone()
    last_cmd = conn.execute("SELECT MAX(updated_at) m FROM device_commands WHERE device_id=?",
                            (dev["id"],)).fetchone()["m"]
    conn.close()
    if not row:
        return {"online": False}
    return {"online": now() - row["last_seen"] < 120, "name": row["name"],
            "detail": json.loads(row["detail_json"]), "last_seen": iso(row["last_seen"]),
            "last_command_at": iso(last_cmd) if last_cmd else None, "server_time": iso(now())}

# ── core_adapter 扩展：api_server 回环（会话/任务/cron 管控，BFF 唯一接缝）──
API_BASE = os.environ.get("LC_API_BASE", "http://127.0.0.1:8642")
HERMES_CONFIG = Path(os.environ.get("LC_HERMES_CONFIG", "/root/.hermes/config.yaml"))
HERMES_ENV = Path(HERMES_HOME) / ".env"

def api_server_key() -> str:
    if HERMES_ENV.exists():
        for line in HERMES_ENV.read_text().splitlines():
            if line.startswith("API_SERVER_KEY="):
                return line.split("=", 1)[1].strip().strip('"')
    raise HTTPException(500, "API_SERVER_KEY not found in hermes .env")

async def api_call(method: str, path: str, body: dict | None = None, raw: bytes | None = None,
                   headers_extra: dict | None = None):
    headers = {"Authorization": "Bearer " + api_server_key()}
    data = raw
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    headers.update(headers_extra or {})
    async with httpx.AsyncClient(timeout=120) as cli:
        r = await cli.request(method, API_BASE + path, content=data, headers=headers)
        try:
            return r.status_code, r.json()
        except Exception:
            return r.status_code, {"raw": r.text[:500]}

def gateway_state() -> dict:
    gs = Path(HERMES_HOME) / "gateway_state.json"
    if gs.exists():
        try:
            return json.loads(gs.read_text())
        except Exception:
            return {}
    return {}

# 网关状态总览
@app.get("/v2/gateway/status")
def gw_status(dev: sqlite3.Row = Depends(auth_device)) -> dict:
    st = gateway_state()
    return {"state": st.get("gateway_state"), "version": st.get("code_version"),
            "platforms": st.get("platforms", {}), "active_agents": st.get("active_agents"),
            "fingerprint": FINGERPRINT, "server_time": iso(now())}

# 会话：列表 / 新建 / 消息 / 对话 / 改名 / 删 / 锁模型（全部透传 api_server，App 不直连核心）
@app.get("/v2/sessions")
async def sessions(q: str = "", dev: sqlite3.Row = Depends(auth_device)):
    s, r = await api_call("GET", "/api/sessions" + ("?q=" + q if q else ""))
    return JSONResponse(r, status_code=s)

@app.post("/v2/sessions")
async def session_create(req: Request, dev: sqlite3.Row = Depends(auth_device)):
    body = req.scope.get("_json") or {}
    s, r = await api_call("POST", "/api/sessions", body)
    return JSONResponse(r, status_code=s)

@app.get("/v2/sessions/{sid}/messages")
async def session_messages(sid: str, dev: sqlite3.Row = Depends(auth_device)):
    s, r = await api_call("GET", f"/api/sessions/{sid}/messages")
    return JSONResponse(r, status_code=s)

@app.post("/v2/sessions/{sid}/chat")
async def session_chat(sid: str, req: Request, dev: sqlite3.Row = Depends(auth_device)):
    body = req.scope.get("_json") or {}
    if not body.get("message"):
        raise HTTPException(400, "message required")
    msg = str(body["message"])
    block = state_block()
    if block and not msg.startswith("[live "):
        msg = block + "\n" + msg
        logger.info("[state] injected: %s...", block[:120])
    s, r = await api_call("POST", f"/api/sessions/{sid}/chat", {"message": msg})
    return JSONResponse(r, status_code=s)

@app.patch("/v2/sessions/{sid}")
async def session_patch(sid: str, req: Request, dev: sqlite3.Row = Depends(auth_device)):
    s, r = await api_call("PATCH", f"/api/sessions/{sid}", req.scope.get("_json") or {})
    return JSONResponse(r, status_code=s)

@app.delete("/v2/sessions/{sid}")
async def session_delete(sid: str, dev: sqlite3.Row = Depends(auth_device)):
    s, r = await api_call("DELETE", f"/api/sessions/{sid}")
    return JSONResponse(r, status_code=s)

@app.post("/v2/sessions/{sid}/model")
async def session_model(sid: str, req: Request, dev: sqlite3.Row = Depends(auth_device)):
    s, r = await api_call("POST", f"/api/sessions/{sid}/model", req.scope.get("_json") or {})
    return JSONResponse(r, status_code=s)

# cron（/api/jobs 透传）
@app.get("/v2/jobs")
async def jobs(dev: sqlite3.Row = Depends(auth_device)):
    s, r = await api_call("GET", "/api/jobs")
    return JSONResponse(r, status_code=s)

@app.post("/v2/jobs")
async def job_create(req: Request, dev: sqlite3.Row = Depends(auth_device)):
    s, r = await api_call("POST", "/api/jobs", req.scope.get("_json") or {})
    return JSONResponse(r, status_code=s)

@app.post("/v2/jobs/{jid}/{action}")
async def job_action(jid: str, action: str, dev: sqlite3.Row = Depends(auth_device)):
    if action not in ("pause", "resume", "run"):
        raise HTTPException(400, "bad action")
    s, r = await api_call("POST", f"/api/jobs/{jid}/{action}", {})
    return JSONResponse(r, status_code=s)

@app.delete("/v2/jobs/{jid}")
async def job_delete(jid: str, dev: sqlite3.Row = Depends(auth_device)):
    s, r = await api_call("DELETE", f"/api/jobs/{jid}")
    return JSONResponse(r, status_code=s)

# 网关配置读写（channel_overrides / require_mention / mcp_servers 等；PUT 后重启 gateway，会话自动恢复）
CONFIG_ALLOWLIST = ("platforms", "mcp_servers", "display", "gateway")

def read_hermes_config() -> dict:
    import yaml
    if not HERMES_CONFIG.exists():
        return {}
    return yaml.safe_load(HERMES_CONFIG.read_text()) or {}

@app.get("/v2/admin/config")
def admin_config_get(dev: sqlite3.Row = Depends(auth_device)) -> dict:
    cfg = read_hermes_config()
    return {"config": {k: v for k, v in cfg.items() if k in CONFIG_ALLOWLIST},
            "config_path": str(HERMES_CONFIG), "server_time": iso(now())}

@app.put("/v2/admin/config")
def admin_config_put(req: Request, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    import yaml
    body = req.scope.get("_json") or {}
    patch = body.get("config", {})
    illegal = [k for k in patch if k not in CONFIG_ALLOWLIST]
    if illegal:
        raise HTTPException(400, f"keys not allowed: {illegal}")
    cfg = read_hermes_config()
    cfg.update(patch)
    HERMES_CONFIG.write_text(yaml.safe_dump(cfg, allow_unicode=True, sort_keys=False))
    proc = subprocess.run(["systemctl", "restart", "hermes-gateway"], capture_output=True, text=True, timeout=60)
    time.sleep(5)
    st = gateway_state()
    return {"written": list(patch.keys()), "restart_rc": proc.returncode,
            "gateway_state": st.get("gateway_state"), "version": st.get("code_version")}

@app.get("/v2/admin/mcp")
def mcp_list(dev: sqlite3.Row = Depends(auth_device)) -> dict:
    return {"mcp_servers": read_hermes_config().get("mcp_servers", {})}

# 通道列表（BFF 视图）
@app.get("/v1/channels")
def channels_list(dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    rows = conn.execute("SELECT * FROM channels WHERE revoked=0 ORDER BY created_at").fetchall()
    conn.close()
    return {"channels": [{"channel_id": r["id"], "name": r["name"], "archetype": r["archetype"],
            "direction": r["direction"], "uplink_level": r["uplink_level"],
            "ingest_url": f"{PUBLIC_BASE}/hk/{r['id']}", "created_at": iso(r["created_at"])} for r in rows]}

# ── MiniMax 语音管线代理（key 只存服务端，App 不接触；已实测 api.minimaxi.com）──
MINIMAX_KEY = os.environ.get("LC_MINIMAX_KEY", "")
MINIMAX_HOST = os.environ.get("LC_MINIMAX_HOST", "https://api.minimaxi.com")

@app.post("/v2/tts")
async def tts(req: Request, dev: sqlite3.Row = Depends(auth_device)) -> Response:
    body = req.scope.get("_json") or {}
    text, voice = str(body.get("text", ""))[:2000], str(body.get("voice", "male-qn-qingse"))
    if not text:
        raise HTTPException(400, "text required")
    if not MINIMAX_KEY:
        raise HTTPException(500, "LC_MINIMAX_KEY not configured")
    payload = {"model": "speech-01-240228", "text": text, "stream": False,
               "voice_setting": {"voice_id": voice, "speed": 1.0, "vol": 1.0, "pitch": 0}}
    async with httpx.AsyncClient(timeout=60) as cli:
        r = await cli.post(f"{MINIMAX_HOST}/v1/t2a_v2",
                           headers={"Authorization": f"Bearer {MINIMAX_KEY}"}, json=payload)
    j = r.json()
    audio_hex = (j.get("data") or {}).get("audio")
    if not audio_hex:
        raise HTTPException(502, f"minimax error: {j.get('base_resp')}")
    return Response(bytes.fromhex(audio_hex), media_type="audio/mpeg")

@app.post("/v2/stt")
async def stt(request: Request, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    if not MINIMAX_KEY:
        raise HTTPException(500, "LC_MINIMAX_KEY not configured")
    audio = await request.body()
    if not audio or len(audio) > 20 * 1024 * 1024:
        raise HTTPException(400, "audio required (<=20MB)")
    async with httpx.AsyncClient(timeout=120) as cli:
        r = await cli.post(f"{MINIMAX_HOST}/v1/speech_to_text",
                           headers={"Authorization": f"Bearer {MINIMAX_KEY}"},
                           data={"model": "asr-1.0", "response_format": "json"},
                           files={"file": ("voice.m4a", audio, "audio/mp4")})
    j = r.json()
    if "text" not in j:
        raise HTTPException(502, f"minimax asr error: {str(j)[:300]}")
    return {"text": j["text"], "duration": j.get("duration")}

# ── 灾难恢复骨架（docs/02 §8；restore 需显式开启 + 用户确认）──
@app.post("/v1/restore")
async def restore(req: Request) -> dict:
    if not RESTORE_MODE:
        raise HTTPException(403, "server not in restore mode")
    backup = await req.body()
    (DATA_DIR / "restored_backup.enc").write_bytes(backup)
    return {"received_bytes": len(backup),
            "note": "备份已存；恢复后请重启 lifecore-server 并关闭 restore 模式"}

# JSON body 缓存进 scope（Depends 里同步取）
@app.middleware("http")
async def cache_json(request: Request, call_next):
    if request.method in ("POST", "PUT") and "application/json" in request.headers.get("content-type", ""):
        try:
            request.scope["_json"] = json.loads(await request.body())
        except Exception:
            request.scope["_json"] = {}
    return await call_next(request)

# ── 每回合状态块（docs/02 §12.4：瞬时注入，位置=当前用户消息头部，缓存安全）──
FULL_LISTS = ["user_state", "running_items", "schedule"]   # 全文注入的清单
STATE_BUDGET = int(os.environ.get("LC_STATE_BUDGET", "2500"))  # 全文块字符预算

def state_block(dev_name: str = "") -> str:
    """每回合注入块（全文模式）：[live] 实时行 + 各清单全文（新→旧）+ 服务投影。
    位置=当前消息头部 → 提示词前缀缓存不受影响。"""
    try:
        conn = db()
        now_s = datetime.now(timezone.utc).astimezone().strftime("%m-%d %H:%M")
        sections = []
        parts = []
        act = conn.execute("SELECT id,summary FROM notify_items WHERE state='awaiting_feedback' ORDER BY id LIMIT 1").fetchone()
        qn = conn.execute("SELECT COUNT(*) c FROM notify_items WHERE state='queued'").fetchone()["c"]
        if act:
            parts.append(f"待决策:1({str(act['summary'] or '#'+str(act['id']))[:40]})")
        if qn:
            parts.append(f"队列:{qn}")
        ch = conn.execute("SELECT COUNT(*) c FROM channels WHERE revoked=0").fetchone()["c"]
        parts.append(f"通道:{ch}")
        sections.append("[live " + now_s + "] " + " | ".join(parts))
        # 清单全文（活跃条目，每张清单新→旧，各限 20 条）
        for lname in FULL_LISTS:
            rows = conn.execute("""SELECT key,value_json,updated_at FROM lists
                                   WHERE name=? AND resolved=0 ORDER BY updated_at DESC LIMIT 20""",
                                (lname,)).fetchall()
            if not rows:
                continue
            lines = []
            for r in rows:
                ts = datetime.fromtimestamp(r["updated_at"], timezone.utc).astimezone().strftime("%m-%d %H:%M")
                lines.append(f"  {r['key']}: {str(r['value_json'])[:120]} ({ts})")
            sections.append(f"[{lname}]\n" + "\n".join(lines))
        # 接入服务投影（注册表）
        svcs = conn.execute("SELECT name,host FROM channels WHERE revoked=0 ORDER BY name LIMIT 30").fetchall()
        if svcs:
            sections.append("[services]\n" + "\n".join(f"  {r['name']} @ {r['host'] or '?'}" for r in svcs))
        conn.close()
        block = "\n".join(sections)
        if len(block) > STATE_BUDGET:
            block = block[:STATE_BUDGET] + "\n…(截断)"
        return block
    except Exception:
        return ""

def inject_state(raw: bytes) -> bytes:
    """把状态块 prepend 到消息体 message/input 字段（BFF 唯一改写点）。"""
    try:
        body = json.loads(raw or b"{}")
        msg = body.get("message") or body.get("input") or ""
        block = state_block()
        if block and not str(msg).startswith("[live "):
            body["message"] = block + "\n" + msg
            logger.info("[state] injected: %s...", block[:120])
        return json.dumps(body, ensure_ascii=False).encode()
    except Exception:
        return raw

# ───────────────────── core_adapter：Hermes api_server 代理（App 会话/任务/模型）─────────────────────
async def api_server_proxy(method: str, path: str, body: bytes | None = None,
                           query: str = "") -> tuple[int, bytes, str]:
    if not API_SERVER_KEY:
        raise HTTPException(501, "api_server not configured (LC_API_SERVER_KEY)")
    url = f"{API_SERVER_BASE}{path}{query}"
    headers = {"Authorization": f"Bearer {API_SERVER_KEY}"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    try:
        async with httpx.AsyncClient(timeout=60) as cli:
            r = await cli.request(method, url, content=body, headers=headers)
            ct = r.headers.get("content-type", "application/json")
            if r.status_code == 401:
                # 上游 hermes 自己的 key 问题，与设备凭证无关：改写 502，
                # 保住"401 = 设备凭证失效"的语义（否则控制台会误触发解除配对）
                return (502,
                        json.dumps({"error": "upstream_auth",
                                    "detail": "hermes api_server rejected its own API key"}).encode(),
                        "application/json")
            return r.status_code, r.content, ct
    except httpx.ConnectError:
        raise HTTPException(502, "hermes api_server unreachable (enable platforms.api_server)")

@app.get("/v2/jobs")
async def v2_jobs(dev: sqlite3.Row = Depends(auth_device)):
    st, content, ct = await api_server_proxy("GET", "/api/jobs")
    return Response(content, status_code=st, media_type=ct)

@app.get("/v2/models")
async def v2_models(dev: sqlite3.Row = Depends(auth_device)):
    st, content, ct = await api_server_proxy("GET", "/v1/models")
    return Response(content, status_code=st, media_type=ct)

@app.get("/v2/gateway/capabilities")
async def v2_caps(dev: sqlite3.Row = Depends(auth_device)):
    st, content, ct = await api_server_proxy("GET", "/v1/capabilities")
    return Response(content, status_code=st, media_type=ct)

@app.get("/v1/channels")
def list_channels(dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    rows = conn.execute("SELECT id,name,archetype,direction,uplink_level,host,revoked,created_at FROM channels ORDER BY created_at DESC").fetchall()
    conn.close()
    return {"channels": [{"channel_id": r["id"], "name": r["name"], "archetype": r["archetype"],
            "direction": r["direction"], "uplink_level": r["uplink_level"], "host": r["host"],
            "revoked": bool(r["revoked"]), "created_at": iso(r["created_at"])} for r in rows]}

# ───────────────────── 语音管线：MiniMax ASR/TTS（key 只存服务端）─────────────────────
async def minimax_post(path: str, *, json_body: dict | None = None,
                       multipart: dict | None = None) -> httpx.Response:
    if not MINIMAX_KEY:
        raise HTTPException(501, "MiniMax not configured (LC_MINIMAX_KEY)")
    headers = {"Authorization": f"Bearer {MINIMAX_KEY}"}
    try:
        async with httpx.AsyncClient(timeout=120) as cli:
            if multipart is not None:
                files = {k: v for k, v in multipart.items() if k == "file"}
                data = {k: v for k, v in multipart.items() if k != "file"}
                return await cli.post(MINIMAX_BASE + path, headers=headers, data=data, files=files)
            return await cli.post(MINIMAX_BASE + path, headers=headers, json=json_body)
    except httpx.HTTPError as e:
        raise HTTPException(502, f"minimax unreachable: {e}")

@app.post("/v2/asr")
async def v2_asr(request: Request, dev: sqlite3.Row = Depends(auth_device)):
    """App 上传音频（m4a/mp3/wav/opus，禁止 webm/pcm）→ MiniMax asr-1.0 → 文本。"""
    audio = await request.body()
    if not audio:
        raise HTTPException(400, "empty audio")
    fmt = (request.query_params.get("format") or "json")
    level = (request.query_params.get("level") or "sentence")
    r = await minimax_post("/v1/speech_to_text", multipart={
        "model": "asr-1.0", "file": ("audio.m4a", audio, "audio/m4a"),
        "response_format": fmt, "timestamp_level": level, "stream": "false"})
    if r.status_code != 200:
        raise HTTPException(502, f"minimax asr error {r.status_code}: {r.text[:300]}")
    return JSONResponse(json.loads(r.text))

@app.post("/v2/tts")
async def v2_tts(req: Request, dev: sqlite3.Row = Depends(auth_device)):
    """文本 → MiniMax t2a_v2 → mp3 字节（App 直接播放）。"""
    body = req.scope.get("_json") or {}
    text = str(body.get("text", ""))[:2000]
    if not text:
        raise HTTPException(400, "text required")
    voice = str(body.get("voice", MINIMAX_TTS_VOICE))
    speed = float(body.get("speed", 1.0))
    r = await minimax_post("/v1/t2a_v2", json_body={
        "model": MINIMAX_TTS_MODEL, "text": text, "stream": False,
        "output_format": "hex", "language_boost": "auto",
        "voice_setting": {"voice_id": voice, "speed": speed, "vol": 1, "pitch": 0},
        "audio_setting": {"sample_rate": 32000, "bitrate": 128000, "format": "mp3", "channel": 1}})
    if r.status_code != 200:
        raise HTTPException(502, f"minimax tts error {r.status_code}: {r.text[:300]}")
    data = r.json().get("data", {})
    if not data.get("audio"):
        raise HTTPException(502, f"minimax tts empty audio: {r.text[:200]}")
    mp3 = bytes.fromhex(data["audio"])
    return Response(mp3, media_type="audio/mpeg")

@app.get("/v2/voices")
async def v2_voices(dev: sqlite3.Row = Depends(auth_device)):
    r = await minimax_post("/v1/get_voice", json_body={})
    return JSONResponse(r.json() if r.status_code == 200 else {"error": r.text[:300]})

# ── 会话流式对话（SSE 透传，App/控制台共用）──
@app.post("/v2/sessions/{sid}/chat/stream")
async def v2_session_chat_stream(sid: str, req: Request, dev: sqlite3.Row = Depends(auth_device)):
    if not API_SERVER_KEY:
        raise HTTPException(501, "api_server not configured")
    raw = inject_state(await req.body())
    async def upstream():
        try:
            async with httpx.AsyncClient(timeout=None) as cli:
                async with cli.stream("POST",
                    f"{API_SERVER_BASE}/api/sessions/{sid}/chat/stream",
                    content=raw,
                    headers={"Authorization": f"Bearer {API_SERVER_KEY}",
                             "Content-Type": "application/json"}) as r:
                    async for chunk in r.aiter_raw():
                        yield chunk
        except Exception as e:
            yield f"event: error\ndata: {{\"message\": \"proxy: {e}\"}}\n\n".encode()
    return StreamingResponse(upstream(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

# ── Web 控制台（全功能parity，免安装测试）──
@app.get("/console", response_class=HTMLResponse)
def console_page() -> str:
    f = STATIC_DIR / "console.html"
    if f.exists():
        return f.read_text(encoding="utf-8")
    raise HTTPException(404, "console not deployed")

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)  # root 无 handler 时 INFO 会被吞（lastResort 只收 WARNING+）
    uvicorn.run(app, host=BIND_HOST, port=PORT, log_level="info")
