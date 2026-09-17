/**
 * LifeCore ChatPage — streaming SSE chat against /v2/sessions/:id/chat/stream.
 *
 * NOT a re-implementation of Hermes' PTY-backed ChatPage (see hermes/web/
 * AGENTS.md). LifeCore has its own session model on the LC backend — we
 * implement React-side streaming here using fetch + reader per the
 * docs/17 §4.2 template.
 *
 * URL param: `?sid=<sessionId>` (optional). If absent, the page shows a
 * session picker (similar to legacy `sessionsView()`).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import {
  ArrowLeft,
  ListChecks,
  Mic,
  Send,
  Square,
  Volume2,
} from "lucide-react";
import { Button } from "@nous-research/ui/ui/components/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@nous-research/ui/ui/components/card";
import { Badge } from "@nous-research/ui/ui/components/badge";
import { Input } from "@nous-research/ui/ui/components/input";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import { useI18n } from "@/i18n";
import { errorMessage, lifecoreApi } from "@/lib/lifecore-api";
import { useVoiceConsent } from "@/hooks/useVoiceConsent";
import { VoiceConsentBanner } from "@/components/lifecore/VoiceConsentBanner";

type ChatMessage =
  | { kind: "user"; text: string }
  | { kind: "ai"; text: string; tool?: string; done: boolean }
  | { kind: "error"; text: string };

/** Convert a webm/any blob to a 16kHz mono PCM WAV (matches legacy toWav16k). */
async function toWav16k(ab: ArrayBuffer): Promise<ArrayBuffer> {
  const Ctx = window.AudioContext ?? (window as unknown as {
    webkitAudioContext: typeof AudioContext;
  }).webkitAudioContext;
  const ctx = new Ctx();
  const buf = await ctx.decodeAudioData(ab);
  const off = new OfflineAudioContext(1, Math.ceil(buf.duration * 16000), 16000);
  const src = off.createBufferSource();
  src.buffer = buf;
  src.connect(off.destination);
  src.start();
  const res = await off.startRendering();
  ctx.close();
  const d = res.getChannelData(0);
  const n = d.length;
  const out = new DataView(new ArrayBuffer(44 + n * 2));
  const W = (o: number, s: string) => {
    for (let i = 0; i < s.length; i++) out.setUint8(o + i, s.charCodeAt(i));
  };
  W(0, "RIFF");
  out.setUint32(4, 36 + n * 2, true);
  W(8, "WAVE");
  W(12, "fmt ");
  out.setUint32(16, 16, true);
  out.setUint16(20, 1, true);
  out.setUint16(22, 1, true);
  out.setUint32(24, 16000, true);
  out.setUint32(28, 32000, true);
  out.setUint16(32, 2, true);
  out.setUint16(34, 16, true);
  W(36, "data");
  out.setUint32(40, n * 2, true);
  for (let i = 0; i < n; i++) {
    const v = Math.max(-1, Math.min(1, d[i]));
    out.setInt16(44 + i * 2, v < 0 ? v * 0x8000 : v * 0x7fff, true);
  }
  return out.buffer;
}

export default function ChatPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { consent, setConsent, speak } = useVoiceConsent();
  const [params, setParams] = useSearchParams();
  const sid = params.get("sid");
  const logRef = useRef<HTMLDivElement>(null);

  // ── Session picker view (no sid) ────────────────────────────────
  if (!sid) return <SessionPicker />;

  // ── Streaming chat view ─────────────────────────────────────────
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [recorder, setRecorder] = useState<MediaRecorder | null>(null);
  const [recording, setRecording] = useState(false);

  const appendMessage = useCallback((msg: ChatMessage) => {
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      // If the new message is an AI delta and the last is also AI in progress,
      // mutate that last entry instead of pushing a new one.
      if (msg.kind === "ai" && last?.kind === "ai" && !last.done) {
        return [
          ...prev.slice(0, -1),
          {
            ...last,
            text: last.text + msg.text,
            tool: msg.tool ?? last.tool,
            done: msg.done,
          },
        ];
      }
      return [...prev, msg];
    });
  }, []);

  // Auto-scroll on new content
  useEffect(() => {
    const el = logRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  const send = useCallback(async () => {
    const text = input.trim();
    if (!text || busy) return;
    setInput("");
    appendMessage({ kind: "user", text });
    appendMessage({ kind: "ai", text: "", done: false });
    setBusy(true);
    let full = "";
    try {
      const res = await lifecoreApi.streamChat(sid, text);
      if (!res.ok || !res.body) {
        const errText = await res.text().catch(() => res.statusText);
        throw new Error(errText || `HTTP ${res.status}`);
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const frames = buf.split("\n\n");
        buf = frames.pop() ?? "";
        for (const frame of frames) {
          let ev = "";
          let data: { delta?: string; tool_name?: string; final_response?: string; message?: string } = {};
          for (const line of frame.split("\n")) {
            if (line.startsWith("event:")) ev = line.slice(6).trim();
            else if (line.startsWith("data:")) {
              try {
                data = JSON.parse(line.slice(5).trim());
              } catch {
                /* malformed chunk — ignore */
              }
            }
          }
          if (!ev) continue;
          if (ev === "assistant.delta") {
            full += data.delta ?? "";
            appendMessage({ kind: "ai", text: data.delta ?? "", done: false });
          } else if (ev === "tool.progress") {
            appendMessage({
              kind: "ai",
              text: "",
              tool: `⚙ ${data.tool_name ?? ""} ${data.delta ?? ""}`.trim(),
              done: false,
            });
          } else if (ev === "assistant.completed") {
            full = data.final_response ?? full;
            setMessages((prev) => {
              const last = prev[prev.length - 1];
              if (last?.kind === "ai") {
                return [
                  ...prev.slice(0, -1),
                  { ...last, text: full, done: true },
                ];
              }
              return [...prev, { kind: "ai", text: full, done: true }];
            });
          } else if (ev === "error") {
            appendMessage({
              kind: "error",
              text: data.message ?? "stream error",
            });
          }
        }
      }
      if (!full) {
        appendMessage({ kind: "ai", text: "(无文本响应)", done: true });
      }
      if (consent && full) void speak(full);
    } catch (e) {
      appendMessage({
        kind: "error",
        text: `⚠ ${errorMessage(e)}`,
      });
    } finally {
      setBusy(false);
    }
  }, [appendMessage, busy, consent, input, sid, speak]);

  const handleMic = useCallback(async () => {
    if (recording && recorder) {
      recorder.stop();
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const rec = new MediaRecorder(stream);
      const chunks: Blob[] = [];
      rec.ondataavailable = (ev) => {
        if (ev.data.size) chunks.push(ev.data);
      };
      rec.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        setRecording(false);
        setRecorder(null);
        try {
          const ab = await new Blob(chunks).arrayBuffer();
          const wav = await toWav16k(ab);
          const r = await lifecoreApi.asr(wav);
          if (r.text) {
            setInput((prev) => (prev ? prev + " " + r.text : r.text));
          } else {
            showToast(t.lifecore?.chat?.asrEmpty ?? "未识别到语音", "error");
          }
        } catch (e) {
          showToast(
            `${t.lifecore?.chat?.asrFail ?? "转写失败："}${errorMessage(e)}`,
            "error",
          );
        }
      };
      rec.start();
      setRecorder(rec);
      setRecording(true);
    } catch (e) {
      showToast(
        `${t.lifecore?.chat?.micFail ?? "麦克风不可用："}${errorMessage(e)}`,
        "error",
      );
    }
  }, [recorder, recording, showToast, t]);

  const back = useCallback(() => {
    setParams((p) => {
      const next = new URLSearchParams(p);
      next.delete("sid");
      return next;
    });
  }, [setParams]);

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-3">
      <Toast toast={toast} />

      <div className="flex items-center gap-2">
        <Button
          ghost
          size="sm"
          className="uppercase"
          onClick={back}
          prefix={<ArrowLeft className="h-4 w-4" />}
        >
          {t.lifecore?.chat?.back ?? "会话列表"}
        </Button>
        <span className="text-xs text-muted-foreground">{sid}</span>
        <div className="flex-1" />
        <Button
          ghost
          size="sm"
          className="uppercase"
          onClick={() => setConsent(!consent)}
          prefix={<Volume2 className="h-4 w-4" />}
        >
          {consent
            ? (t.lifecore?.voice?.on ?? "播报已开")
            : (t.lifecore?.voice?.off ?? "播报已关")}
        </Button>
      </div>

      {!consent && (
        <VoiceConsentBanner onAgree={() => setConsent(true)} />
      )}

      <Card>
        <CardContent className="p-0">
          <div
            ref={logRef}
            className="flex max-h-[60vh] min-h-[20rem] flex-col gap-2 overflow-y-auto p-4"
          >
            {messages.length === 0 && (
              <p className="py-8 text-center text-sm text-muted-foreground">
                {t.lifecore?.chat?.empty ?? "开始对话吧"}
              </p>
            )}
            {messages.map((m, i) => {
              if (m.kind === "user") {
                return (
                  <div
                    key={i}
                    className="ml-12 self-end whitespace-pre-wrap rounded-md bg-primary/15 px-3 py-2 text-sm"
                  >
                    {m.text}
                  </div>
                );
              }
              if (m.kind === "error") {
                return (
                  <div
                    key={i}
                    className="mr-12 self-start whitespace-pre-wrap rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive"
                  >
                    {m.text}
                  </div>
                );
              }
              return (
                <div
                  key={i}
                  className="mr-12 self-start whitespace-pre-wrap rounded-md border border-border bg-card px-3 py-2 text-sm"
                >
                  {m.tool && (
                    <p className="mb-1 text-xs text-muted-foreground">
                      {m.tool}
                    </p>
                  )}
                  {m.text || (m.done ? "(无文本响应)" : "")}
                  {!m.done && (
                    <Spinner className="ml-2 inline text-primary" />
                  )}
                </div>
              );
            })}
          </div>
          <div className="flex flex-wrap items-center gap-2 border-t border-border p-3">
            <Button
              ghost
              size="icon"
              className={recording ? "text-destructive" : ""}
              onClick={() => void handleMic()}
              aria-label={t.lifecore?.chat?.mic ?? "语音输入"}
              title={t.lifecore?.chat?.mic ?? "语音输入"}
              disabled={busy}
            >
              {recording ? <Square /> : <Mic />}
            </Button>
            <Input
              autoFocus
              value={input}
              placeholder={t.lifecore?.chat?.inputPlaceholder ?? "输入消息，Enter 发送"}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  void send();
                }
              }}
              disabled={busy}
            />
            <Button
              className="uppercase"
              size="sm"
              onClick={() => void send()}
              disabled={busy || !input.trim()}
              prefix={busy ? <Spinner /> : <Send className="h-4 w-4" />}
            >
              {busy
                ? (t.lifecore?.chat?.sending ?? "发送中…")
                : (t.common?.create ?? "Send")}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

/* ── Session picker (subcomponent) ─────────────────────────────── */

function SessionPicker() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const [list, setList] = useState<LcSession[]>([]);
  const [loading, setLoading] = useState(true);
  const [, setParams] = useSearchParams();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await lifecoreApi.listSessions();
      setList(r.data ?? []);
    } catch (e) {
      showToast(errorMessage(e), "error");
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    void load();
  }, [load]);

  const pick = useCallback(
    (id: string) => {
      setParams((p) => {
        const next = new URLSearchParams(p);
        next.set("sid", id);
        return next;
      });
    },
    [setParams],
  );

  const items = useMemo(() => list, [list]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Spinner className="text-2xl text-primary" />
      </div>
    );
  }

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-6">
      <Toast toast={toast} />
      <div className="flex items-center gap-2">
        <ListChecks className="h-5 w-5 text-primary" />
        <h2 className="text-lg font-medium">
          {t.lifecore?.chat?.pickerTitle ?? "选择一个会话继续对话"}
        </h2>
      </div>
      {items.length === 0 ? (
        <Card>
          <CardContent className="py-8 text-center text-sm text-muted-foreground">
            {t.lifecore?.chat?.noSessions ?? "暂无可选会话"}
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-3">
          {items.map((s) => (
            <Card
              key={s.id}
              className="cursor-pointer transition-colors hover:border-primary/60"
              onClick={() => pick(s.id)}
            >
              <CardContent className="flex items-start gap-4 py-4">
                <div className="flex-1 min-w-0">
                  <div className="mb-1 flex items-center gap-2 flex-wrap">
                    <span className="truncate font-medium text-sm">
                      {s.title || s.id}
                    </span>
                    {s.source && (
                      <Badge tone="outline">{s.source}</Badge>
                    )}
                    {s.model && <Badge tone="secondary">{s.model}</Badge>}
                  </div>
                  <p className="text-xs text-muted-foreground">
                    {s.started_at
                      ? new Date(s.started_at * 1000).toLocaleString()
                      : ""}
                  </p>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
