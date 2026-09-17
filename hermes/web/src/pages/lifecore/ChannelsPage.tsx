/**
 * LifeCore ChannelsPage — register and manage event ingest channels.
 *
 * Uses the CronPage template (list + per-row action buttons + header "Add"
 * button opening a modal) since channels are list-shaped with one-shot
 * register flow. POST /v1/channels returns ingest_url + secret, displayed
 * inline with a CopyButton helper.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { Copy, Plus, Radio, TestTube, Trash2, X } from "lucide-react";
import { Button } from "@nous-research/ui/ui/components/button";
import { Badge } from "@nous-research/ui/ui/components/badge";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@nous-research/ui/ui/components/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@nous-research/ui/ui/components/dialog";
import { Input } from "@nous-research/ui/ui/components/input";
import { Label } from "@nous-research/ui/ui/components/label";
import {
  Select,
  SelectOption,
} from "@nous-research/ui/ui/components/select";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import { useConfirmDelete } from "@nous-research/ui/hooks/use-confirm-delete";
import { useModalBehavior } from "@/hooks/useModalBehavior";
import { useI18n } from "@/i18n";
import { usePageHeader } from "@/contexts/usePageHeader";
import { errorMessage, lifecoreApi, type LcChannel } from "@/lib/lifecore-api";
import { cn, themedBody } from "@/lib/utils";

const ARCHETYPES = [
  "message",
  "metric",
  "file",
  "task",
  "calendar",
  "alert",
  "result",
];

const UPLINK_LEVELS = [
  { value: "AB", label: "AB 级" },
  { value: "A", label: "仅 A 级" },
];

export default function ChannelsPage() {
  const { t } = useI18n();
  const { toast, showToast } = useToast();
  const { setEnd } = usePageHeader();
  const [channels, setChannels] = useState<LcChannel[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [archetype, setArchetype] = useState("message");
  const [uplink, setUplink] = useState("AB");
  const [saving, setSaving] = useState(false);
  const [created, setCreated] = useState<{
    ingest_url: string;
    secret: string;
  } | null>(null);

  const createModalRef = useModalBehavior({
    open,
    onClose: () => {
      setOpen(false);
      setCreated(null);
    },
  });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await lifecoreApi.listChannels();
      setChannels(r.channels ?? []);
    } catch (e) {
      showToast(errorMessage(e), "error");
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    void load();
  }, [load]);

  const channelDelete = useConfirmDelete({
    onDelete: useCallback(
      async (id: string) => {
        try {
          await lifecoreApi.deleteChannel(id);
          showToast(t.lifecore?.channels?.deleted ?? "已删除通道", "success");
          await load();
        } catch (e) {
          showToast(errorMessage(e), "error");
          throw e;
        }
      },
      [load, showToast, t],
    ),
  });

  const handleCreate = useCallback(async () => {
    if (!name.trim()) return;
    setSaving(true);
    try {
      const r = await lifecoreApi.registerChannel({
        name: name.trim(),
        archetype,
        uplink_level: uplink,
      });
      setCreated({ ingest_url: r.ingest_url, secret: r.secret });
      showToast(t.lifecore?.channels?.created ?? "通道已注册", "success");
      setName("");
      await load();
    } catch (e) {
      showToast(errorMessage(e), "error");
    } finally {
      setSaving(false);
    }
  }, [archetype, name, uplink, load, showToast, t]);

  const handleTest = useCallback(
    async (id: string) => {
      try {
        await lifecoreApi.testEvent(id);
        showToast(t.lifecore?.channels?.tested ?? "测试事件已注入", "success");
      } catch (e) {
        showToast(errorMessage(e), "error");
      }
    },
    [showToast, t],
  );

  useEffect(() => {
    setEnd(
      <Button
        size="sm"
        className="uppercase"
        prefix={<Plus className="h-4 w-4" />}
        onClick={() => {
          setCreated(null);
          setOpen(true);
        }}
      >
        {t.common?.create ?? "Register"}
      </Button>,
    );
    return () => setEnd(null);
  }, [setEnd, t]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Spinner className="text-2xl text-primary" />
      </div>
    );
  }

  const copy = t.lifecore?.channels;
  return (
    <div className="flex min-w-0 max-w-full flex-col gap-6">
      <Toast toast={toast} />

      {channels.length === 0 ? (
        <Card>
          <CardContent className="py-8 text-center text-sm text-muted-foreground">
            {copy?.empty ?? "暂无通道"}
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-3">
          {channels.map((c) => (
            <Card key={c.channel_id}>
              <CardContent className="flex items-start gap-4 py-4">
                <div className="flex-1 min-w-0">
                  <div className="mb-1 flex items-center gap-2 flex-wrap">
                    <span className="truncate font-medium text-sm">
                      {c.name}
                    </span>
                    <Badge tone="outline">{c.archetype}</Badge>
                    <Badge tone="secondary">{c.uplink_level}</Badge>
                    {c.revoked && (
                      <Badge tone="destructive">revoked</Badge>
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground">
                    {c.channel_id}
                    {c.created_at
                      ? ` · ${new Date(c.created_at * 1000).toLocaleString()}`
                      : ""}
                  </p>
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  <Button
                    ghost
                    size="icon"
                    onClick={() => void handleTest(c.channel_id)}
                    aria-label={copy?.test ?? "Test event"}
                    title={copy?.test ?? "Test event"}
                  >
                    <TestTube />
                  </Button>
                  <Button
                    ghost
                    destructive
                    size="icon"
                    onClick={() => channelDelete.requestDelete(c.channel_id)}
                    aria-label={t.common?.delete ?? "Delete"}
                    title={t.common?.delete ?? "Delete"}
                  >
                    <Trash2 />
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Delete confirm dialog */}
      <Dialog
        open={channelDelete.isOpen}
        onOpenChange={(o) => (o ? null : channelDelete.cancel())}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{copy?.deleteTitle ?? "删除通道？"}</DialogTitle>
            <DialogDescription>
              {copy?.deleteDesc ??
                "删除后该通道的 ingest_url 将无法继续上报事件。"}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button ghost onClick={() => channelDelete.cancel()}>
              {t.common?.cancel ?? "Cancel"}
            </Button>
            <Button
              destructive
              onClick={() => void channelDelete.confirm()}
              disabled={channelDelete.isDeleting}
              prefix={channelDelete.isDeleting ? <Spinner /> : undefined}
            >
              {t.common?.delete ?? "Delete"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Create modal */}
      {open && (
        <div
          ref={createModalRef}
          className="fixed inset-0 z-[100] flex min-h-dvh items-start justify-center overflow-y-auto bg-background/85 px-4 py-4 sm:items-center sm:p-4"
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              setOpen(false);
              setCreated(null);
            }
          }}
          role="dialog"
          aria-modal="true"
          aria-labelledby="lc-channel-create-title"
        >
          <div
            className={cn(
              themedBody,
              "relative flex max-h-[calc(100dvh-2rem)] w-full max-w-lg flex-col border border-border bg-card shadow-2xl sm:max-h-[90dvh]",
            )}
          >
            <Button
              ghost
              size="icon"
              className="absolute right-2 top-2 text-muted-foreground hover:text-foreground"
              onClick={() => {
                setOpen(false);
                setCreated(null);
              }}
              aria-label={t.common?.close ?? "Close"}
            >
              <X />
            </Button>
            <header className="border-b border-border p-5 pb-3">
              <h2
                id="lc-channel-create-title"
                className="font-mondwest text-display text-base tracking-wider"
              >
                {copy?.createTitle ?? "注册新通道"}
              </h2>
            </header>
            <div className="grid gap-4 overflow-y-auto overscroll-contain p-4 sm:p-5">
              {!created ? (
                <>
                  <div className="grid gap-2">
                    <Label htmlFor="ch-name">{copy?.name ?? "名称"}</Label>
                    <Input
                      id="ch-name"
                      autoFocus
                      placeholder={copy?.nameHint ?? "例如 wechat-monitor"}
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") void handleCreate();
                      }}
                    />
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="ch-archetype">
                      {copy?.archetype ?? "类型"}
                    </Label>
                    <Select
                      id="ch-archetype"
                      value={archetype}
                      onValueChange={setArchetype}
                    >
                      {ARCHETYPES.map((a) => (
                        <SelectOption key={a} value={a}>
                          {a}
                        </SelectOption>
                      ))}
                    </Select>
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="ch-uplink">
                      {copy?.uplink ?? "上行等级"}
                    </Label>
                    <Select
                      id="ch-uplink"
                      value={uplink}
                      onValueChange={setUplink}
                    >
                      {UPLINK_LEVELS.map((u) => (
                        <SelectOption key={u.value} value={u.value}>
                          {u.label}
                        </SelectOption>
                      ))}
                    </Select>
                  </div>
                </>
              ) : (
                <div className="grid gap-3">
                  <Card>
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm">
                        ingest_url
                      </CardTitle>
                    </CardHeader>
                    <CardContent className="flex items-center gap-2">
                      <code className="flex-1 break-all font-mono-ui text-xs">
                        {created.ingest_url}
                      </code>
                      <Button
                        ghost
                        size="icon"
                        onClick={() =>
                          void navigator.clipboard?.writeText(created.ingest_url)
                        }
                        aria-label={copy?.copy ?? "Copy"}
                        title={copy?.copy ?? "Copy"}
                      >
                        <Copy />
                      </Button>
                    </CardContent>
                  </Card>
                  <Card>
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm">secret</CardTitle>
                    </CardHeader>
                    <CardContent className="flex items-center gap-2">
                      <code className="flex-1 break-all font-mono-ui text-xs">
                        {created.secret}
                      </code>
                      <Button
                        ghost
                        size="icon"
                        onClick={() =>
                          void navigator.clipboard?.writeText(created.secret)
                        }
                        aria-label={copy?.copy ?? "Copy"}
                        title={copy?.copy ?? "Copy"}
                      >
                        <Copy />
                      </Button>
                    </CardContent>
                  </Card>
                  <p className="text-xs text-warning">
                    {copy?.secretWarn ??
                      "secret 仅显示一次，请立即保存。"}
                  </p>
                </div>
              )}
              <div className="flex flex-col-reverse gap-2 pt-1 sm:flex-row sm:justify-end">
                <Button
                  ghost
                  size="sm"
                  className="w-full sm:w-auto"
                  onClick={() => {
                    setOpen(false);
                    setCreated(null);
                  }}
                >
                  {created
                    ? (t.common?.close ?? "Close")
                    : (t.common?.cancel ?? "Cancel")}
                </Button>
                {!created && (
                  <Button
                    size="sm"
                    className="w-full uppercase sm:w-auto"
                    onClick={() => void handleCreate()}
                    disabled={saving || !name.trim()}
                    prefix={saving ? <Spinner /> : undefined}
                  >
                    {saving
                      ? (t.common?.creating ?? "Creating…")
                      : (t.common?.create ?? "Create")}
                  </Button>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
