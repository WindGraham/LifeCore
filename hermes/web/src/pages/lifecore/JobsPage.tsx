/**
 * LifeCore JobsPage — cron-style list of recurring agent tasks.
 *
 * Read-only view in v1: GET /v2/jobs and render rows. Per-row actions are
 * pause/resume and trigger (POST /v2/jobs/{jid}/{action}). Delete +
 * create live in SettingsPage advanced or as a future addition.
 *
 * Layout follows CronPage template: list + header refresh + Stats card
 * at top.
 */
import { useCallback, useEffect, useState } from "react";
import { Clock, Pause, Play, RefreshCw, Zap } from "lucide-react";
import { Button } from "@nous-research/ui/ui/components/button";
import { Badge } from "@nous-research/ui/ui/components/badge";
import {
  Card,
  CardContent,
} from "@nous-research/ui/ui/components/card";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { Stats } from "@nous-research/ui/ui/components/stats";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import { H2 } from "@nous-research/ui/ui/components/typography/h2";
import { usePageHeader } from "@/contexts/usePageHeader";
import { useI18n } from "@/i18n";
import { errorMessage, lifecoreApi, type LcJob } from "@/lib/lifecore-api";

type JobStatus = "running" | "paused" | "unknown";

function jobStatus(j: LcJob): JobStatus {
  if (j.paused) return "paused";
  if (j.enabled === false) return "paused";
  return "running";
}

function jobTone(s: JobStatus): "success" | "warning" | "secondary" {
  if (s === "running") return "success";
  if (s === "paused") return "warning";
  return "secondary";
}

export default function JobsPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { setEnd } = usePageHeader();
  const [jobs, setJobs] = useState<LcJob[]>([]);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await lifecoreApi.listJobs();
      const arr = Array.isArray(r) ? r : r.jobs ?? r.data ?? [];
      setJobs(arr);
    } catch (e) {
      showToast(errorMessage(e), "error");
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    void load();
  }, [load]);

  const action = useCallback(
    async (id: string, verb: "pause" | "resume" | "trigger") => {
      setActing(`${id}:${verb}`);
      try {
        await fetch(
          `${(import.meta.env.VITE_LIFECORE_BASE ?? "").replace(/\/+$/, "")}` +
            `/v2/jobs/${encodeURIComponent(id)}/${verb}`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              ...(getTokenHeader() ?? {}),
            },
          },
        );
        showToast(
          t.lifecore?.jobs?.[`ok_${verb}`] ?? `Job ${verb} ok`,
          "success",
        );
        await load();
      } catch (e) {
        showToast(errorMessage(e), "error");
      } finally {
        setActing(null);
      }
    },
    [load, showToast, t],
  );

  useEffect(() => {
    setEnd(
      <Button
        ghost
        size="sm"
        className="uppercase"
        onClick={() => void load()}
        disabled={loading}
        prefix={loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
      >
        {t.common?.refresh ?? "Refresh"}
      </Button>,
    );
    return () => setEnd(null);
  }, [load, loading, setEnd, t]);

  if (loading && jobs.length === 0) {
    return (
      <div className="flex items-center justify-center py-24">
        <Spinner className="text-2xl text-primary" />
      </div>
    );
  }

  const running = jobs.filter((j) => jobStatus(j) === "running").length;
  const paused = jobs.filter((j) => jobStatus(j) === "paused").length;
  const copy = t.lifecore?.jobs;

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-6">
      <Toast toast={toast} />

      <Stats
        items={[
          { label: copy?.total ?? "Total", value: String(jobs.length) },
          { label: copy?.running ?? "Running", value: String(running) },
          { label: copy?.paused ?? "Paused", value: String(paused) },
        ]}
      />

      <section className="flex flex-col gap-3">
        <H2 className="flex items-center gap-2 text-muted-foreground">
          <Clock className="h-4 w-4" />
          {copy?.title ?? "定时任务"}
          <span className="text-text-tertiary">({jobs.length})</span>
        </H2>

        {jobs.length === 0 ? (
          <Card>
            <CardContent className="py-8 text-center text-sm text-muted-foreground">
              {copy?.empty ?? "暂无任务"}
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-3">
            {jobs.map((j) => {
              const status = jobStatus(j);
              const id = j.id ?? j.name ?? "?";
              const isActing = acting === `${id}:pause` || acting === `${id}:resume` || acting === `${id}:trigger`;
              return (
                <Card key={id}>
                  <CardContent className="flex items-start gap-4 py-4">
                    <div className="flex-1 min-w-0">
                      <div className="mb-1 flex items-center gap-2 flex-wrap">
                        <span className="truncate font-medium text-sm">
                          {j.name || j.id || "?"}
                        </span>
                        <Badge tone={jobTone(status)}>
                          {status === "running"
                            ? (copy?.runBadge ?? "▶ 运行中")
                            : status === "paused"
                              ? (copy?.pauseBadge ?? "⏸ 暂停")
                              : (copy?.unknownBadge ?? "?")}
                        </Badge>
                      </div>
                      <p className="text-xs text-muted-foreground">
                        {j.schedule || j.cron || "(无 schedule)"}
                        {j.deliver ? ` · → ${j.deliver}` : ""}
                      </p>
                    </div>
                    <div className="flex items-center gap-1 shrink-0">
                      <Button
                        ghost
                        size="icon"
                        disabled={isActing}
                        onClick={() =>
                          void action(id, status === "paused" ? "resume" : "pause")
                        }
                        aria-label={
                          status === "paused"
                            ? (copy?.resume ?? "Resume")
                            : (copy?.pause ?? "Pause")
                        }
                        title={
                          status === "paused"
                            ? (copy?.resume ?? "Resume")
                            : (copy?.pause ?? "Pause")
                        }
                      >
                        {status === "paused" ? <Play /> : <Pause />}
                      </Button>
                      <Button
                        ghost
                        size="icon"
                        disabled={isActing}
                        onClick={() => void action(id, "trigger")}
                        aria-label={copy?.trigger ?? "Trigger"}
                        title={copy?.trigger ?? "Trigger"}
                      >
                        <Zap />
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}

function getTokenHeader(): Record<string, string> | null {
  try {
    const raw = window.localStorage.getItem("lc_pair_v1");
    if (!raw) return null;
    const p = JSON.parse(raw) as { token?: string };
    return p.token ? { Authorization: `Bearer ${p.token}` } : null;
  } catch {
    return null;
  }
}
