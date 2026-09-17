/**
 * LifeCore TodayPage — daily overview / homepage.
 *
 * Combines 4 quick signals:
 *   1. Pending notify (read /v2/notify/active; if active → show as decision card)
 *   2. Recent sessions (read /v2/sessions; show top 3 as quick links to chat)
 *   3. Channel count (read /v1/channels; one stat)
 *   4. Job count (read /v2/jobs; one stat)
 * Plus: if /v2/state-block is implemented server-side, render its `context`
 * field as a hint banner. If not implemented yet, fail silent.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router";
import {
  Activity,
  Bell,
  Clock,
  MessageSquare,
  Radio,
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
  type LcChannel,
  type LcJob,
  type LcNotifyItem,
  type LcSession,
} from "@/lib/lifecore-api";
import { getPair } from "@/lib/lifecore-pair-store";

export default function TodayPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { setEnd } = usePageHeader();

  const [sessions, setSessions] = useState<LcSession[]>([]);
  const [active, setActive] = useState<LcNotifyItem | null>(null);
  const [channels, setChannels] = useState<LcChannel[]>([]);
  const [jobs, setJobs] = useState<LcJob[]>([]);
  const [stateHint, setStateHint] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);

  const pair = getPair();

  const load = useCallback(async () => {
    if (!pair) return;
    setLoading(true);
    try {
      const [sess, notify, ch, jb, hint] = await Promise.allSettled([
        lifecoreApi.listSessions(),
        lifecoreApi.getActiveNotify(),
        lifecoreApi.listChannels(),
        lifecoreApi.listJobs(),
        lifecoreApi.stateBlock(),
      ]);
      if (sess.status === "fulfilled") setSessions(sess.value.data ?? []);
      if (notify.status === "fulfilled") setActive(notify.value.active);
      if (ch.status === "fulfilled") setChannels(ch.value.channels ?? []);
      if (jb.status === "fulfilled") {
        const j = jb.value;
        setJobs(Array.isArray(j) ? j : j.jobs ?? j.data ?? []);
      }
      if (hint.status === "fulfilled") {
        setStateHint(
          typeof hint.value.context === "string" ? hint.value.context : "",
        );
      }
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
        setActive(null);
        void load();
      } catch (e) {
        showToast(errorMessage(e), "error");
      } finally {
        setActing(false);
      }
    },
    [load, showToast, t],
  );

  // Page header end: refresh button (no setAfterTitle to keep it simple)
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

  const statsItems = useMemo(
    () => [
      {
        label: t.lifecore?.today?.statNotify ?? "待裁决",
        value: String(active ? 1 : 0),
      },
      {
        label: t.lifecore?.today?.statSessions ?? "活跃会话",
        value: String(sessions.length),
      },
      {
        label: t.lifecore?.today?.statChannels ?? "已注册通道",
        value: String(channels.length),
      },
      {
        label: t.lifecore?.today?.statJobs ?? "任务数",
        value: String(jobs.length),
      },
    ],
    [active, channels.length, jobs.length, sessions.length, t],
  );

  if (!pair) return null; // LifecoreIndex redirects before mounting this
  const recentSessions = sessions.slice(0, 3);

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-6">
      <Toast toast={toast} />

      {stateHint && (
        <Card className="border-primary/40">
          <CardContent className="flex items-start gap-2 py-3 text-sm">
            <Sparkles className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
            <span className="whitespace-pre-wrap text-muted-foreground">
              {stateHint}
            </span>
          </CardContent>
        </Card>
      )}

      <Stats items={statsItems} />

      {active && (
        <Card className="border-warning/50">
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <Bell className="h-4 w-4 text-warning" />
              {t.lifecore?.notify?.pendingTitle ?? "等待你的决策"}
            </CardTitle>
          </CardHeader>
          <CardContent className="grid gap-3">
            <p className="text-sm">{active.summary}</p>
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
      )}

      <section className="flex flex-col gap-3">
        <H2 className="flex items-center gap-2 text-muted-foreground">
          <MessageSquare className="h-4 w-4" />
          {t.lifecore?.today?.recentSessions ?? "最近会话"}
        </H2>
        {recentSessions.length === 0 ? (
          <Card>
            <CardContent className="py-8 text-center text-sm text-muted-foreground">
              {t.lifecore?.today?.noSessions ?? "暂无会话"}
            </CardContent>
          </Card>
        ) : (
          recentSessions.map((s) => (
            <Card key={s.id}>
              <CardContent className="flex items-start gap-4 py-4">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
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
                <Button
                  size="sm"
                  outlined
                  className="uppercase"
                  asChild
                >
                  <Link to={`/lc-chat?sid=${encodeURIComponent(s.id)}`}>
                    {t.lifecore?.today?.openChat ?? "继续对话"}
                  </Link>
                </Button>
              </CardContent>
            </Card>
          ))
        )}
      </section>

      <section className="flex flex-col gap-3">
        <H2 className="flex items-center gap-2 text-muted-foreground">
          <Radio className="h-4 w-4" />
          {t.lifecore?.today?.channelsTitle ?? "通道速览"}
        </H2>
        <div className="grid gap-2 sm:grid-cols-2">
          <Card>
            <CardContent className="flex items-center justify-between py-3">
              <div className="flex items-center gap-2 text-sm">
                <Clock className="h-4 w-4 text-muted-foreground" />
                {t.lifecore?.today?.goChannels ?? "管理通道"}
              </div>
              <Button size="sm" ghost className="uppercase" asChild>
                <Link to="/lc-channels">{t.common?.open ?? "Open"}</Link>
              </Button>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="flex items-center justify-between py-3">
              <div className="flex items-center gap-2 text-sm">
                <Clock className="h-4 w-4 text-muted-foreground" />
                {t.lifecore?.today?.goJobs ?? "管理任务"}
              </div>
              <Button size="sm" ghost className="uppercase" asChild>
                <Link to="/lc-jobs">{t.common?.open ?? "Open"}</Link>
              </Button>
            </CardContent>
          </Card>
        </div>
      </section>
    </div>
  );
}
