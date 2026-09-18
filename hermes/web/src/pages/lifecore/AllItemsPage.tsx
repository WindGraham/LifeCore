/**
 * LifeCore AllItemsPage — P0-S1 (G1 fix).
 *
 * Lists every notify_item across all states, with a top Segmented filter
 * (logged / awaiting_feedback / resolved / queue / all). Click a row to jump
 * to the NotifyPage thread inbox (preserves thread_id filter via URL search).
 *
 * Server endpoint: GET /v2/notify/all?state=&addressed_to_owner=&limit=
 * See `lifecoreApi.getAllItems`.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router";
import { ListChecks, ListFilter } from "lucide-react";
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
import { errorMessage, lifecoreApi, type LcNotifyItem } from "@/lib/lifecore-api";
import { getPair } from "@/lib/lifecore-pair-store";

type StateFilter = "all" | "logged" | "queued" | "awaiting_feedback" | "resolved";

const STATE_OPTIONS: { value: StateFilter; label: string }[] = [
  { value: "logged", label: "logged" },
  { value: "queued", label: "queue" },
  { value: "awaiting_feedback", label: "awaiting" },
  { value: "resolved", label: "resolved" },
  { value: "all", label: "all" },
];

function stateBadgeTone(
  state?: string,
): "success" | "warning" | "destructive" | "secondary" | "outline" {
  switch (state) {
    case "awaiting_feedback":
      return "warning";
    case "queued":
      return "secondary";
    case "resolved":
      return "success";
    case "logged":
      return "outline";
    default:
      return "outline";
  }
}

function priorityTone(
  priority?: string,
): "destructive" | "secondary" | "outline" {
  if (!priority) return "outline";
  const p = priority.toLowerCase();
  if (p === "high" || p === "urgent" || p === "p0") return "destructive";
  return "secondary";
}

function truncate(s: string | undefined, n: number): string {
  if (!s) return "";
  return s.length <= n ? s : s.slice(0, n - 1) + "…";
}

export default function AllItemsPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { setAfterTitle } = usePageHeader();
  const navigate = useNavigate();

  const pair = getPair();

  const [state, setState] = useState<StateFilter>("all");
  const [items, setItems] = useState<LcNotifyItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const lc = t.lifecore;
  const copy = lc?.all;
  const filterCopy = lc?.filter?.state;

  const load = useCallback(
    (s: StateFilter) => {
      if (!pair) return;
      setLoading(true);
      setErr(null);
      lifecoreApi
        .getAllItems(s === "all" ? undefined : s, undefined, 200)
        .then((page) => {
          setItems(page.items ?? []);
        })
        .catch((e) => {
          setErr(errorMessage(e));
          showToast(errorMessage(e), "error");
        })
        .finally(() => setLoading(false));
    },
    [pair, showToast],
  );

  useEffect(() => {
    load(state);
  }, [load, state]);

  // Page header: filter hint
  useEffect(() => {
    setAfterTitle(
      <span className="flex items-center gap-1.5">
        <ListFilter className="h-4 w-4 text-muted-foreground" />
        <span className="text-xs text-muted-foreground">
          {(copy?.title ?? "All items") + " · " + (filterCopy?.[state] ?? state)}
        </span>
      </span>,
    );
    return () => setAfterTitle(null);
  }, [setAfterTitle, copy?.title, filterCopy, state]);

  const tableRows = useMemo(() => items, [items]);

  if (!pair) return null;

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-4">
      <Toast toast={toast} />

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <ListChecks className="h-4 w-4 text-primary" />
            {copy?.title ?? "All items"}
          </CardTitle>
          <p className="text-xs text-muted-foreground">
            {copy?.subtitle ?? "Every notify_item across all states."}
          </p>
        </CardHeader>
        <CardContent className="grid gap-3">
          <FilterGroup label={copy?.columns.state ?? "State"}>
            <Segmented
              value={state}
              onChange={(v) => setState(v as StateFilter)}
              options={STATE_OPTIONS.map((o) => ({
                value: o.value,
                label: filterCopy?.[o.value as keyof typeof filterCopy] ?? o.label,
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
            <div className="mt-3">
              <Button size="sm" onClick={() => load(state)}>
                {lc?.common?.retry ?? "Retry"}
              </Button>
            </div>
          </CardContent>
        </Card>
      ) : tableRows.length === 0 ? (
        <Card>
          <CardContent className="py-8 text-center text-sm text-muted-foreground">
            {copy?.empty ?? "No items in this state"}
          </CardContent>
        </Card>
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs uppercase tracking-wider text-muted-foreground">
                  <th className="px-3 py-2 font-medium">
                    {copy?.columns.summary ?? "Summary"}
                  </th>
                  <th className="px-3 py-2 font-medium">
                    {copy?.columns.priority ?? "Priority"}
                  </th>
                  <th className="px-3 py-2 font-medium">
                    {copy?.columns.state ?? "State"}
                  </th>
                  <th className="px-3 py-2 font-medium">
                    {copy?.columns.channel ?? "Channel"}
                  </th>
                  <th className="px-3 py-2 font-medium">
                    {copy?.columns.createdAt ?? "Created"}
                  </th>
                  <th className="px-3 py-2 font-medium">
                    {copy?.columns.ownerOnly ?? "Owner only"}
                  </th>
                </tr>
              </thead>
              <tbody>
                {tableRows.map((it) => (
                  <tr
                    key={String(it.id)}
                    onClick={() => {
                      if (it.thread_id !== undefined && it.thread_id !== null) {
                        navigate(
                          `/lc-notify?thread=${encodeURIComponent(
                            String(it.thread_id),
                          )}`,
                        );
                      }
                    }}
                    className={cn(
                      "border-b border-border/60 last:border-b-0 cursor-pointer",
                      "transition-colors hover:bg-muted/50",
                    )}
                  >
                    <td className="px-3 py-2 max-w-[28rem]">
                      <span className="line-clamp-2">
                        {truncate(it.summary ?? "", 80)}
                      </span>
                    </td>
                    <td className="px-3 py-2">
                      {it.priority ? (
                        <Badge tone={priorityTone(it.priority)}>
                          {it.priority}
                        </Badge>
                      ) : (
                        <span className="text-text-tertiary">—</span>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={stateBadgeTone(it.state)}>
                        {it.state ?? "—"}
                      </Badge>
                    </td>
                    <td className="px-3 py-2 font-mono-ui text-xs text-muted-foreground">
                      {it.channel_id ?? "—"}
                    </td>
                    <td className="px-3 py-2 text-xs text-muted-foreground">
                      {it.created_at
                        ? new Date(it.created_at * 1000).toLocaleString()
                        : "—"}
                    </td>
                    <td className="px-3 py-2">
                      {it.addressed_to_owner ? (
                        <Badge tone="destructive">★</Badge>
                      ) : (
                        <span className="text-text-tertiary">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  );
}
