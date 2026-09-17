/**
 * LifeCore NotifyPage — pending decision dashboard.
 *
 * Maps the legacy notifyView() into PairingPage-style two-section layout:
 *   1. Active decision card (always single — server enforces single-activity lock)
 *   2. Queue card (read from `queue` field of /v2/notify/active)
 *
 * Decision buttons call POST /v2/notify/items/:id/feedback with action
 * mapped by `mapNotifyAction` (snooze / dismissed / actioned).
 * Auto-polls every 5s while page is mounted.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { Bell, BellOff, ListOrdered, RefreshCw } from "lucide-react";
import { Button } from "@nous-research/ui/ui/components/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@nous-research/ui/ui/components/card";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import { H2 } from "@nous-research/ui/ui/components/typography/h2";
import { useI18n } from "@/i18n";
import {
  errorMessage,
  lifecoreApi,
  mapNotifyAction,
  type LcNotifyItem,
} from "@/lib/lifecore-api";
import { usePageHeader } from "@/contexts/usePageHeader";

export default function NotifyPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { setAfterTitle } = usePageHeader();
  const [active, setActive] = useState<LcNotifyItem | null>(null);
  const [queue, setQueue] = useState<LcNotifyItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const cancelledRef = useRef(false);

  const load = useCallback(async () => {
    try {
      const r = await lifecoreApi.getActiveNotify();
      if (cancelledRef.current) return;
      setActive(r.active);
      setQueue(r.queue ?? []);
    } catch (e) {
      if (!cancelledRef.current) showToast(errorMessage(e), "error");
    } finally {
      if (!cancelledRef.current) setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    cancelledRef.current = false;
    void load();
    const interval = setInterval(load, 5000);
    return () => {
      cancelledRef.current = true;
      clearInterval(interval);
    };
  }, [load]);

  const handleDecision = useCallback(
    async (item: LcNotifyItem, option: string) => {
      setActing(true);
      try {
        await lifecoreApi.sendFeedback(item.id, mapNotifyAction(option));
        showToast(t.lifecore?.notify?.decided ?? "已记录决策", "success");
        await load();
      } catch (e) {
        showToast(errorMessage(e), "error");
      } finally {
        setActing(false);
      }
    },
    [load, showToast, t],
  );

  // Page header end: refresh + live indicator
  useEffect(() => {
    setAfterTitle(
      <span className="flex items-center gap-1.5">
        <Bell className="h-4 w-4 text-muted-foreground" />
        <span className="text-xs text-muted-foreground">
          {t.lifecore?.notify?.singleLock ?? "单活动锁：同一时间只一条决策"}
        </span>
      </span>,
    );
    return () => setAfterTitle(null);
  }, [setAfterTitle, t]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Spinner className="text-2xl text-primary" />
      </div>
    );
  }

  const copy = t.lifecore?.notify;
  return (
    <div className="flex min-w-0 max-w-full flex-col gap-6">
      <Toast toast={toast} />

      <section className="flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <H2 className="flex items-center gap-2 text-muted-foreground">
            <Bell className="h-4 w-4" />
            {copy?.pendingTitle ?? "等待你的决策"}
            {active && (
              <span className="text-text-tertiary">({active.id})</span>
            )}
          </H2>
          <Button
            ghost
            size="sm"
            className="uppercase"
            onClick={() => void load()}
            prefix={<RefreshCw className="h-4 w-4" />}
          >
            {t.common?.refresh ?? "Refresh"}
          </Button>
        </div>

        {active ? (
          <Card className="border-warning/50">
            <CardHeader className="pb-3">
              <CardTitle className="text-base">
                {active.summary ?? "(无内容)"}
              </CardTitle>
            </CardHeader>
            <CardContent className="grid gap-3">
              <div className="flex flex-wrap gap-2">
                {(active.options ?? []).map((opt) => (
                  <Button
                    key={opt}
                    size="sm"
                    className="uppercase"
                    disabled={acting}
                    onClick={() => void handleDecision(active, opt)}
                    prefix={acting ? <Spinner /> : undefined}
                  >
                    {opt}
                  </Button>
                ))}
              </div>
              <p className="text-xs text-muted-foreground">
                #{active.id} · {active.channel_id}
              </p>
            </CardContent>
          </Card>
        ) : (
          <Card>
            <CardContent className="flex flex-col items-center gap-2 py-8 text-center text-sm text-muted-foreground">
              <BellOff className="h-6 w-6 opacity-40" />
              <span>{copy?.empty ?? "当前没有等待决策的事项"}</span>
            </CardContent>
          </Card>
        )}
      </section>

      <section className="flex flex-col gap-3">
        <H2 className="flex items-center gap-2 text-muted-foreground">
          <ListOrdered className="h-4 w-4" />
          {copy?.queueTitle ?? "排队中"}
          {queue.length > 0 && (
            <span className="text-text-tertiary">({queue.length})</span>
          )}
        </H2>

        {queue.length === 0 ? (
          <Card>
            <CardContent className="py-6 text-center text-sm text-muted-foreground">
              {copy?.queueEmpty ?? "排队为空"}
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-2">
            {queue.map((q) => (
              <Card key={q.id}>
                <CardContent className="py-3">
                  <div className="flex items-baseline gap-2">
                    <span className="text-xs text-muted-foreground">
                      #{q.id}
                    </span>
                    <span className="truncate text-sm">{q.summary}</span>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
