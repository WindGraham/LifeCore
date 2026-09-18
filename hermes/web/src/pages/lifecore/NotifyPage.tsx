/**
 * LifeCore NotifyPage — pending decision dashboard + threads inbox.
 *
 * Three sections (top-to-bottom):
 *   1. Active decision card (always single — server enforces single-activity lock)
 *   2. Queue card (read from `queue` field of /v2/notify/active)
 *   3. Threads inbox (P1-S3 — G3 fix): per-thread row with a "Timeline"
 *      button that opens a Dialog showing every notify_item belonging to
 *      that thread, in id order. The dialog is fed by
 *      GET /v2/threads/:id/timeline (lifecoreApi.getThreadTimeline).
 *
 * Decision buttons call POST /v2/notify/items/:id/feedback with action
 * mapped by `mapNotifyAction` (snooze / dismissed / actioned).
 * Auto-polls every 5s while page is mounted.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import {
  Bell,
  BellOff,
  ListOrdered,
  MessageSquareText,
  RefreshCw,
  X,
} from "lucide-react";
import { Button } from "@nous-research/ui/ui/components/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@nous-research/ui/ui/components/card";
import { Badge } from "@nous-research/ui/ui/components/badge";
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
import { cn, themedBody } from "@/lib/utils";
import { useModalBehavior } from "@/hooks/useModalBehavior";

interface ThreadRow {
  id: string;
  raw: {
    id?: string | number;
    thread_id?: string | number;
    title?: string;
    channel_id?: string;
    item_count?: number;
    last_resolution?: string;
    last_resolved_at?: number;
    snoozed_until?: number;
    updated_at?: number;
  };
}

export default function NotifyPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { setAfterTitle } = usePageHeader();
  const [active, setActive] = useState<LcNotifyItem | null>(null);
  const [queue, setQueue] = useState<LcNotifyItem[]>([]);
  const [threads, setThreads] = useState<ThreadRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const cancelledRef = useRef(false);

  // P1-S3: timeline dialog state
  const [openThread, setOpenThread] = useState<ThreadRow | null>(null);
  const [timeline, setTimeline] = useState<LcNotifyItem[] | null>(null);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [timelineErr, setTimelineErr] = useState<string | null>(null);
  const timelineModalRef = useModalBehavior({
    open: openThread !== null,
    onClose: () => {
      setOpenThread(null);
      setTimeline(null);
      setTimelineErr(null);
    },
  });

  const load = useCallback(async () => {
    try {
      const [r, tr] = await Promise.allSettled([
        lifecoreApi.getActiveNotify(),
        lifecoreApi.listThreads(50),
      ]);
      if (cancelledRef.current) return;
      if (r.status === "fulfilled") {
        setActive(r.value.active);
        setQueue(r.value.queue ?? []);
      }
      if (tr.status === "fulfilled") {
        const list = (tr.value.threads ?? []).map((th) => {
          const idVal =
            th.id !== undefined
              ? String(th.id)
              : th.thread_id !== undefined
                ? String(th.thread_id)
                : "";
          return { id: idVal, raw: th };
        });
        setThreads(list.filter((x) => x.id !== ""));
      }
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

  // P1-S3: open timeline dialog for a thread row
  const openTimeline = useCallback(
    async (row: ThreadRow) => {
      setOpenThread(row);
      setTimeline(null);
      setTimelineErr(null);
      setTimelineLoading(true);
      try {
        const r = await lifecoreApi.getThreadTimeline(row.id);
        setTimeline(r.items ?? []);
      } catch (e) {
        setTimelineErr(errorMessage(e));
        showToast(errorMessage(e), "error");
      } finally {
        setTimelineLoading(false);
      }
    },
    [showToast],
  );

  // Page header after-title: single-activity-lock hint
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
  const tlCopy = t.lifecore?.timeline;
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

      {/* P1-S3 — Threads inbox */}
      <section className="flex flex-col gap-3">
        <H2 className="flex items-center gap-2 text-muted-foreground">
          <MessageSquareText className="h-4 w-4" />
          Threads
          {threads.length > 0 && (
            <span className="text-text-tertiary">({threads.length})</span>
          )}
        </H2>

        {threads.length === 0 ? (
          <Card>
            <CardContent className="py-6 text-center text-sm text-muted-foreground">
              No threads
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-2">
            {threads.map((row) => (
              <Card key={row.id}>
                <CardContent className="flex items-start gap-4 py-3">
                  <div className="flex-1 min-w-0">
                    <div className="mb-1 flex items-center gap-2 flex-wrap">
                      <span className="truncate font-medium text-sm">
                        {row.raw.title ?? `#${row.id}`}
                      </span>
                      {row.raw.channel_id && (
                        <Badge tone="outline">{row.raw.channel_id}</Badge>
                      )}
                      {typeof row.raw.item_count === "number" && (
                        <Badge tone="secondary">
                          {row.raw.item_count} items
                        </Badge>
                      )}
                    </div>
                    <p className="text-xs text-muted-foreground">
                      #{row.id}
                      {row.raw.last_resolution
                        ? ` · last: ${row.raw.last_resolution}`
                        : ""}
                      {row.raw.updated_at
                        ? ` · ${new Date(row.raw.updated_at * 1000).toLocaleString()}`
                        : ""}
                    </p>
                  </div>
                  <Button
                    ghost
                    size="sm"
                    className="uppercase"
                    onClick={() => void openTimeline(row)}
                  >
                    {tlCopy?.open ?? "Timeline"}
                  </Button>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </section>

      {/* P1-S3 — Timeline dialog */}
      {openThread !== null && (
        <div
          ref={timelineModalRef}
          className={cn(
            "fixed inset-0 z-[100] flex min-h-dvh items-start justify-center overflow-y-auto bg-background/85 px-4 py-4 sm:items-center sm:p-4",
          )}
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              setOpenThread(null);
              setTimeline(null);
              setTimelineErr(null);
            }
          }}
          role="dialog"
          aria-modal="true"
          aria-labelledby="lc-timeline-title"
        >
          <div
            className={cn(
              themedBody,
              "relative flex max-h-[calc(100dvh-2rem)] w-full max-w-2xl flex-col border border-border bg-card shadow-2xl sm:max-h-[90dvh]",
            )}
          >
            <Button
              ghost
              size="icon"
              className="absolute right-2 top-2 text-muted-foreground hover:text-foreground"
              onClick={() => {
                setOpenThread(null);
                setTimeline(null);
                setTimelineErr(null);
              }}
              aria-label={t.common?.close ?? "Close"}
            >
              <X />
            </Button>
            <header className="border-b border-border p-5 pb-3">
              <h2
                id="lc-timeline-title"
                className="font-mondwest text-display text-base tracking-wider"
              >
                {tlCopy?.title ?? "Thread timeline"} ·{" "}
                {tlCopy?.threadPrefix ?? "thread"} #{openThread.id}
              </h2>
              <p className="mt-1 text-xs text-muted-foreground">
                {openThread.raw.title ?? ""}
                {openThread.raw.channel_id
                  ? ` · ${openThread.raw.channel_id}`
                  : ""}
              </p>
            </header>
            <div className="grid gap-2 overflow-y-auto overscroll-contain p-4 sm:p-5">
              {timelineLoading && (
                <div className="flex items-center justify-center py-8">
                  <Spinner className="text-xl text-primary" />
                </div>
              )}
              {timelineErr && (
                <p className="text-sm text-destructive">{timelineErr}</p>
              )}
              {!timelineLoading && !timelineErr && timeline && timeline.length === 0 && (
                <p className="text-sm text-muted-foreground">
                  {tlCopy?.empty ?? "No items in this thread"}
                </p>
              )}
              {!timelineLoading && timeline && timeline.length > 0 && (
                <ol className="flex flex-col gap-2">
                  {timeline
                    .slice()
                    .sort((a, b) => {
                      const ai = Number(a.id);
                      const bi = Number(b.id);
                      if (Number.isFinite(ai) && Number.isFinite(bi)) return ai - bi;
                      return 0;
                    })
                    .map((it) => (
                      <li
                        key={String(it.id)}
                        className="rounded border border-border/60 bg-background/40 p-3"
                      >
                        <div className="mb-1 flex items-center gap-2 flex-wrap text-xs">
                          <span className="font-mono-ui">#{it.id}</span>
                          {it.state && <Badge tone="outline">{it.state}</Badge>}
                          {it.kind && <Badge tone="secondary">{it.kind}</Badge>}
                          {it.created_at && (
                            <span className="text-muted-foreground">
                              {new Date(it.created_at * 1000).toLocaleString()}
                            </span>
                          )}
                        </div>
                        <p className="text-sm">{it.summary ?? ""}</p>
                        {it.resolution && (
                          <p className="mt-1 text-xs text-muted-foreground">
                            resolution: {it.resolution}
                          </p>
                        )}
                      </li>
                    ))}
                </ol>
              )}
            </div>
            <div className="flex justify-end gap-2 border-t border-border p-3">
              <Button
                ghost
                size="sm"
                className="uppercase"
                onClick={() => {
                  setOpenThread(null);
                  setTimeline(null);
                  setTimelineErr(null);
                }}
              >
                {t.common?.close ?? "Close"}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}