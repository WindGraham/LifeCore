#!/usr/bin/env python3
"""
google-bridge — LifeCore 的 Google 边缘桥（跑在用户 PC，常驻，只出不进）。

职责（contracts/agent.md v0.1 + docs/06 定型决议）：
  上行（监听）：Gmail IMAP IDLE（秒级）+ Calendar/Drive/Tasks delta 轮询（gws CLI）
               → 初筛（B 级摘要，原文留 Google 侧）→ HMAC-V2 上报 VPS 通道。
  下行（执行）：长轮询 /v2/commands/pending → 本地 gws CLI 执行 → 回执。
               核心 agent 经 google_mcp.py（VPS，hermes MCP）入队。

纪律：
  - 纯标准库，零第三方依赖；NAT 后一切链路主动外拨（零入站端口）；
  - VPS 只存摘要+指针（A/B/C 铁律），原文经 get_original 现场回查；
  - 凭证两处：Gmail=应用专用密码（IMAP）；其余=gws CLI OAuth（~/.config/gws）。

用法：
  python3 google_bridge.py run [--config PATH]      # 常驻
  python3 google_bridge.py register --config PATH   # 注册四个通道并写回凭证
  python3 google_bridge.py selftest [--config PATH] # 自检（签名/spool/动作形状--dry-run/服务器连通）
  python3 google_bridge.py exec <action> '<json>'   # 单条指令执行后退出（调试用）
  python3 google_bridge.py send-test <channel>      # 发合成事件走真实管道
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac as hmac_mod
import imaplib
import json
import logging
import logging.handlers
import os
import secrets
import socket
import sqlite3
import subprocess
import threading
import time
import urllib.error
import urllib.request
from email.message import EmailMessage
from email.parser import Parser
from pathlib import Path

DEFAULT_CONFIG = Path(os.environ.get("GOOGLE_BRIDGE_CONFIG",
                                     "~/.config/google-bridge/config.json")).expanduser()
DEFAULT_DATA = Path(os.environ.get("GOOGLE_BRIDGE_DATA",
                                   "~/.local/state/google-bridge")).expanduser()

log = logging.getLogger("google-bridge")

# ───────────────────────── HTTP（显式禁代理，与本机代理环境隔离）─────────────────────────
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def http_json(method: str, url: str, token: str = "", body: dict | None = None,
              timeout: int = 40) -> tuple[int, dict]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with _OPENER.open(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {"error": str(e)}
    except Exception as e:
        return 0, {"error": f"{type(e).__name__}: {e}"}

# ───────────────────────── HMAC V2（agent.md §2.2）─────────────────────────
def sign(secret: str, body: bytes) -> dict[str, str]:
    ts = str(int(time.time()))
    sig = hmac_mod.new(secret.encode(), ts.encode() + b"." + body,
                       hashlib.sha256).hexdigest()
    return {"X-Webhook-Signature-V2": sig, "X-Webhook-Timestamp": ts}

def post_event(ingest_url: str, secret: str, payload: dict) -> tuple[int, str]:
    body = json.dumps(payload, ensure_ascii=False).encode()
    headers = {"Content-Type": "application/json", **sign(secret, body)}
    req = urllib.request.Request(ingest_url, data=body, method="POST")
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with _OPENER.open(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace")[:200]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:200]
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"

# ───────────────────────── 本地状态（游标/镜像/去重）─────────────────────────
class State:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(path, timeout=10, check_same_thread=False)
        self.conn.execute("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)")
        self.lock = threading.Lock()

    def get(self, k: str, default=None):
        with self.lock:
            r = self.conn.execute("SELECT v FROM kv WHERE k=?", (k,)).fetchone()
            return json.loads(r[0]) if r else default

    def set(self, k: str, v) -> None:
        with self.lock:
            self.conn.execute("INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=?",
                              (k, json.dumps(v), json.dumps(v)))
            self.conn.commit()

# ───────────────────────── 事件发送（spool 兜底，agent.md §4 断点续传）─────────────────────────
class Sender:
    def __init__(self, cfg: dict, state: State, data_dir: Path):
        self.cfg = cfg
        self.state = state
        self.spool = data_dir / "spool.jsonl"
        self.last_event: dict[str, float] = {}
        self.errors: dict[str, str] = {}

    def send(self, ch_key: str, payload: dict) -> bool:
        ch = (self.cfg.get("channels") or {}).get(ch_key)
        if not ch:
            self.errors[ch_key] = "channel not configured"
            return False
        payload.setdefault("source", f"google-{ch_key}")
        payload.setdefault("time", time.strftime("%Y-%m-%dT%H:%M:%S%z"))
        status, text = post_event(ch["ingest_url"], ch["secret"], payload)
        if status == 200:
            self.last_event[ch_key] = time.time()
            self.errors.pop(ch_key, None)
            return True
        log.warning("event %s post failed: %s %s", ch_key, status, text)
        if status in (401, 404):   # 凭证死/通道没：spool 也无用，但留记录
            self.errors[ch_key] = f"auth/gone: {status} {text}"
        with open(self.spool, "a", encoding="utf-8") as f:
            f.write(json.dumps({"ch": ch_key, "payload": payload}, ensure_ascii=False) + "\n")
        return False

    def flush_spool(self) -> None:
        if not self.spool.exists():
            return
        lines = self.spool.read_text(encoding="utf-8").splitlines()
        if not lines:
            return
        remain = []
        for line in lines:
            try:
                item = json.loads(line)
            except Exception:
                continue
            if not self.send(item["ch"], item["payload"]):
                remain.append(line)
        self.spool.write_text("\n".join(remain) + ("\n" if remain else ""), encoding="utf-8")
        if remain:
            log.warning("spool: %d events still pending", len(remain))

# ───────────────────────── gws 执行器（下行命令的唯一执行臂）─────────────────────────
class GwsError(Exception):
    pass

def run_gws(gws_path: str, argv: list[str], timeout: int = 120) -> dict:
    try:
        p = subprocess.run([gws_path] + argv, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise GwsError(f"gws timeout ({timeout}s)")
    except FileNotFoundError:
        raise GwsError(f"gws not found: {gws_path}")
    if p.returncode != 0:
        raise GwsError((p.stderr or p.stdout or "").strip()[-1200:])
    out = (p.stdout or "").strip()
    try:
        return {"json": json.loads(out)}
    except Exception:
        return {"text": out[:6000]}

def _b64url_mime(to: str, subject: str, body: str) -> str:
    m = EmailMessage()
    m["To"] = to
    m["Subject"] = subject
    m.set_content(body)
    return base64.urlsafe_b64encode(m.as_bytes()).decode()

def build_argv(action: str, a: dict) -> list[str]:
    """action → gws argv。形状经 gws --dry-run 验证（selftest 覆盖全部动作）。"""
    u = "me"
    if action == "gmail_search":
        return ["gmail", "users", "messages", "list", "--params",
                json.dumps({"userId": u, "q": a.get("query", ""), "maxResults": int(a.get("max_results", 10))})]
    if action == "gmail_read":
        return ["gmail", "users", "messages", "get", "--params",
                json.dumps({"userId": u, "id": a["message_id"], "format": a.get("format", "full")})]
    if action == "gmail_draft":
        return ["gmail", "users", "drafts", "create", "--params", json.dumps({"userId": u}),
                "--json",
                json.dumps({"message": {"raw": _b64url_mime(a["to"], a["subject"], a.get("body", ""))}})]
    if action == "gmail_archive":
        return ["gmail", "users", "messages", "modify", "--params",
                json.dumps({"userId": u, "id": a["message_id"]}),
                "--json", json.dumps({"removeLabelIds": ["INBOX"]})]
    if action == "gmail_trash":
        return ["gmail", "users", "messages", "trash", "--params",
                json.dumps({"userId": u, "id": a["message_id"]})]
    if action == "calendar_list":
        return ["calendar", "events", "list", "--params",
                json.dumps({"calendarId": "primary", "timeMin": a.get("time_min", ""),
                            "timeMax": a.get("time_max", ""), "singleEvents": True,
                            "orderBy": "startTime", "maxResults": int(a.get("max_results", 25))})]
    if action == "calendar_create":
        ev = {"summary": a["title"], "start": {"dateTime": a["start"]},
              "end": {"dateTime": a["end"]}}
        if a.get("description"):
            ev["description"] = a["description"]
        ev["extendedProperties"] = {"private": {"lifecore": "1"}}  # 自产标记：监听端跳过
        return ["calendar", "events", "insert", "--params",
                json.dumps({"calendarId": "primary", "sendUpdates": "none"}),
                "--json", json.dumps(ev)]
    if action == "calendar_update":
        return ["calendar", "events", "update", "--params",
                json.dumps({"calendarId": "primary", "eventId": a["event_id"],
                            "sendUpdates": "none"}),
                "--json", json.dumps(json.loads(a.get("fields_json", "{}")))]
    if action == "calendar_delete":
        return ["calendar", "events", "delete", "--params",
                json.dumps({"calendarId": "primary", "eventId": a["event_id"],
                            "sendUpdates": "none"})]
    if action == "tasks_list":
        return ["tasks", "tasks", "list", "--params",
                json.dumps({"tasklist": a.get("tasklist", "@default"),
                            "showCompleted": bool(a.get("show_completed", False)),
                            "maxResults": 100})]
    if action == "tasks_create":
        body = {"title": a["title"]}
        if a.get("notes"):
            body["notes"] = a["notes"]
        if a.get("due"):
            body["due"] = a["due"]
        return ["tasks", "tasks", "insert", "--params",
                json.dumps({"tasklist": a.get("tasklist", "@default")}), "--json", json.dumps(body)]
    if action == "tasks_complete":
        return ["tasks", "tasks", "patch", "--params",
                json.dumps({"tasklist": a.get("tasklist", "@default"), "task": a["task_id"]}),
                "--json", json.dumps({"status": "completed"})]
    if action == "drive_search":
        return ["drive", "files", "list", "--params",
                json.dumps({"q": a.get("query", ""), "pageSize": int(a.get("max_results", 10)),
                            "fields": "files(id,name,mimeType,modifiedTime,webViewLink)"})]
    if action == "drive_read":
        return ["drive", "files", "get", "--params",
                json.dumps({"fileId": a["file_id"],
                            "fields": "id,name,mimeType,modifiedTime,owners,webViewLink"})]
    if action == "drive_export_text":
        return ["drive", "files", "export", "--params",
                json.dumps({"fileId": a["file_id"], "mimeType": "text/plain"})]
    raise GwsError(f"unknown action: {action}")

def get_original_argv(pointer: str) -> list[str]:
    """指针协议（上报摘要的配套回查，铁律 2）：gmail:<id> / cal:<id> / drive:<id> / task:<id>"""
    svc, _, pid = pointer.partition(":")
    if svc == "gmail":
        return build_argv("gmail_read", {"message_id": pid})
    if svc == "cal":
        return ["calendar", "events", "get", "--params",
                json.dumps({"calendarId": "primary", "eventId": pid})]
    if svc == "drive":
        return build_argv("drive_read", {"file_id": pid})
    if svc == "task":
        return ["tasks", "tasks", "get", "--params",
                json.dumps({"tasklist": "@default", "task": pid})]
    raise GwsError(f"bad pointer: {pointer}")

def execute_command(cfg: dict, cmd: dict) -> dict:
    action, a = cmd["action"], cmd.get("args") or {}
    if action == "echo":                       # 测试/探活
        return {"echo": a}
    if action == "get_original":
        return run_gws(cfg["gws_path"], get_original_argv(a["pointer"]))
    if action == "raw":                        # 逃生门：裸 gws（README 安全节，LLM 慎用）
        argv = a.get("argv") or []
        if not isinstance(argv, list) or not all(isinstance(x, str) for x in argv):
            raise GwsError("raw.argv must be list[str]")
        return run_gws(cfg["gws_path"], argv, timeout=int(a.get("timeout", 120)))
    return run_gws(cfg["gws_path"], build_argv(action, a))

# ───────────────────────── 监听 1：Gmail IMAP IDLE（秒级，应用专用密码）─────────────────────────
def watch_gmail(cfg: dict, sender: Sender, state: State, stop: threading.Event) -> None:
    g = cfg.get("gmail") or {}
    if not g.get("enabled"):
        return
    skip_labels = set(g.get("skip_labels") or
                      ["CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL",
                       "CATEGORY_FORUMS", "CATEGORY_UPDATES"])
    backoff = 15
    while not stop.is_set():
        conn = None
        try:
            conn = imaplib.IMAP4_SSL("imap.gmail.com", 993, timeout=30)
            conn.login(g["email"], g["app_password"])
            typ, _ = conn.select("INBOX", readonly=True)
            if typ != "OK":
                raise RuntimeError("select INBOX failed")
            log.info("gmail IMAP connected: %s", g["email"])
            sender.errors.pop("gmail", None)
            backoff = 15
            uid_validity = conn.response("UIDVALIDITY")[-1][0].decode()
            seen: set[int] = set(state.get("gmail:seen_uids", []))
            if state.get("gmail:uidvalidity") != uid_validity:
                seen = set()
                state.set("gmail:uidvalidity", uid_validity)
            _idle_loop(conn, seen, skip_labels, sender, state, stop)
        except Exception as e:
            if not stop.is_set():
                log.warning("gmail watcher error: %s (retry in %ss)", e, backoff)
                sender.errors["gmail"] = str(e)[:200]
                stop.wait(backoff)
                backoff = min(backoff * 2, 600)

def _readline_tagged_ok(conn) -> None:
    while True:
        line = conn.readline().decode(errors="replace").strip()
        if not line:
            raise RuntimeError("IMAP connection closed")
        if not line.startswith("*") and \
           (line.endswith("OK") or line.endswith("NO") or line.endswith("BAD")):
            return

def _idle_loop(conn, seen: set[int], skip_labels: set[str], sender: Sender,
               state: State, stop: threading.Event) -> None:
    tag_counter = 0
    while not stop.is_set():
        tag_counter += 1
        tag = f"LC{tag_counter}"
        conn.send(f"{tag} IDLE\r\n".encode())
        while True:                       # 等到 "+ idling"
            line = conn.readline()
            if not line:
                raise RuntimeError("IMAP connection closed")
            if line.startswith(b"+"):
                break
        conn.sock.settimeout(480)         # Gmail ~9min 踢 IDLE：8min 超时重挂
        try:
            while True:
                line = conn.readline()
                if not line:
                    raise RuntimeError("IMAP connection closed")
                if line.startswith(b"*") and (b"EXISTS" in line or b"RECENT" in line):
                    break
                if line.startswith(b"*") and b"BYE" in line.upper():
                    raise RuntimeError("IMAP BYE")
        except socket.timeout:
            pass                          # 保活：重挂 IDLE
        finally:
            conn.sock.settimeout(None)
        conn.send(b"DONE\r\n")
        _readline_tagged_ok(conn)
        _drain_unseen(conn, seen, skip_labels, sender, state)

def _drain_unseen(conn, seen: set[int], skip_labels: set[str], sender: Sender,
                  state: State) -> None:
    typ, data = conn.uid("SEARCH", None, "UNSEEN")
    if typ != "OK" or not data or not data[0]:
        return
    uids = [int(x) for x in data[0].split()]
    fresh = [u for u in uids if u not in seen]
    if not fresh:
        return
    uidset = ",".join(str(u) for u in fresh)
    typ, data = conn.uid("FETCH", uidset,
                         "(X-GM-MSGID X-GM-LABELS BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE)])")
    if typ != "OK" or not data:
        return
    for item in data:
        if not isinstance(item, tuple):
            continue
        hdr_blob = item[1]
        flags_line = item[0].decode(errors="replace") if isinstance(item[0], bytes) else str(item[0])
        parts = flags_line.split()
        uid = int(parts[parts.index("UID") + 1]) if "UID" in parts else 0
        seen.add(uid)
        msgid_hex = ""
        if "X-GM-MSGID" in parts:
            msgid_hex = f"{int(parts[parts.index('X-GM-MSGID') + 1]):x}"
        labels: set[str] = set()
        if "X-GM-LABELS" in flags_line:
            raw = flags_line.split("X-GM-LABELS", 1)[1].split("BODY[", 1)[0]
            labels = {x.strip(" ()") for x in raw.split()}
        if labels & skip_labels and "IMPORTANT" not in labels:
            continue
        hdr = Parser().parsestr(hdr_blob.decode(errors="replace"))
        sender_name = (hdr.get("From") or "?").strip()
        subject = (hdr.get("Subject") or "(无主题)").strip()
        important = "IMPORTANT" in labels
        ok = sender.send("gmail", {
            "level": "B", "archetype": "message", "pointer": f"gmail:{msgid_hex}",
            "summary": f"📧 {sender_name} | {subject}"[:300],
            "sender": sender_name[:120], "subject": subject[:200],
            "category": "important" if important else "normal",
            "suggested_priority": "high" if important else "normal",
            "uid": uid,
        })
        log.info("gmail event uid=%s msgid=%s sent=%s important=%s", uid, msgid_hex, ok, important)
    state.set("gmail:seen_uids", sorted(seen)[-500:])

# ───────────── 监听 2/3/4：Calendar / Drive / Tasks delta 轮询（gws）─────────────
def _gws_probe(cfg: dict) -> bool:
    try:
        run_gws(cfg["gws_path"], ["drive", "files", "list", "--params", '{"pageSize": 1}'],
                timeout=45)
        return True
    except GwsError as e:
        log.warning("gws probe failed: %s", str(e)[:200])
        return False

def watch_calendar(cfg: dict, sender: Sender, state: State, stop: threading.Event) -> None:
    c = cfg.get("calendar") or {}
    if not c.get("enabled"):
        return
    interval = int(c.get("poll_seconds", 60))
    backoff = 30
    while not stop.is_set():
        try:
            tok = state.get("calendar:sync_token")
            params = {"calendarId": "primary", "maxResults": 250, "showDeleted": True}
            if tok:
                params["syncToken"] = tok
            else:
                params.update({"timeMin": _rfc3339(time.time() - 86400),
                               "singleEvents": True, "orderBy": "updated"})
            r = run_gws(cfg["gws_path"], ["calendar", "events", "list", "--params",
                                          json.dumps(params)])
            j = r.get("json") or {}
            if tok and j.get("error"):        # 410 Gone → 清游标全量重同步
                state.set("calendar:sync_token", None)
                continue
            n_self = 0
            for ev in j.get("items", []):
                if ((ev.get("extendedProperties") or {}).get("private") or {}).get("lifecore"):
                    n_self += 1
                    continue
                start = (ev.get("start") or {}).get("dateTime") or (ev.get("start") or {}).get("date") or "?"
                summary = (f"🗑 日历事件取消：{ev.get('summary', '?')}" if ev.get("status") == "cancelled"
                           else f"📅 {ev.get('summary', '(无标题)')} @ {start}")
                sender.send("calendar", {"level": "B", "archetype": "calendar",
                                         "pointer": f"cal:{ev.get('id', '')}",
                                         "summary": summary[:300],
                                         "suggested_priority": "normal"})
            if j.get("nextSyncToken"):
                state.set("calendar:sync_token", j["nextSyncToken"])
            sender.errors.pop("calendar", None)
            log.info("calendar poll: %d events (%d self-origin)", len(j.get("items", [])), n_self)
            backoff = 30
            stop.wait(interval)
        except Exception as e:
            log.warning("calendar watcher error: %s", e)
            sender.errors["calendar"] = str(e)[:200]
            stop.wait(backoff)
            backoff = min(backoff * 2, 900)

def watch_drive(cfg: dict, sender: Sender, state: State, stop: threading.Event) -> None:
    d = cfg.get("drive") or {}
    if not d.get("enabled"):
        return
    interval = int(d.get("poll_seconds", 300))
    backoff = 60
    while not stop.is_set():
        try:
            tok = state.get("drive:page_token")
            if not tok:
                r = run_gws(cfg["gws_path"], ["drive", "changes", "getStartPageToken",
                                              "--params", "{}"])
                tok = (r.get("json") or {}).get("startPageToken")
                if not tok:
                    raise RuntimeError("no startPageToken")
            r = run_gws(cfg["gws_path"], ["drive", "changes", "list", "--params",
                                          json.dumps({"pageToken": tok, "pageSize": 100})])
            j = r.get("json") or {}
            for ch in j.get("changes", []):
                f = ch.get("file") or {}
                name = f.get("name", "?")
                if ch.get("removed"):
                    summary = f"🗑 网盘删除：{name}"
                else:
                    op = "新建" if f.get("createdTime") == f.get("modifiedTime") else "修改"
                    summary = f"📁 网盘{op}：{name} ({f.get('mimeType', '?')})"
                sender.send("drive", {"level": "B", "archetype": "file",
                                      "pointer": f"drive:{ch.get('fileId', '')}",
                                      "summary": summary[:300]})
            if j.get("nextPageToken"):
                state.set("drive:page_token", j["nextPageToken"])
            elif j.get("newStartPageToken"):
                state.set("drive:page_token", j["newStartPageToken"])
            sender.errors.pop("drive", None)
            backoff = 60
            stop.wait(interval)
        except Exception as e:
            log.warning("drive watcher error: %s", e)
            sender.errors["drive"] = str(e)[:200]
            stop.wait(backoff)
            backoff = min(backoff * 2, 1800)

def watch_tasks(cfg: dict, sender: Sender, state: State, stop: threading.Event) -> None:
    t = cfg.get("tasks") or {}
    if not t.get("enabled"):
        return
    interval = int(t.get("poll_seconds", 3600))
    backoff = 300
    while not stop.is_set():
        try:
            prev: dict[str, dict] = state.get("tasks:snapshot", {})
            cur: dict[str, dict] = {}
            lists = (run_gws(cfg["gws_path"], ["tasks", "tasklists", "list",
                                               "--params", "{}"]).get("json") or {}).get("items", [])
            for tl in lists:
                r = run_gws(cfg["gws_path"], ["tasks", "tasks", "list", "--params",
                                              json.dumps({"tasklist": tl["id"],
                                                          "showCompleted": True, "maxResults": 100})])
                for item in (r.get("json") or {}).get("items", []):
                    cur[item["id"]] = {"title": item.get("title", "?"),
                                       "status": item.get("status", "needsAction"),
                                       "updated": item.get("updated", ""),
                                       "list": tl.get("title", "?")}
            for tid, it in cur.items():
                old = prev.get(tid)
                if old is None:
                    summary = f"✅ 新任务：{it['title']}（{it['list']}）"
                elif old["status"] != "completed" and it["status"] == "completed":
                    summary = f"🏁 任务完成：{it['title']}"
                elif old["title"] != it["title"]:
                    summary = f"✏️ 任务改名：{old['title']} → {it['title']}"
                else:
                    continue
                sender.send("tasks", {"level": "B", "archetype": "task",
                                      "pointer": f"task:{tid}", "summary": summary[:300],
                                      "suggested_priority": "normal"})
            state.set("tasks:snapshot", cur)
            sender.errors.pop("tasks", None)
            backoff = 300
            stop.wait(interval)
        except Exception as e:
            log.warning("tasks watcher error: %s", e)
            sender.errors["tasks"] = str(e)[:200]
            stop.wait(backoff)
            backoff = min(backoff * 2, 3600)

def _rfc3339(ts: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts))

# ───────────────────────── 下行：命令长轮询 + 执行 + 回执 + 心跳 ──────────────────────────
def command_loop(cfg: dict, sender: Sender, state: State, stop: threading.Event) -> None:
    base = cfg["api_base"].rstrip("/")
    token = cfg["device_token"]
    gws_ok = {"v": _gws_probe(cfg)}
    while not stop.is_set():
        status, j = http_json("GET", f"{base}/v2/commands/pending?wait=25", token, timeout=40)
        if status == 401:
            log.error("device token rejected (401) — 凭证失效，按契约停止重试并报告")
            sender.errors["device"] = "token rejected"
            stop.wait(300)
            continue
        if status != 200:
            log.warning("commands poll failed: %s %s", status, j)
            stop.wait(10)
            continue
        cmd = (j or {}).get("command")
        if cmd:
            log.info("command #%s %s %s", cmd["id"], cmd["action"],
                     json.dumps(cmd.get("args"), ensure_ascii=False)[:300])
            try:
                result = execute_command(cfg, cmd)
                http_json("POST", f"{base}/v2/commands/{cmd['id']}/result", token,
                          {"status": "done", "result": result}, timeout=30)
            except Exception as e:
                http_json("POST", f"{base}/v2/commands/{cmd['id']}/result", token,
                          {"status": "failed", "error": str(e)[:1000]}, timeout=30)
        if not gws_ok["v"] and _gws_probe(cfg):
            gws_ok["v"] = True
        watchers = {}
        for k in ("gmail", "calendar", "drive", "tasks"):
            if not (cfg.get(k) or {}).get("enabled"):
                watchers[k] = "disabled"
            elif k in sender.errors:
                watchers[k] = "error: " + sender.errors[k][:80]
            else:
                watchers[k] = "ok"
        spool_depth = 0
        if sender.spool.exists():
            with open(sender.spool) as sf:
                spool_depth = sum(1 for _ in sf)
        http_json("POST", f"{base}/v2/bridge/heartbeat", token, {
            "name": cfg.get("device_name", "google-bridge"),
            "detail": {"watchers": watchers,
                       "gws_auth": gws_ok["v"],
                       "last_event": {k: _rfc3339(v) for k, v in sender.last_event.items()},
                       "spool_depth": spool_depth,
                       "version": "1.0"}},
                  timeout=15)
        sender.flush_spool()

# ───────────────────────── 注册（幂等：缺哪个建哪个，已存在则沿用）─────────────────────────
CHANNEL_SPECS = [
    ("gmail", "google-gmail", "message", "AB",
     {"mode": "notify",
      "triggers": [{"field": "$.category", "op": "eq", "value": "important"}],
      "semantics": "个人 Gmail 监听（IMAP IDLE 秒级）。只上报未读新邮件，桥端已过滤推广/社交/论坛/订阅更新类（除非标了 IMPORTANT）。老师、工作、验证码等重要来信应带 requires_feedback 并建议草拟回复（用 google_gmail_draft，系统不直接发信）。summary=发件人|主题，原文用 google_get_original(pointer) 回查。",
      "requires_feedback": False}),
    ("calendar", "google-calendar", "calendar", "AB",
     {"mode": "notify",
      "semantics": "日历变更监听（分钟级增量）：新事件、改期、取消、他人邀请。桥端自动跳过本系统自己创建的事件（extendedProperties 标记），不要对自己刚创建的日程再汇报。临近开始（<30min）的变更建议提级。",
      "requires_feedback": False}),
    ("drive", "google-drive", "file", "AB",
     {"mode": "digest", "digest_window": "15m",
      "semantics": "网盘变更监听（5-10 分钟增量）：新文件/修改/删除，只报文件名+操作类型。正文内容一律用 google_get_original(pointer) 回查，摘要不要自造。",
      "requires_feedback": False}),
    ("tasks", "google-tasks", "task", "AB",
     {"mode": "silent",
      "semantics": "Google Tasks 镜像（小时级）。不主动汇报（silent），只保证核心可查可改（google_tasks_* 工具）；如需任务汇总再读。",
      "requires_feedback": False}),
]

def register_channels(cfg: dict) -> dict:
    base = cfg["api_base"].rstrip("/")
    token = cfg["device_token"]
    status, j = http_json("GET", f"{base}/v1/channels", token)
    if status != 200:
        raise SystemExit(f"list channels failed: {status} {j}")
    existing = {c["name"]: c for c in j.get("channels", []) if not c.get("revoked")}
    cfg.setdefault("channels", {})
    for key, name, archetype, uplink, rp in CHANNEL_SPECS:
        if name in existing:
            ch = existing[name]
            cfg["channels"].setdefault(key, {})
            cfg["channels"][key].update({"ingest_url": ch["ingest_url"],
                                         "channel_id": ch["channel_id"]})
            if "secret" not in cfg["channels"][key]:
                print(f"[register] {name}: exists {ch['channel_id']} (secret 不在 config，"
                      f"若已丢失请删通道重建)")
            else:
                print(f"[register] {name}: exists {ch['channel_id']}, secret reused")
            continue
        _, cj = http_json("POST", f"{base}/v1/channels", token, {
            "name": name, "host": cfg.get("host_desc", "用户PC（google-bridge 常驻）"),
            "direction": "source", "archetype": archetype, "uplink_level": uplink,
            "report_policy": rp,
            "session": {"discriminator": "$.pointer"}})
        if "secret" not in cj:
            print(f"[register] {name}: FAILED {cj}")
            continue
        cfg["channels"][key] = {"ingest_url": cj["ingest_url"], "secret": cj["secret"],
                                "channel_id": cj["channel_id"]}
        print(f"[register] {name}: created {cj['channel_id']}")
    save_config(cfg)
    return cfg

# ───────────────────────── 配置 / 日志 / 入口 ──────────────────────────
def load_config(path: Path) -> dict:
    if not path.exists():
        return {"api_base": "https://windgraham.art", "device_name": "google-bridge",
                "gws_path": "gws", "host_desc": "用户PC（google-bridge 常驻）",
                "gmail": {"enabled": False}, "calendar": {"enabled": True, "poll_seconds": 60},
                "drive": {"enabled": True, "poll_seconds": 300},
                "tasks": {"enabled": True, "poll_seconds": 3600}, "channels": {}}
    return json.loads(path.read_text(encoding="utf-8"))

def save_config(cfg: dict, path: Path | None = None) -> None:
    path = path or DEFAULT_CONFIG
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")
    path.chmod(0o600)

def setup_logging(data_dir: Path) -> None:
    data_dir.mkdir(parents=True, exist_ok=True)
    h = logging.handlers.RotatingFileHandler(data_dir / "bridge.log", maxBytes=5 * 1024 * 1024,
                                             backupCount=3, encoding="utf-8")
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.addHandler(h)
    root.addHandler(logging.StreamHandler())

def _apply_gws_proxy(cfg: dict) -> None:
    """gws 子进程需访问 *.googleapis.com；本机直连不可达时走 mihomo 等本地代理。
    bridge 自己的 urllib 走禁代理 opener（_OPENER），两者互不干扰。"""
    p = (cfg.get("gws_proxy") or "").strip()
    if p:
        os.environ["HTTPS_PROXY"] = p
        os.environ["HTTP_PROXY"] = p

def cmd_run(cfg: dict, data_dir: Path) -> None:
    setup_logging(data_dir)
    _apply_gws_proxy(cfg)
    state = State(data_dir / "state.db")
    sender = Sender(cfg, state, data_dir)
    stop = threading.Event()
    sender.flush_spool()
    if not cfg.get("device_token"):
        raise SystemExit("device_token missing — pair first (see README)")
    if not (cfg.get("channels") or {}):
        raise SystemExit("no channels — run: google_bridge.py register")
    threads = [threading.Thread(target=command_loop, args=(cfg, sender, state, stop),
                                daemon=True, name="commands")]
    if (cfg.get("gmail") or {}).get("enabled"):
        threads.append(threading.Thread(target=watch_gmail, args=(cfg, sender, state, stop),
                                        daemon=True, name="gmail"))
    if (cfg.get("calendar") or {}).get("enabled"):
        threads.append(threading.Thread(target=watch_calendar, args=(cfg, sender, state, stop),
                                        daemon=True, name="calendar"))
    if (cfg.get("drive") or {}).get("enabled"):
        threads.append(threading.Thread(target=watch_drive, args=(cfg, sender, state, stop),
                                        daemon=True, name="drive"))
    if (cfg.get("tasks") or {}).get("enabled"):
        threads.append(threading.Thread(target=watch_tasks, args=(cfg, sender, state, stop),
                                        daemon=True, name="tasks"))
    for t in threads:
        t.start()
        time.sleep(1)            # stagger，避免启动风暴
    log.info("google-bridge up: %d threads", len(threads))
    try:
        while not stop.is_set():
            time.sleep(1)
    except KeyboardInterrupt:
        log.info("stopping")
        stop.set()
    for t in threads:
        t.join(timeout=5)

def cmd_selftest(cfg: dict, data_dir: Path) -> None:
    setup_logging(data_dir)
    state = State(data_dir / "state.db")
    ok = True
    # 1) HMAC 形状
    body = b'{"level":"B","pointer":"gmail:abc","summary":"t"}'
    hdrs = sign("lc_testsecret", body)
    expect = hmac_mod.new(b"lc_testsecret", hdrs["X-Webhook-Timestamp"].encode() + b"." + body,
                          hashlib.sha256).hexdigest()
    assert hdrs["X-Webhook-Signature-V2"] == expect
    print("[selftest] HMAC V2 sign/verify shape ... OK")
    # 2) spool 落盘
    sender = Sender(cfg, state, data_dir)
    n0 = len(sender.spool.read_text().splitlines()) if sender.spool.exists() else 0
    sender.spool.write_text(json.dumps({"ch": "gmail", "payload": {"summary": "spool-test"}}) + "\n",
                            encoding="utf-8")
    print(f"[selftest] spool write ok (depth {n0} -> {n0 + 1})")
    # 3) 动作形状（gws --dry-run：从 Discovery 动态构建请求，无需 OAuth）
    gws = cfg.get("gws_path", "gws")
    samples = {
        "gmail_search": {"query": "from:me", "max_results": 1},
        "gmail_read": {"message_id": "deadbeef"},
        "gmail_draft": {"to": "a@b.c", "subject": "t", "body": "b"},
        "gmail_archive": {"message_id": "x"}, "gmail_trash": {"message_id": "x"},
        "calendar_list": {"time_min": _rfc3339(time.time())},
        "calendar_create": {"title": "t", "start": _rfc3339(time.time() + 3600),
                            "end": _rfc3339(time.time() + 7200)},
        "calendar_update": {"event_id": "x", "fields_json": "{}"},
        "calendar_delete": {"event_id": "x"},
        "tasks_list": {}, "tasks_create": {"title": "t"},
        "tasks_complete": {"task_id": "x"},
        "drive_search": {"query": "name contains 'x'", "max_results": 1},
        "drive_read": {"file_id": "x"}, "drive_export_text": {"file_id": "x"},
    }
    for action, a in samples.items():
        argv = build_argv(action, a)
        try:
            p = subprocess.run([gws, *argv, "--dry-run"], capture_output=True, text=True,
                               timeout=90)
            good = p.returncode == 0
            tail = ((p.stderr or "") + (p.stdout or ""))[:140].strip()
        except Exception as e:
            good, tail = False, str(e)[:140]
        if not good:
            ok = False
        print(f"[selftest] {'OK ' if good else 'FAIL'} {action}: gws {' '.join(argv[:3])} :: {tail}")
    for ptr in ("gmail:abc", "cal:abc", "drive:abc", "task:abc"):
        argv = get_original_argv(ptr)
        p = subprocess.run([gws, *argv, "--dry-run"], capture_output=True, text=True, timeout=90)
        if p.returncode != 0:
            ok = False
        print(f"[selftest] {'OK ' if p.returncode == 0 else 'FAIL'} get_original {ptr}: "
              f"{' '.join(argv[:3])}")
    # 4) 服务器连通
    if cfg.get("device_token"):
        st, j = http_json("GET", cfg["api_base"].rstrip("/") + "/v2/me", cfg["device_token"],
                          timeout=15)
        print(f"[selftest] server /v2/me: {st} {json.dumps(j, ensure_ascii=False)[:150]}")
        ok = ok and st == 200
    else:
        print("[selftest] no device_token — skip server probe")
    print("[selftest]", "ALL OK" if ok else "HAS FAILURES")
    raise SystemExit(0 if ok else 1)

def main() -> None:
    ap = argparse.ArgumentParser(prog="google-bridge")
    ap.add_argument("cmd", nargs="?", default="run",
                    choices=["run", "register", "selftest", "exec", "send-test"])
    ap.add_argument("action", nargs="?", help="exec 的动作名 / send-test 的通道键")
    ap.add_argument("args", nargs="?", default="{}", help="exec 的 JSON 参数")
    ap.add_argument("--config", default=str(DEFAULT_CONFIG))
    args = ap.parse_args()
    cfg_path = Path(args.config).expanduser()
    cfg = load_config(cfg_path)
    _apply_gws_proxy(cfg)
    data_dir = Path(cfg.get("data_dir", str(DEFAULT_DATA))).expanduser()
    if args.cmd == "run":
        cmd_run(cfg, data_dir)
    elif args.cmd == "register":
        register_channels(cfg)
        print(f"[register] config saved: {cfg_path}")
    elif args.cmd == "selftest":
        cmd_selftest(cfg, data_dir)
    elif args.cmd == "exec":
        setup_logging(data_dir)
        print(json.dumps(execute_command(cfg, {"action": args.action, "args": json.loads(args.args)}),
                         ensure_ascii=False, indent=2)[:4000])
    elif args.cmd == "send-test":
        setup_logging(data_dir)
        state = State(data_dir / "state.db")
        sender = Sender(cfg, state, data_dir)
        ch = args.action
        payload = {"level": "B", "archetype": "message",
                   "pointer": f"test:{secrets.token_hex(4)}",
                   "summary": f"google-bridge 合成测试事件（{ch}）",
                   "suggested_priority": "normal"}
        status, text = post_event(cfg["channels"][ch]["ingest_url"], cfg["channels"][ch]["secret"],
                                  payload)
        print(f"send-test {ch}: {status} {text}")

if __name__ == "__main__":
    main()
