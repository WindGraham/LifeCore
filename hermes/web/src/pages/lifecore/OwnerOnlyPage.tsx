/**
 * LifeCore OwnerOnlyPage — P1-S4 (G6 fix).
 *
 * Items explicitly flagged for owner attention (addressed_to_owner=1).
 * Server filters at the source; UI additionally chips priority to highlight
 * high-priority items with a deeper background.
 *
 * Server endpoint: GET /v2/notify/all?addressed_to_owner=1&limit=50
 * See `lifecoreApi.getAllItems`.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { ShieldAlert, ShieldCheck } from "lucide-react";
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
import {
  FilterGroup,
  Segmented,
} from "@nous-research/ui/ui/components/segmented";
import { useI18n } from "@/i18n";
import { usePageHeader } from "@/contexts/usePageHeader";
import { cn } from "@/lib/utils";
import {
  errorMessage,
  lifecoreApi,
  mapNotifyAction,
  type LcNotifyItem,
  type NotifyAction,
} from "@/lib/lifecore-api";
import { getPair } from "@/lib/lifecore-pair-store";

type PriorityFilter = "all" | "high" | "normal";

const PRIORITY_OPTIONS: { value: PriorityFilter; label: string }[] = [
  { value: "high", label: "high" },
  { value: "normal", label: "normal" },
  { value: "all", label: "all" },
];

function isHighPriority(item: LcNotifyItem): boolean {
  const p = (item.priority ?? "").toLowerCase();
  return p === "high" || p === "urgent" || p === "p0";
}

export default function OwnerOnlyPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { setAfterTitle } = usePageHeader();

  const pair = getPair();

  const [priorityFilter, setPriorityFilter] = useState<PriorityFilter>("all");
  const [items, setItems] = useState<LcNotifyItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const lc = t.lifecore;
  const copy = lc?.owner;
  const filterCopy = lc?.filter?.priority;

  const load = useCallback(async () => {
    if (!pair) return;
    setLoading(true);
    setErr(null);
    try {
      const r = await lifecoreApi.getAllItems(undefined, true, 50);
      setItems(r.items ?? []);
    } catch (e) {
      setErr(errorMessage(e));
      showToast(errorMessage(e), "error");
    } finally {
      setLoading(false);
    }
  }, [pair, showToast]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleDecision = useCallback(
    async (item: LcNotifyItem, action: NotifyAction) => {
      setActing(String(item.id));
      try {
        await lifecoreApi.sendFeedback(item.id, action);
        showToast(copy?.feedback ?? "Decision recorded", "success");
        await load();
      } catch (e) {
        showToast(errorMessage(e), "error");
      } finally {
        setActing(null);
      }
    },
    [load, showToast, copy?.feedback],
  );

  useEffect(() => {
    setAfterTitle(
      <span className="flex items-center gap-1.5">
        <ShieldCheck className="h-4 w-4 text-muted-foreground" />
        <span className="text-xs text-muted-foreground">
          {copy?.title ?? "Owner-only"}
        </span>
      </span>,
    );
    return () => setAfterTitle(null);
  }, [setAfterTitle, copy?.title]);

  const filtered = useMemo(() => {
    if (priorityFilter === "all") return items;
    if (priorityFilter === "high") return items.filter(isHighPriority);
    // normal = not high and has a priority
    return items.filter((it) => {
      const p = (it.priority ?? "").toLowerCase();
      if (p === "") return false; // exclude unknown-priority from "normal"
      return !isHighPriority(it);
    });
  }, [items, priorityFilter]);

  if (!pair) return null;

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-4">
      <Toast toast={toast} />

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <ShieldAlert className="h-4 w-4 text-primary" />
            {copy?.title ?? "Owner-only"}
          </CardTitle>
          <p className="text-xs text-muted-foreground">
            {copy?.subtitle ??
              "Items flagged for your eyes only — high-priority / awaiting your decision."}
          </p>
        </CardHeader>
        <CardContent>
          <FilterGroup label={filterCopy?.all ?? "Priority"}>
            <Segmented
              value={priorityFilter}
              onChange={(v) => setPriorityFilter(v as PriorityFilter)}
              options={PRIORITY_OPTIONS.map((o) => ({
                value: o.value,
                label: filterCopy?.[o.value] ?? o.label,
              }))}
            />
          </FilterGroup>
        </CardContent>
      </Card>

      {loading ? (
        <div className="flex items-center justify-center py-24">
          <Spinner className="text-2xl text-primary" />
        </div>
      ) : err ? (
        <Card>
          <CardContent className="py-8 text-center text-sm text-destructive">
            {err}
          </CardContent>
        </Card>
      ) : filtered.length === 0 ? (
        <Card>
          <CardContent className="py-8 text-center text-sm text-muted-foreground">
            {copy?.empty ?? "No owner-only items right now"}
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-3">
          {filtered.map((it) => {
            const isHigh = isHighPriority(it);
            const opts = it.options ?? [];
            const isActionable =
              it.state === "awaiting_feedback" && opts.length > 0;
            return (
              <Card
                key={String(it.id)}
                className={cn(isHigh && "border-warning/60 bg-warning/5")}
              >
                <CardContent className="flex items-start gap-4 py-4">
                    <div className="flex-1 min-w-0">
                      <div className="mb-1 flex items-center gap-2 flex-wrap">
                        {it.priority && (
                          <Badge tone={isHigh ? "destructive" : "secondary"}>
                            {it.priority}
                          </Badge>
                        )}
                        <Badge tone="outline">
                          {it.state ?? "—"}
                        </Badge>
                        {it.kind && (
                          <Badge tone="secondary">{it.kind}</Badge>
                        )}
                      </div>
                      <p className="text-sm">{it.summary ?? "(no summary)"}</p>
                      <p className="mt-1 text-xs text-muted-foreground">
                        #{it.id} · {it.channel_id ?? "—"}
                        {it.created_at
                          ? ` · ${new Date(it.created_at * 1000).toLocaleString()}`
                          : ""}
                      </p>
                    </div>
                    {isActionable && (
                      <div className="flex shrink-0 items-center gap-1">
                        {opts.map((opt) => (
                          <Button
                            key={opt}
                            size="sm"
                            className="uppercase"
                            disabled={acting === String(it.id)}
                            onClick={() =>
                              void handleDecision(it, mapNotifyAction(opt))
                            }
                            prefix={
                              acting === String(it.id) ? <Spinner /> : undefined
                            }
                          >
                            {opt}
                          </Button>
                        ))}
                      </div>
                    )}
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
