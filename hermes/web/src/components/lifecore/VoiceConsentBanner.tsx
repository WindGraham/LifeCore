/**
 * Voice consent banner — shown above the chat input (and SettingsPage) until
 * the user grants explicit consent to streaming TTS playback. Mirrors the
 * legacy console.html banner shape (`banner` div with Agree / Dismiss
 * buttons) but expressed in DS Card + Button primitives.
 */
import { useState } from "react";
import { Volume2, X } from "lucide-react";
import { Button } from "@nous-research/ui/ui/components/button";
import { useI18n } from "@/i18n";

export interface VoiceConsentBannerProps {
  onAgree: () => void;
  className?: string;
}

export function VoiceConsentBanner({
  onAgree,
  className,
}: VoiceConsentBannerProps) {
  const { t } = useI18n();
  const [dismissed, setDismissed] = useState(false);
  if (dismissed) return null;
  const copy = t.lifecore?.voice;
  return (
    <div
      role="status"
      className={
        "mb-3 flex flex-wrap items-center gap-3 rounded-md border " +
        "border-warning/50 bg-warning/10 px-4 py-3 text-sm " +
        (className ?? "")
      }
    >
      <Volume2 className="h-4 w-4 shrink-0 text-warning" aria-hidden="true" />
      <span className="flex-1 min-w-0">
        <strong className="font-medium">
          {copy?.bannerTitle ?? "流式语音播报需要你的同意。"}
        </strong>
        <span className="ml-2 text-muted-foreground">
          {copy?.bannerHint ??
            "启用后，每条回复将以流式音频朗读（MiniMax TTS）。"}
        </span>
      </span>
      <Button
        size="sm"
        className="uppercase"
        onClick={() => {
          onAgree();
          setDismissed(true);
        }}
      >
        {copy?.agree ?? "同意并启用"}
      </Button>
      <Button
        ghost
        size="icon"
        className="text-muted-foreground hover:text-foreground"
        onClick={() => setDismissed(true)}
        aria-label={t.common?.close ?? "Close"}
        title={t.common?.close ?? "Close"}
      >
        <X />
      </Button>
    </div>
  );
}
