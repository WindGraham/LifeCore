/**
 * LifeCore PairPage — independent landing surface.
 *
 * Replaces the unpaired branch of legacy console.html (`pairView()`).
 * Layout: centered card, base URL input, 8-digit code input, "Pair" button.
 * On success → navigate to /lc-today. Standalone route — NOT a sub-page
 * of SettingsPage (per design: pairing is the product entry point).
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router";
import { KeyRound, Link2, ShieldCheck } from "lucide-react";
import { Button } from "@nous-research/ui/ui/components/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@nous-research/ui/ui/components/card";
import { Input } from "@nous-research/ui/ui/components/input";
import { Label } from "@nous-research/ui/ui/components/label";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import { useI18n } from "@/i18n";
import { errorMessage, lifecoreApi } from "@/lib/lifecore-api";
import { setPair, getPair } from "@/lib/lifecore-pair-store";

const DEVICE_NAME = "web-console";

export default function PairPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { toast, showToast } = useToast();
  const [base, setBase] = useState<string>(
    () => getPair()?.base ?? window.location.origin,
  );
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const codeRef = useRef<HTMLInputElement>(null);

  // If already paired (e.g. user typed the URL by hand), skip ahead.
  useEffect(() => {
    if (getPair()) {
      navigate("/lc-today", { replace: true });
    }
  }, [navigate]);

  const handlePair = useCallback(async () => {
    const trimmedBase = base.trim().replace(/\/+$/, "");
    const trimmedCode = code.trim();
    if (!trimmedBase) {
      showToast(t.lifecore?.pair?.needBase ?? "请填写服务器地址", "error");
      return;
    }
    if (!/^\d{8}$/.test(trimmedCode)) {
      showToast(
        t.lifecore?.pair?.needCode ?? "配对码应为 8 位数字",
        "error",
      );
      codeRef.current?.focus();
      return;
    }
    setBusy(true);
    try {
      const resp = await lifecoreApi.pair(trimmedBase, trimmedCode, DEVICE_NAME);
      setPair({
        base: trimmedBase,
        token: resp.device_token,
        fingerprint: resp.fingerprint,
        voiceConsent: false,
      });
      showToast(t.lifecore?.pair?.success ?? "配对成功", "success");
      navigate("/lc-today", { replace: true });
    } catch (e) {
      showToast(
        `${t.lifecore?.pair?.failPrefix ?? "配对失败："}${errorMessage(e)}`,
        "error",
      );
    } finally {
      setBusy(false);
    }
  }, [base, code, navigate, showToast, t]);

  const copy = t.lifecore?.pair;

  return (
    <div className="flex min-w-0 flex-1 items-center justify-center px-3 py-10 sm:px-6">
      <Toast toast={toast} />
      <Card className="w-full max-w-md">
        <CardHeader className="space-y-2 pb-4">
          <div className="flex items-center gap-2">
            <ShieldCheck className="h-5 w-5 text-primary" />
            <CardTitle className="text-lg">
              {copy?.title ?? "LifeCore 设备配对"}
            </CardTitle>
          </div>
          <p className="text-sm text-muted-foreground">
            {copy?.subtitle ??
              "输入 8 位配对码，把这个浏览器注册为你的设备。"}
          </p>
        </CardHeader>
        <CardContent className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="pair-base">
              <Link2 className="mr-1 inline h-3.5 w-3.5" />
              {copy?.baseLabel ?? "服务器"}
            </Label>
            <Input
              id="pair-base"
              autoComplete="url"
              placeholder="https://windgraham.art"
              value={base}
              onChange={(e) => setBase(e.target.value)}
              disabled={busy}
            />
          </div>
          <div className="grid gap-2">
            <Label htmlFor="pair-code">
              <KeyRound className="mr-1 inline h-3.5 w-3.5" />
              {copy?.codeLabel ?? "配对码"}
            </Label>
            <Input
              id="pair-code"
              ref={codeRef}
              autoFocus
              autoComplete="one-time-code"
              inputMode="numeric"
              maxLength={8}
              placeholder={
                copy?.codePlaceholder ?? "8 位数字（10 分钟内有效）"
              }
              value={code}
              onChange={(e) =>
                setCode(e.target.value.replace(/\D/g, "").slice(0, 8))
              }
              onKeyDown={(e) => {
                if (e.key === "Enter") void handlePair();
              }}
              disabled={busy}
            />
            <p className="text-xs text-muted-foreground">
              {copy?.codeHint
                ?.replace("{base}", base.replace(/^https?:\/\//, ""))
                ??
                `在 ${base.replace(/^https?:\/\//, "")}/pair 页面获取配对码`}
            </p>
          </div>
          <Button
            size="sm"
            className="uppercase"
            onClick={() => void handlePair()}
            disabled={busy}
            prefix={busy ? <Spinner /> : undefined}
          >
            {busy
              ? (copy?.pairing ?? "配对中…")
              : (copy?.pair ?? "配对")}
          </Button>
          <p className="text-center text-xs text-muted-foreground">
            {copy?.footer ?? (
              <>
                配对后本浏览器获得与 App 相同的设备凭证。已配对？前往{" "}
                <Link
                  to="/lc-today"
                  className="underline-offset-2 hover:underline"
                >
                  今日视图
                </Link>
                。
              </>
            )}
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
