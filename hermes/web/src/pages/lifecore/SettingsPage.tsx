/**
 * LifeCore SettingsPage — device info + TTS test + ASR test + voice consent
 * + unpair.
 *
 * Layout follows EnvPage template (multi-section cards). All sections are
 * independent — each fetches its own data via Promise.allSettled.
 *
 * Unpair uses ConfirmDialog (destructive) per docs/17 §4.4.
 */
import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router";
import {
  AudioLines,
  ExternalLink,
  Fingerprint,
  KeyRound,
  LogOut,
  Mic,
  PowerOff,
  Server,
  Volume2,
} from "lucide-react";
import { Button } from "@nous-research/ui/ui/components/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@nous-research/ui/ui/components/card";
import { Input } from "@nous-research/ui/ui/components/input";
import { Label } from "@nous-research/ui/ui/components/label";
import { Badge } from "@nous-research/ui/ui/components/badge";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@nous-research/ui/ui/components/dialog";
import { useI18n } from "@/i18n";
import {
  errorMessage,
  lifecoreApi,
  type LcCapabilities,
  type LcDevice,
} from "@/lib/lifecore-api";
import {
  clearPair,
  getPair,
  updateBase,
} from "@/lib/lifecore-pair-store";
import { useVoiceConsent } from "@/hooks/useVoiceConsent";

export default function SettingsPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { consent, setConsent, speak } = useVoiceConsent();

  const [me, setMe] = useState<LcDevice | null>(null);
  const [caps, setCaps] = useState<LcCapabilities | null>(null);
  const [baseInput, setBaseInput] = useState(getPair()?.base ?? "");
  const [ttsText, setTtsText] = useState("你好，这是 LifeCore 语音播报测试。");
  const [ttsBusy, setTtsBusy] = useState(false);
  const [confirmUnpair, setConfirmUnpair] = useState(false);
  const [recording] = useState(false);

  const load = useCallback(async () => {
    try {
      const [m, c] = await Promise.allSettled([
        lifecoreApi.me(),
        lifecoreApi.capabilities(),
      ]);
      if (m.status === "fulfilled") setMe(m.value);
      if (c.status === "fulfilled") setCaps(c.value);
    } catch (e) {
      showToast(errorMessage(e), "error");
    }
  }, [showToast]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleBaseSave = useCallback(() => {
    updateBase(baseInput);
    showToast(t.lifecore?.settings?.baseSaved ?? "服务器地址已更新", "success");
  }, [baseInput, showToast, t]);

  const handleTts = useCallback(async () => {
    if (!ttsText.trim()) return;
    setTtsBusy(true);
    try {
      await speak(ttsText);
    } finally {
      setTtsBusy(false);
    }
  }, [speak, ttsText]);

  const handleUnpair = useCallback(() => {
    clearPair();
    setConfirmUnpair(false);
    showToast(t.lifecore?.settings?.unpaired ?? "已解除配对", "success");
    // Hard navigate so all components re-read pair state
    window.location.assign("/lc-pair");
  }, [showToast, t]);

  const handleAsrTest = useCallback(async () => {
    if (recording) return; // placeholder for future ASR test button
    showToast(t.lifecore?.settings?.asrHint ?? "ASR 入口在 ChatPage 麦克风按钮", "success");
  }, [recording, showToast, t]);

  const copy = t.lifecore?.settings;
  const enabledKeys = caps
    ? Object.keys(caps).filter((k) => caps[k] === true)
    : [];

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-6">
      <Toast toast={toast} />

      {/* Device info */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Fingerprint className="h-4 w-4 text-primary" />
            {copy?.deviceTitle ?? "设备"}
          </CardTitle>
        </CardHeader>
        <CardContent className="grid gap-2 text-sm">
          {me ? (
            <>
              <div className="flex flex-wrap items-baseline gap-x-2">
                <span className="text-muted-foreground">
                  {copy?.deviceName ?? "名称"}
                </span>
                <span>{me.device}</span>
              </div>
              <div className="flex flex-wrap items-baseline gap-x-2">
                <span className="text-muted-foreground">
                  {copy?.deviceFp ?? "指纹"}
                </span>
                <code className="font-mono-ui text-xs">{me.fingerprint}</code>
              </div>
              <div className="flex flex-wrap items-baseline gap-x-2">
                <span className="text-muted-foreground">
                  {copy?.registeredAt ?? "注册时间"}
                </span>
                <span>{me.registered_at}</span>
              </div>
              <div className="flex flex-wrap items-baseline gap-x-2">
                <span className="text-muted-foreground">
                  {copy?.serverTime ?? "服务器时间"}
                </span>
                <span>{me.server_time}</span>
              </div>
            </>
          ) : (
            <div className="flex items-center gap-2 text-muted-foreground">
              <Spinner />
              <span>{t.common?.loading ?? "Loading…"}</span>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Server URL */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Server className="h-4 w-4 text-primary" />
            {copy?.serverTitle ?? "服务器"}
          </CardTitle>
        </CardHeader>
        <CardContent className="grid gap-2">
          <Label htmlFor="settings-base">{copy?.baseLabel ?? "服务器地址"}</Label>
          <div className="flex flex-wrap gap-2">
            <Input
              id="settings-base"
              value={baseInput}
              onChange={(e) => setBaseInput(e.target.value)}
              placeholder="https://windgraham.art"
            />
            <Button
              size="sm"
              className="uppercase"
              onClick={handleBaseSave}
              disabled={!baseInput.trim()}
            >
              {t.common?.save ?? "Save"}
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* TTS */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Volume2 className="h-4 w-4 text-primary" />
            {copy?.ttsTitle ?? "TTS 试听"}
          </CardTitle>
        </CardHeader>
        <CardContent className="grid gap-2">
          <Label htmlFor="settings-tts">
            {copy?.ttsLabel ?? "要说的话"}
          </Label>
          <div className="flex flex-wrap gap-2">
            <Input
              id="settings-tts"
              value={ttsText}
              onChange={(e) => setTtsText(e.target.value)}
              maxLength={200}
              onKeyDown={(e) => {
                if (e.key === "Enter") void handleTts();
              }}
            />
            <Button
              size="sm"
              className="uppercase"
              onClick={() => void handleTts()}
              disabled={ttsBusy || !ttsText.trim() || !consent}
              prefix={ttsBusy ? <Spinner /> : <AudioLines className="h-4 w-4" />}
            >
              {copy?.ttsPlay ?? "播放"}
            </Button>
          </div>
          {!consent && (
            <p className="text-xs text-muted-foreground">
              {copy?.ttsNeedConsent ??
                "需要先同意语音播报（ChatPage 顶部 banner）。"}
            </p>
          )}
        </CardContent>
      </Card>

      {/* Voice consent */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Volume2 className="h-4 w-4 text-primary" />
            {copy?.voiceTitle ?? "语音播报同意"}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-3">
          <Badge tone={consent ? "success" : "secondary"}>
            {consent
              ? (t.lifecore?.voice?.on ?? "已同意")
              : (t.lifecore?.voice?.off ?? "未同意")}
          </Badge>
          <Button
            size="sm"
            className="uppercase"
            onClick={() => setConsent(!consent)}
          >
            {consent
              ? (copy?.voiceDisable ?? "关闭")
              : (copy?.voiceEnable ?? "启用")}
          </Button>
        </CardContent>
      </Card>

      {/* ASR */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Mic className="h-4 w-4 text-primary" />
            {copy?.asrTitle ?? "语音输入 (ASR)"}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            {copy?.asrDesc ??
              "打开 ChatPage，使用输入框旁的麦克风按钮录制语音。"}
          </p>
          <Link to="/lc-chat" className="mt-2 inline-block">
            <Button
              ghost
              size="sm"
              className="uppercase"
              onClick={handleAsrTest}
            >
              <ExternalLink className="mr-1 h-3.5 w-3.5" />
              {copy?.asrCta ?? "前往 ChatPage"}
            </Button>
          </Link>
        </CardContent>
      </Card>

      {/* Capabilities */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <KeyRound className="h-4 w-4 text-primary" />
            {copy?.capsTitle ?? "网关能力"}
          </CardTitle>
        </CardHeader>
        <CardContent>
          {caps ? (
            enabledKeys.length > 0 ? (
              <div className="flex flex-wrap gap-1.5">
                {enabledKeys.map((k) => (
                  <Badge key={k} tone="secondary">
                    {k}
                  </Badge>
                ))}
              </div>
            ) : (
              <pre className="overflow-auto text-xs text-muted-foreground">
                {JSON.stringify(caps, null, 2).slice(0, 500)}
              </pre>
            )
          ) : (
            <div className="flex items-center gap-2 text-muted-foreground">
              <Spinner />
              <span>{t.common?.loading ?? "Loading…"}</span>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Unpair */}
      <Card className="border-destructive/40">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base text-destructive">
            <PowerOff className="h-4 w-4" />
            {copy?.unpairTitle ?? "危险操作"}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-3">
          <p className="flex-1 min-w-[12rem] text-sm text-muted-foreground">
            {copy?.unpairDesc ??
              "解除配对后本浏览器将失去访问权限，需要重新输入配对码。"}
          </p>
          <Button
            destructive
            size="sm"
            className="uppercase"
            onClick={() => setConfirmUnpair(true)}
            prefix={<LogOut className="h-4 w-4" />}
          >
            {copy?.unpairCta ?? "解除配对"}
          </Button>
        </CardContent>
      </Card>

      {/* Unpair confirm */}
      <Dialog
        open={confirmUnpair}
        onOpenChange={(o) => !o && setConfirmUnpair(false)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {copy?.unpairConfirmTitle ?? "解除配对？"}
            </DialogTitle>
            <DialogDescription>
              {copy?.unpairConfirmDesc ??
                "本浏览器将立即失去访问权限。继续操作无法撤销。"}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button ghost onClick={() => setConfirmUnpair(false)}>
              {t.common?.cancel ?? "Cancel"}
            </Button>
            <Button destructive onClick={handleUnpair}>
              {copy?.unpairCta ?? "解除配对"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
