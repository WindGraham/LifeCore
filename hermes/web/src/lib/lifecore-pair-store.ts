/**
 * LifeCore pair store — localStorage wrapper for the device pairing token
 * returned by POST /v1/pair. Versioned key (`lc_pair_v1`) so we can change
 * the schema in the future and migrate cleanly.
 *
 * Replaces the in-memory `S = { base, token, fp, consent, ... }` state object
 * that the legacy `services/lifecore-server/static/console.html` used. Same
 * persistence shape, but typed, namespaced, and shared across all LifeCore
 * pages so ChatPage / SettingsPage / TodayPage all read the same pair.
 */
const STORAGE_KEY = "lc_pair_v1";

export interface LcPair {
  /** Base URL of the LifeCore gateway, no trailing slash. e.g. "https://windgraham.art" */
  base: string;
  /** Device bearer token returned by /v1/pair */
  token: string;
  /** Server-issued device fingerprint */
  fingerprint: string;
  /** Whether the user has explicitly consented to streaming TTS playback */
  voiceConsent: boolean;
  /** Epoch seconds — when this pair was created (display only) */
  pairedAt: number;
}

function readRaw(): LcPair | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<LcPair>;
    if (
      typeof parsed.base === "string" &&
      typeof parsed.token === "string" &&
      typeof parsed.fingerprint === "string"
    ) {
      return {
        base: parsed.base.replace(/\/+$/, ""),
        token: parsed.token,
        fingerprint: parsed.fingerprint,
        voiceConsent: parsed.voiceConsent === true,
        pairedAt: typeof parsed.pairedAt === "number" ? parsed.pairedAt : 0,
      };
    }
    return null;
  } catch {
    return null;
  }
}

function writeRaw(pair: LcPair | null): void {
  if (typeof window === "undefined") return;
  try {
    if (pair === null) {
      window.localStorage.removeItem(STORAGE_KEY);
    } else {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(pair));
    }
  } catch {
    /* quota / privacy mode — degrade silently, the user will just be forced to re-pair */
  }
}

export function getPair(): LcPair | null {
  return readRaw();
}

export function setPair(pair: Omit<LcPair, "pairedAt">): LcPair {
  const full: LcPair = { ...pair, pairedAt: Math.floor(Date.now() / 1000) };
  writeRaw(full);
  return full;
}

export function clearPair(): void {
  writeRaw(null);
}

export function updateBase(base: string): void {
  const current = readRaw();
  if (!current) return;
  writeRaw({ ...current, base: base.replace(/\/+$/, "") });
}

export function setVoiceConsent(consent: boolean): void {
  const current = readRaw();
  if (!current) return;
  writeRaw({ ...current, voiceConsent: consent });
}

/**
 * Subscribe to pair changes from any tab. Returns the unsubscribe function.
 * Used by TodayPage / SettingsPage so they re-render when PairPage writes
 * or when another tab un-pairs.
 */
export function subscribePair(listener: () => void): () => void {
  if (typeof window === "undefined") return () => {};
  const handler = (ev: StorageEvent) => {
    if (ev.key === STORAGE_KEY) listener();
  };
  window.addEventListener("storage", handler);
  return () => window.removeEventListener("storage", handler);
}
