/**
 * LifeCore EventsPage — P0-S2 (G2 fix).
 *
 * Lists raw events as they land on the server. Top filter:
 *   - Channel picker (from /v1/channels; "All channels" by default)
 *   - "Pull new" button (sinceSeq = current max seq, fetches only newer rows)
 *
 * Server endpoints:
 *   GET /v2/events?channel_id=&since_seq=&limit=
 *   GET /v1/channels (to populate the picker)
 * See `lifecoreApi.getEvents` and `lifecoreApi.listChannels`.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { Activity, Filter, RefreshCw } from "lucide-react";
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
import {
  Select,
  SelectOption,
} from "@nous-research/ui/ui/components/select";
import { Label } from "@nous-research/ui/ui/components/label";
import { useI18n } from "@/i18n";
import { usePageHeader } from "@/contexts/usePageHeader";
import {
  errorMessage,
  lifecoreApi,
  type LcChannel,
  type LcEvent,
} from "@/lib/lifecore-api";
import { getPair } from "@/lib/lifecore-pair-store";

const ALL_CHANNELS = "__all__";

function truncate(s: string | undefined, n: number): string {
  if (!s) return "";
  return s.length <= n ? s : s.slice(0, n - 1) + "…";
}

export default function EventsPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { setEnd, setAfterTitle } = usePageHeader();

  const pair = getPair();

  const [channels, setChannels] = useState<LcChannel[]>([]);
  const [channelId, setChannelId] = useState<string>(ALL_CHANNELS);
  const [events, setEvents] = useState<LcEvent[]>([]);
  const [maxSeq, setMaxSeq] = useState<number>(0);
  const [loading, setLoading] = useState(true);
  const [pulling, setPulling] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const lc = t.lifecore;
  const copy = lc?.events;

  const loadChannels = useCallback(async () => {
    try {
      const r = await lifecoreApi.listChannels();
      setChannels(r.channels ?? []);
    } catch {
      /* picker just stays empty; events page still works for "all channels" */
    }
  }, []);

  const load = useCallback(
    async (since?: number) => {
      if (!pair) return;
      setLoading(true);
      setErr(null);
      try {
        const r = await lifecoreApi.getEvents(
          channelId === ALL_CHANNELS ? undefined : channelId,
          since,
          200,
        );
        const evs = r.events ?? [];
        // If we're doing an incremental pull, prepend to keep growing list.
        if (typeof since === "number") {
          setEvents((prev) => [...evs, ...prev]);
        } else {
          setEvents(evs);
        }
        const top = evs.reduce(
          (acc, e) => (typeof e.seq === "number" && e.seq > acc ? e.seq : acc),
          since ?? 0,
        );
        setMaxSeq(top);
      } catch (e) {
        setErr(errorMessage(e));
        showToast(errorMessage(e), "error");
      } finally {
        setLoading(false);
        setPulling(false);
      }
    },
    [channelId, pair, showToast],
  );

  useEffect(() => {
    void loadChannels();
  }, [loadChannels]);

  useEffect(() => {
    // Channel changed → reset and full reload.
    setEvents([]);
    setMaxSeq(0);
    void load();
  }, [load]);

  const handlePullNew = useCallback(() => {
    setPulling(true);
    void load(maxSeq);
  }, [load, maxSeq]);

  useEffect(() => {
    setAfterTitle(
      <span className="flex items-center gap-1.5">
        <Activity className="h-4 w-4 text-muted-foreground" />
        <span className="text-xs text-muted-foreground">
          {(copy?.title ?? "Event stream") +
            " · " +
            (copy?.maxSeq ?? "max seq") +
            " " +
            String(maxSeq)}
        </span>
      </span>,
    );
    setEnd(
      <Button
        size="sm"
        className="uppercase"
        onClick={handlePullNew}
        disabled={pulling || loading}
        prefix={pulling ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
      >
        {copy?.pullNew ?? "Pull new"}
      </Button>,
    );
    return () => {
      setAfterTitle(null);
      setEnd(null);
    };
  }, [
    setAfterTitle,
    setEnd,
    copy?.title,
    copy?.maxSeq,
    copy?.pullNew,
    maxSeq,
    pulling,
    loading,
    handlePullNew,
  ]);

  const rows = useMemo(() => events, [events]);

  if (!pair) return null;

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-4">
      <Toast toast={toast} />

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Filter className="h-4 w-4 text-primary" />
            {copy?.title ?? "Event stream"}
          </CardTitle>
          <p className="text-xs text-muted-foreground">
            {copy?.subtitle ??
              "Raw events as they land on the server — useful for tracing channel → notify_items."}
          </p>
        </CardHeader>
        <CardContent>
          <div className="grid gap-2 max-w-md">
            <Label htmlFor="lc-events-channel">
              {copy?.channelLabel ?? "Channel"}
            </Label>
            <Select
              id="lc-events-channel"
              value={channelId}
              onValueChange={(v) => setChannelId(v)}
            >
              <SelectOption value={ALL_CHANNELS}>
                {copy?.channelAll ?? "All channels"}
              </SelectOption>
              {channels.map((c) => (
                <SelectOption key={c.channel_id} value={c.channel_id}>
                  {c.name || c.channel_id}
                </SelectOption>
              ))}
            </Select>
          </div>
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
      ) : rows.length === 0 ? (
        <Card>
          <CardContent className="py-8 text-center text-sm text-muted-foreground">
            {copy?.empty ?? "No events for this filter"}
          </CardContent>
        </Card>
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs uppercase tracking-wider text-muted-foreground">
                  <th className="px-3 py-2 font-medium">
                    {copy?.columns.seq ?? "seq"}
                  </th>
                  <th className="px-3 py-2 font-medium">
                    {copy?.columns.channel ?? "channel"}
                  </th>
                  <th className="px-3 py-2 font-medium">
                    {copy?.columns.payload ?? "payload"}
                  </th>
                  <th className="px-3 py-2 font-medium">
                    {copy?.columns.receivedAt ?? "received_at"}
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((e) => (
                  <tr
                    key={String(e.seq)}
                    className="border-b border-border/60 last:border-b-0"
                  >
                    <td className="px-3 py-2 font-mono-ui text-xs">
                      {e.seq}
                    </td>
                    <td className="px-3 py-2 font-mono-ui text-xs text-muted-foreground">
                      {e.channel_id}
                    </td>
                    <td className="px-3 py-2 max-w-[40rem]">
                      <code className="font-mono-ui text-xs break-all">
                        {truncate(e.payload, 100)}
                      </code>
                    </td>
                    <td className="px-3 py-2 text-xs text-muted-foreground">
                      {e.received_at
                        ? new Date(e.received_at * 1000).toLocaleString()
                        : "—"}
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
