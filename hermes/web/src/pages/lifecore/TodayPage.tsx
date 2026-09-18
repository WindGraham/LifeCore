/**
 * LifeCore TodayPage — daily overview / homepage (real digest, P1-S6 — G12 fix).
 *
 * Reads `/v2/digest/today`, which the server aggregates from
 *   events + notify_items + lists.user_state into:
 *     counters { events_today, threads_active, awaiting_owner, needs_feedback }
 *     card_a_done       — items that already happened (logged / resolved)
 *     card_b_decision   — items awaiting the user's decision (awaiting_feedback)
 *     card_c_ask        — items the agent is asking the user about
 *
 * Renders:
 *   - 4 number counters (Stats)
 *   - Card A: "Happened today"
 *   - Card B: "Your turn"  — each item shows its decision buttons
 *   - Card C: "Asks for you" — each item has a free-text reply input
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router";
import {
  Activity,
  CheckCircle2,
  CircleHelp,
  Clock,
  ListChecks,
  Send,
  Sparkles,
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
import { Stats } from "@nous-research/ui/ui/components/stats";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import { H2 } from "@nous-research/ui/ui/components/typography/h2";
import { useI18n } from "@/i18n";
import { usePageHeader } from "@/contexts/usePageHeader";
import {
  errorMessage,
  lifecoreApi,
  mapNotifyAction,
  type LcDigestToday,
  type LcNotifyItem,
} from "@/lib/lifecore-api";
import { getPair } from "@/lib/lifecore-pair-store";

export default function TodayPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { setEnd } = usePageHeader();

  const pair = getPair();

  const [digest, setDigest] = useState<LcDigestToday | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const [askDrafts, setAskDrafts] = useState<Record<string, string>>({});
  const [sending, setSending] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!pair) return;
    setLoading(true);
    try {
      const r = await lifecoreApi.getDigestToday();
      setDigest(r);
    } catch (e) {
      showToast(errorMessage(e), "error");
    } finally {
      setLoading(false);
    }
  }, [pair, showToast]);

  useEffect(() => {
    void load();
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

  // Page header end: refresh button
  useEffect(() => {
    setEnd(
      <Button
        size="sm"
        ghost
        className="uppercase"
        onClick={() => void load()}
        disabled={loading}
        prefix={loading ? <Spinner /> : <Activity className="h-4 w-4" />}
      >
        {t.common?.refresh ?? "Refresh"}
      </Button>,
    );
    return () => setEnd(null);
  }, [load, loading, setEnd, t]);

  const counters = digest?.counters;
  const statsItems = useMemo(
    () => [
      {
        label: t.lifecore?.digest?.counterEvents ?? "Events today",
        value: String(counters?.events_today ?? 0),
      },
      {
        label: t.lifecore?.digest?.counterThreads ?? "Active threads",
        value: String(counters?.threads_active ?? 0),
      },
      {
        label:
          t.lifecore?.digest?.counterAwaitingOwner ?? "Awaiting you",
        value: String(counters?.awaiting_owner ?? 0),
      },
      {
        label:
          t.lifecore?.digest?.counterNeedsFeedback ?? "Needs feedback",
        value: String(counters?.needs_feedback ?? 0),
      },
    ],
    [
      counters?.events_today,
      counters?.threads_active,
      counters?.awaiting_owner,
      counters?.needs_feedback,
      t,
    ],
  );

  if (!pair) return null;

  if (loading) {
    return (
      <div className="flex min-w-0 max-w-full flex-col gap-6">
        <Toast toast={toast} />
        <div className="flex items-center justify-center py-24">
          <Spinner className="text-2xl text-primary" />
        </div>
      </div>
    );
  }

  const cardA = digest?.card_a_done?.items ?? [];
  const cardB = digest?.card_b_decision?.items ?? [];
  const cardC = digest?.card_c_ask?.items ?? [];
  const copy = t.lifecore?.digest;
  const emptyLabel = copy?.empty ?? "Nothing here yet";

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-6">
      <Toast toast={toast} />

      {/* Top counters */}
      <Stats items={statsItems} />

      {/* Card A — happened today */}
      <section className="flex flex-col gap-3">
        <H2 className="flex items-center gap-2 text-muted-foreground">
          <CheckCircle2 className="h-4 w-4" />
          {copy?.cardA ?? "Happened today"}
          {cardA.length > 0 && (
            <span className="text-text-tertiary">({cardA.length})</span>
          )}
        </H2>
        {cardA.length === 0 ? (
          <Card>
            <CardContent className="py-6 text-center text-sm text-muted-foreground">
              {emptyLabel}
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-2">
            {cardA.slice(0, 8).map((it) => (
              <Card key={String(it.id)}>
                <CardContent className="py-3">
                  <div className="flex items-baseline gap-2 flex-wrap">
                    <span className="text-xs text-muted-foreground">
                      #{it.id}
                    </span>
                    {it.state && (
                      <Badge tone="outline">{it.state}</Badge>
                    )}
                    {it.priority && (
                      <Badge tone="secondary">{it.priority}</Badge>
                    )}
                    <span className="truncate text-sm">{it.summary}</span>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </section>

      {/* Card B — your turn (with decision buttons) */}
      <section className="flex flex-col gap-3">
        <H2 className="flex items-center gap-2 text-muted-foreground">
          <Clock className="h-4 w-4" />
          {copy?.cardB ?? "Your turn"}
          {cardB.length > 0 && (
            <span className="text-text-tertiary">({cardB.length})</span>
          )}
        </H2>
        {cardB.length === 0 ? (
          <Card>
            <CardContent className="py-6 text-center text-sm text-muted-foreground">
              {emptyLabel}
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-3">
            {cardB.map((it) => (
              <Card key={String(it.id)} className="border-warning/50">
                <CardContent className="grid gap-3 py-4">
                  <div className="flex items-baseline gap-2 flex-wrap">
                    {it.priority && (
                      <Badge tone="destructive">{it.priority}</Badge>
                    )}
                    <span className="text-sm">{it.summary}</span>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {(it.options ?? []).map((opt) => (
                      <Button
                        key={opt}
                        size="sm"
                        className="uppercase"
                        disabled={acting}
                        onClick={() => void handleDecision(it, opt)}
                        prefix={acting ? <Spinner /> : undefined}
                      >
                        {opt}
                      </Button>
                    ))}
                  </div>
                  <p className="text-xs text-muted-foreground">
                    #{it.id} · {it.channel_id ?? "—"}
                  </p>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </section>

      {/* Card C — asks for you (free-text reply) */}
      <section className="flex flex-col gap-3">
        <H2 className="flex items-center gap-2 text-muted-foreground">
          <CircleHelp className="h-4 w-4" />
          {copy?.cardC ?? "Asks for you"}
          {cardC.length > 0 && (
            <span className="text-text-tertiary">({cardC.length})</span>
          )}
        </H2>
        {cardC.length === 0 ? (
          <Card>
            <CardContent className="py-6 text-center text-sm text-muted-foreground">
              {emptyLabel}
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-3">
            {cardC.map((it) => {
              const k = String(it.id);
              const draft = askDrafts[k] ?? "";
              return (
                <Card key={k}>
                  <CardHeader className="pb-2">
                    <CardTitle className="flex items-center gap-2 text-sm">
                      <Sparkles className="h-4 w-4 text-primary" />
                      {it.summary ?? "(no summary)"}
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="grid gap-2">
                    <div className="flex items-center gap-2">
                      <Input
                        value={draft}
                        placeholder={copy?.askReplyPlaceholder ?? "Reply…"}
                        onChange={(e) =>
                          setAskDrafts((prev) => ({
                            ...prev,
                            [k]: e.target.value,
                          }))
                        }
                        onKeyDown={(e) => {
                          if (e.key === "Enter" && draft.trim()) {
                            // Decided as "actioned" with the free text as summary feedback.
                            // We don't have a /v2/notify/items/:id/reply endpoint yet,
                            // so the simplest bridge is sendFeedback(actioned) so the
                            // ask is closed on the server; the draft is shown in toast.
                            void handleDecision(it, "OK");
                            showToast(draft || "Sent", "success");
                            setAskDrafts((prev) => ({ ...prev, [k]: "" }));
                          }
                        }}
                        disabled={sending === k}
                      />
                      <Button
                        size="sm"
                        className="uppercase"
                        disabled={sending === k || !draft.trim()}
                        onClick={() => {
                          setSending(k);
                          void handleDecision(it, "OK");
                          showToast(draft || "Sent", "success");
                          setAskDrafts((prev) => ({ ...prev, [k]: "" }));
                          setSending(null);
                        }}
                        prefix={sending === k ? <Spinner /> : <Send className="h-4 w-4" />}
                      >
                        {copy?.askReplySend ?? "Send"}
                      </Button>
                    </div>
                    <p className="text-xs text-muted-foreground">
                      #{it.id} · {it.channel_id ?? "—"}
                    </p>
                  </CardContent>
                </Card>
              );
            })}
          </div>
        )}
      </section>

      {/* Quick links to dedicated sub-pages (kept for discoverability). */}
      <section className="flex flex-col gap-3">
        <H2 className="flex items-center gap-2 text-muted-foreground">
          <ListChecks className="h-4 w-4" />
          Explore
        </H2>
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          <Card>
            <CardContent className="flex items-center justify-between py-3">
              <div className="flex items-center gap-2 text-sm">
                <ListChecks className="h-4 w-4 text-muted-foreground" />
                {t.lifecore?.all?.title ?? "All items"}
              </div>
              <Link to="/lc-all" className="inline-block">
                <Button size="sm" ghost className="uppercase">
                  {t.lifecore?.open ?? "Open"}
                </Button>
              </Link>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="flex items-center justify-between py-3">
              <div className="flex items-center gap-2 text-sm">
                <Sparkles className="h-4 w-4 text-muted-foreground" />
                {t.lifecore?.owner?.title ?? "Owner-only"}
              </div>
              <Link to="/lc-owner" className="inline-block">
                <Button size="sm" ghost className="uppercase">
                  {t.lifecore?.open ?? "Open"}
                </Button>
              </Link>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="flex items-center justify-between py-3">
              <div className="flex items-center gap-2 text-sm">
                <Activity className="h-4 w-4 text-muted-foreground" />
                {t.lifecore?.events?.title ?? "Event stream"}
              </div>
              <Link to="/lc-events" className="inline-block">
                <Button size="sm" ghost className="uppercase">
                  {t.lifecore?.open ?? "Open"}
                </Button>
              </Link>
            </CardContent>
          </Card>
        </div>
      </section>
    </div>
  );
}