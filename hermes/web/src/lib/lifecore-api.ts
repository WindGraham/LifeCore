/**
 * LifeCore REST + SSE API client.
 *
 * Mirrors the in-page `api(method, path, body)` helper from the legacy
 * `console.html`, but typed, namespaced, and reusing `fetchJSON`'s
 * infrastructure for token injection + 401 handling where it fits.
 *
 * Authorization: every request carries `Authorization: Bearer <pair.token>`
 * — independent of Hermes' own `X-Hermes-Session-Token`. This is fine
 * because LC has its own device-pair scheme (different from Hermes' profile
 * token) and its own routes (`/v2/*`, `/v1/*`).
 *
 * Base URL: `import.meta.env.VITE_LIFECORE_BASE` if set, else `""` so the
 * browser hits the same origin and nginx proxies `/v2/*` and `/v1/*` to the
 * lifecore-server (port 8790). Do NOT hardcode `windgraham.art`.
 */
import {
  ApiError,
  apiErrorFromNetworkFailure,
  apiErrorFromResponse,
  errorMessage as lcErrorMessage,
} from "@/lib/api-error";
import { getPair } from "@/lib/lifecore-pair-store";

const BASE = (import.meta.env.VITE_LIFECORE_BASE ?? "").replace(/\/+$/, "");

export class LcApiError extends ApiError {
  /** No additional fields beyond what ApiError provides, just a typed re-export. */
}

/** Convenience re-export so call sites can import from one place. */
export const errorMessage = lcErrorMessage;

/** Internal: fetch wrapper that injects LC bearer token and parses JSON / blob. */
async function lcFetch<T>(
  path: string,
  init: RequestInit & { raw?: boolean } = {},
): Promise<T> {
  const pair = getPair();
  const headers = new Headers(init.headers);
  if (pair?.token) headers.set("Authorization", `Bearer ${pair.token}`);
  // raw=true means caller wants blob/arrayBuffer back — do NOT set Content-Type
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, {
      ...init,
      headers,
      credentials: "include",
    });
  } catch (cause) {
    throw apiErrorFromNetworkFailure(cause, path);
  }
  if (!res.ok) {
    const body = await res.text().catch(() => res.statusText);
    throw apiErrorFromResponse(res.status, body, path);
  }
  if (init.raw) return (await res.blob()) as unknown as T;
  // Empty 204 → resolve undefined
  if (res.status === 204) return undefined as T;
  const ct = res.headers.get("content-type") ?? "";
  if (!ct.includes("json")) return (await res.text()) as unknown as T;
  return (await res.json()) as T;
}

/* ── Types (server.py response shapes — best-effort, optional fields default to null) ── */

export interface LcDevice {
  device: string;
  fingerprint: string;
  registered_at: string;
  server_time: string;
}

export interface LcSession {
  id: string;
  title?: string;
  source?: string;
  model?: string;
  started_at?: number;
}

export interface LcNotifyActive {
  active: LcNotifyItem | null;
  queue?: LcNotifyItem[];
}

export interface LcNotifyItem {
  id: string;
  channel_id?: string;
  summary?: string;
  options?: string[];
}

export type NotifyAction = "snooze" | "dismissed" | "actioned";

export interface LcChannel {
  name: string;
  channel_id: string;
  archetype: string;
  uplink_level: string;
  created_at?: number;
  revoked?: boolean;
}

export interface LcChannelRegisterResp {
  ingest_url: string;
  secret: string;
  channel: LcChannel;
}

export interface LcJob {
  id?: string;
  name?: string;
  schedule?: string;
  cron?: string;
  enabled?: boolean;
  paused?: boolean;
  deliver?: string;
}

export interface LcCapabilities {
  [key: string]: unknown;
}

export interface LcStateBlock {
  context?: string;
  [key: string]: unknown;
}

/* ── Endpoint methods ── */

export const lifecoreApi = {
  /* Device pairing */
  async pair(base: string, code: string, deviceName: string): Promise<{
    device_token: string;
    fingerprint: string;
  }> {
    const res = await fetch(`${base.replace(/\/+$/, "")}/v1/pair`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, device_name: deviceName }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => res.statusText);
      throw apiErrorFromResponse(res.status, body, "/v1/pair");
    }
    return res.json();
  },

  /* Sessions */
  async listSessions(): Promise<{ data: LcSession[] }> {
    return lcFetch<{ data: LcSession[] }>("/v2/sessions");
  },

  /* Stream chat — returns a Response so caller can read SSE. Caller is
   * responsible for token lifecycle (browser fetch handles it). */
  streamChat(sid: string, message: string): Promise<Response> {
    const pair = getPair();
    return fetch(`${BASE}/v2/sessions/${encodeURIComponent(sid)}/chat/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(pair?.token ? { Authorization: `Bearer ${pair.token}` } : {}),
      },
      body: JSON.stringify({ message }),
    });
  },

  /* Notifications */
  async getActiveNotify(): Promise<LcNotifyActive> {
    return lcFetch<LcNotifyActive>("/v2/notify/active");
  },

  async sendFeedback(itemId: string, action: NotifyAction): Promise<unknown> {
    return lcFetch(`/v2/notify/items/${encodeURIComponent(itemId)}/feedback`, {
      method: "POST",
      body: JSON.stringify({ action }),
    });
  },

  /* Channels */
  async listChannels(): Promise<{ channels: LcChannel[] }> {
    return lcFetch<{ channels: LcChannel[] }>("/v1/channels");
  },

  async registerChannel(input: {
    name: string;
    archetype: string;
    uplink_level: string;
  }): Promise<LcChannelRegisterResp> {
    return lcFetch<LcChannelRegisterResp>("/v1/channels", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },

  async deleteChannel(channelId: string): Promise<unknown> {
    return lcFetch(`/v1/channels/${encodeURIComponent(channelId)}`, {
      method: "DELETE",
    });
  },

  async testEvent(channelId: string): Promise<unknown> {
    return lcFetch("/v1/test-event", {
      method: "POST",
      body: JSON.stringify({ channel_id: channelId }),
    });
  },

  /* Jobs (cron) */
  async listJobs(): Promise<{ jobs?: LcJob[]; data?: LcJob[] } | LcJob[]> {
    return lcFetch("/v2/jobs");
  },

  /* Me / capabilities / state block */
  async me(): Promise<LcDevice> {
    return lcFetch<LcDevice>("/v2/me");
  },

  async capabilities(): Promise<LcCapabilities> {
    return lcFetch<LcCapabilities>("/v2/gateway/capabilities");
  },

  async stateBlock(): Promise<LcStateBlock> {
    return lcFetch<LcStateBlock>("/v2/state-block");
  },

  /* TTS — returns audio blob for the caller to play */
  async tts(text: string): Promise<Blob> {
    return lcFetch<Blob>("/v2/tts", {
      method: "POST",
      body: JSON.stringify({ text: text.slice(0, 200) }),
      raw: true,
    });
  },

  /* ASR — accepts a 16k WAV ArrayBuffer, returns { text: string } */
  async asr(wav: ArrayBuffer): Promise<{ text: string }> {
    return lcFetch<{ text: string }>("/v2/asr?format=json", {
      method: "POST",
      body: wav,
      headers: { "Content-Type": "audio/wav" },
      raw: true,
    }).then(async (blob) => {
      // Server may return JSON-encoded response (the v2/asr endpoint normally does)
      try {
        const text = await (blob as unknown as Blob).text();
        return JSON.parse(text);
      } catch {
        return { text: "" };
      }
    });
  },
};

/** Lowercase helper for the action label mapping used by NotifyPage. */
export function mapNotifyAction(label: string): NotifyAction {
  if (/稍后|晚点|snooze/i.test(label)) return "snooze";
  if (/不做|忽略|取消|dismiss/i.test(label)) return "dismissed";
  return "actioned";
}
