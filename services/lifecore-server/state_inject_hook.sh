#!/usr/bin/env bash
# state_inject_hook — hermes pre_llm_call shell hook：每回合把 LifeCore 状态块注入用户消息尾部。
# 契约（hermes agent/shell_hooks.py）：stdin JSON {hook_event_name, session_id, extra:{user_message,...}}；
# stdout JSON {"context": "..."} 会被追加进用户消息（不进 system prompt，缓存安全）。
# 幂等守卫：消息已以 [live 开头（BFF 已注入）则跳过 —— 双通道并存不重复。
set -u
INPUT=$(cat)
BLOCK=$(INPUT="$INPUT" python3 - <<'PY'
import json, os, urllib.request

try:
    payload = json.loads(os.environ["INPUT"])
except Exception:
    print(""); raise SystemExit(0)

msg = str((payload.get("extra") or {}).get("user_message") or "")
if msg.startswith("[live "):
    print(""); raise SystemExit(0)   # BFF 已注入，跳过

try:
    req = urllib.request.Request("http://127.0.0.1:8790/v2/state-block")
    with urllib.request.urlopen(req, timeout=8) as r:
        block = (json.loads(r.read() or b"{}") or {}).get("block") or ""
except Exception:
    print(""); raise SystemExit(0)   # lifecore 不在也不挡路（fail open）
print(block[:2600])
PY
)
[ -z "$BLOCK" ] && { echo '{}'; exit 0; }
python3 -c 'import json,sys; print(json.dumps({"context": sys.stdin.read()}, ensure_ascii=False))' <<< "$BLOCK"
