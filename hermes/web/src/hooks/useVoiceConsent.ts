/**
 * Voice consent + sentence-level TTS queue.
 *
 * Replaces the `S.speakQ` chain + the consent banner from legacy console.html.
 * All LifeCore pages that want to play streaming audio (ChatPage, SettingsPage
 * TTS test) should:
 *   1. read `consent` from this hook
 *   2. show `<VoiceConsentBanner>` if not yet granted
 *   3. call `speak(text)` to enqueue — it short-circuits when consent=false
 */
import { useCallback, useEffect, useState } from "react";
import {
  getPair,
  setVoiceConsent,
  subscribePair,
} from "@/lib/lifecore-pair-store";
import { lifecoreApi, errorMessage } from "@/lib/lifecore-api";

let queueTail: Promise<void> = Promise.resolve();

/** Split text into sentence-level chunks (CJK + ASCII punctuation). */
function splitSentences(text: string): string[] {
  const parts =
    text.match(/[^。！？!?.；;\n]+[。！？!?.；;\n]?/g) || [text];
  return parts
    .map((s) => s.trim())
    .filter(Boolean)
    .slice(0, 8);
}

async function playOne(text: string): Promise<void> {
  try {
    const blob = await lifecoreApi.tts(text);
    const url = URL.createObjectURL(blob);
    try {
      await new Promise<void>((resolve) => {
        const a = new Audio(url);
        a.onended = () => resolve();
        a.onerror = () => resolve();
        // Some browsers (esp. mobile) reject autoplay until user gesture.
        // We let upstream consent banner guarantee user gesture; if it still
        // rejects, fall back to resolve() so the queue doesn't stall.
        a.play().catch(() => resolve());
      });
    } finally {
      URL.revokeObjectURL(url);
    }
  } catch (e) {
    // Swallow per-chunk errors — keep queue moving. Surface via console.
    // eslint-disable-next-line no-console
    console.warn("[voice] tts chunk failed:", errorMessage(e));
  }
}

export interface UseVoiceConsent {
  /** Whether user has explicitly granted consent to streaming audio playback */
  consent: boolean;
  /** Flip consent on/off (writes to pair-store, re-renders consumers) */
  setConsent: (on: boolean) => void;
  /**
   * Enqueue text for sequential playback. No-op if consent=false.
   * Returns a promise that resolves when the entire text finishes playing
   * (or immediately if consent is off).
   */
  speak: (text: string) => Promise<void>;
}

export function useVoiceConsent(): UseVoiceConsent {
  const [consent, setConsentState] = useState<boolean>(
    () => getPair()?.voiceConsent === true,
  );

  // React to pair-store changes from other tabs / from PairPage.
  useEffect(() => {
    return subscribePair(() => {
      setConsentState(getPair()?.voiceConsent === true);
    });
  }, []);

  const setConsent = useCallback((on: boolean) => {
    setVoiceConsent(on);
    setConsentState(on);
    if (on) {
      // Lightweight feedback so the user knows audio is now active.
      void queueTail.then(() => playOne("语音播报已启用"));
    }
  }, []);

  const speak = useCallback(
    (text: string): Promise<void> => {
      const current = getPair()?.voiceConsent === true;
      if (!current || !text.trim()) return Promise.resolve();
      const next = queueTail.then(async () => {
        for (const sentence of splitSentences(text)) {
          await playOne(sentence);
        }
      });
      // Keep tail pointing at the latest job so concurrent speak() calls chain.
      queueTail = next.catch(() => {});
      return next;
    },
    [],
  );

  return { consent, setConsent, speak };
}
