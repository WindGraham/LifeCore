#!/usr/bin/env python3
"""
lifecore-server — LifeCore 外围单进程（终审拓扑：pairing+BFF / registrar / inbox+notify / core_adapter）
设计依据：docs/02 §3、§13、§14；contracts/agent.md v0.1；docs/06 定型决议。
原则：无状态 BFF 门面 + SQLite 单一数据源；Hermes 零修改（只通过 webhook CLI 与回环 HTTP 交互）。
"""
from __future__ import annotations

import base64
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
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, Response

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
RESTORE_MODE = os.environ.get("LC_RESTORE_MODE", "0") == "1"
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
    """)
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
    conn = db()
    if conn.execute("SELECT 1 FROM channels WHERE name=? AND revoked=0", (name,)).fetchone():
        conn.close(); raise HTTPException(409, "channel name exists")
    ch_id = "ch_" + secrets.token_hex(4)
    route_name = f"lc_{ch_id}"
    secret = "lc_" + secrets.token_hex(24)
    # hermes webhook subscribe：热加载，免重启（docs/04 §10）
    proc = hermes_cli("webhook", "subscribe", route_name,
                      "--prompt", "{__raw__}",
                      "--deliver", "log",
                      "--secret", secret,
                      "--description", f"lifecore channel {name}")
    if proc.returncode != 0:
        conn.close()
        raise HTTPException(502, f"hermes subscribe failed: {proc.stderr[:300]}")
    conn.execute("""INSERT INTO channels(id,name,archetype,direction,uplink_level,report_policy,
                    session_discriminator,route_name,secret,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?)""",
                 (ch_id, name, archetype, direction, uplink, rp, disc, route_name, secret, now()))
    conn.commit(); conn.close()
    return {"channel_id": ch_id, "ingest_url": f"{PUBLIC_BASE}/hk/{ch_id}",
            "secret": secret, "created": iso(now())}

@app.get("/v1/channels/{ch_id}")
def channel_stats(ch_id: str, dev: sqlite3.Row = Depends(auth_device)) -> dict:
    conn = db()
    ch = conn.execute("SELECT * FROM channels WHERE id=?", (ch_id,)).fetchone()
    if not ch:
        conn.close(); raise HTTPException(404, "channel not found")
    stats = conn.execute("SELECT COUNT(*) c, MAX(received_at) last FROM events WHERE channel_id=?", (ch_id,)).fetchone()
    conn.close()
    return {"channel_id": ch_id, "name": ch["name"], "archetype": ch["archetype"],
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

if __name__ == "__main__":
    uvicorn.run(app, host=BIND_HOST, port=PORT, log_level="info")
