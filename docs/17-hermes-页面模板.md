# hermes Dashboard 页面模板（用于 LifeCore 页面统一）

> 调研范围：`/media/data_old/ChenXi/LifeCore/hermes/web/src/`。所有代码片段均为 hermes 真实代码复制，未做改动。
> 调研方式：只读 `pages/*.tsx`、`App.tsx`、`components/*.tsx`、`contexts/*.ts(x)`、`hooks/*.ts`、`lib/utils.ts`、`lib/api-error.ts`、`lib/dashboard-modal-shell.ts`。

---

## 1. 页面基础骨架

hermes dashboard 的页面根渲染结构是两层：

- 外壳层（`App.tsx` + `PageHeaderProvider.tsx`）已经固定为 **左 sidebar + 顶部 page header（title / afterTitle / end）+ 内容区**。
- 页面本身只负责：状态、数据加载、handlers、**返回一段 flex 容器**，容器由 `<main>` 接管 scroll。

每个页面必须遵守的模板：

```tsx
// 文件: web/src/pages/XxxPage.tsx
import { useEffect, useLayoutEffect, useState, useCallback } from "react";
import { Button } from "@nous-research/ui/ui/components/button";
import { Card, CardContent } from "@nous-research/ui/ui/components/card";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { api } from "@/lib/api";
import { usePageHeader } from "@/contexts/usePageHeader";
import { errorMessage } from "@/lib/api-error";

export default function XxxPage() {
  // 1. state
  const [data, setData] = useState<Data[]>([]);
  const [loading, setLoading] = useState(true);
  const { toast, showToast } = useToast();
  const { setEnd } = usePageHeader();

  // 2. data fetching
  const load = useCallback(() => {
    setLoading(true);
    api.getXxx()
      .then(setData)
      .catch((e) => showToast(`Could not load: ${errorMessage(e)}`, "error"))
      .finally(() => setLoading(false));
  }, [showToast]);
  useEffect(() => { load(); }, [load]);

  // 3. handlers
  const handleSomething = async () => { /* ... */ };

  // 4. page-header 工具按钮（可选）
  useLayoutEffect(() => {
    setEnd(
      <Button className="uppercase" size="sm" onClick={handleCreate}>
        Create
      </Button>,
    );
    return () => setEnd(null); // 卸载时清掉，避免泄漏到下一个页面
  }, [setEnd]);

  // 5. loading
  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Spinner className="text-2xl text-primary" />
      </div>
    );
  }

  // 6. render：外层永远是 `flex flex-col gap-{N}`，内层用 Card
  return (
    <div className="flex flex-col gap-6">
      <Toast toast={toast} />
      <Card>
        <CardContent>...</CardContent>
      </Card>
    </div>
  );
}
```

关键约束（**从 AGENTS.md / 真实代码汇总**）：

- **页面顶部不放自己的 `<h1>`**，title 由 `PageHeaderProvider` 自动从 path + `useI18n` 解析。如需改标题或加按钮，用 `usePageHeader()` 提供的 `setTitle / setAfterTitle / setEnd`，并写 `useLayoutEffect` 在 `return () => setEnd(null)` 清理（见 `ChannelsPage.tsx:285-300`、`PairingPage.tsx:109-125`、`CronPage.tsx:849-866`）。
- **根容器**用 `flex flex-col gap-6`（最常用，ChannelsPage/CronPage/PairingPage/SessionsPage 都是）或 `gap-4`（ConfigPage/ModelsPage/DocsPage）。
- **`min-w-0 flex-1 min-h-0`**：任何 flex/grid 子项要可压缩时必备；列表行 `<CardContent className="flex items-start gap-4 py-4">` + 内部 `<div className="flex-1 min-w-0">` + 操作按钮 `<div className="flex items-center gap-1 shrink-0">`，全部摘自 CronPage 1111-1257 与 PairingPage 175-215。
- **永远在根 JSX 顶部放 `<Toast toast={toast} />`**：所有页面统一模式（PairingPage、CronPage、ChannelsPage、FilesPage）。
- **错误信息**：用 `errorMessage(err)`（`lib/api-error.ts:102`），禁用 `String(e)` / `Error: ${e}`。
- **加载状态**：除整页 spinner（`py-24`）外，子区块加载用 `Spinner className="text-2xl text-primary"` 或更小尺寸；卡片加载用 `<Spinner className="text-xl text-primary" />`。

---

## 2. 5 类典型页面真实例子

### 2.1 列表+操作型 — `CronPage.tsx`（参考文件）

> **它解决什么业务问题**：维护一组 cron 任务，每条记录是一行可暂停/触发/编辑/删除的操作卡片，支持新建/编辑模态框、profile 过滤、错误重试。

**关键模式注解**（行号取自真实文件）：

- 顶部工具栏按钮：`useLayoutEffect + setEnd(<Button/>)` 注入 page header（CronPage.tsx:849-866），卸载 `setEnd(null)`。
- 加载：`useState<boolean>(true)` + `api.getCronJobs().then(setJobs).catch(setJobsLoadError).finally(setLoading(false))`（CronPage.tsx:627-657 + 678-687）。
- 持久错误：`useState<string | null>(null) jobsLoadError` + `<LoadErrorNotice what={...} detail={...} onRetry={load} />`（CronPage.tsx:625, 885-891）。`LoadErrorNotice` 组件位于 `components/LoadErrorNotice.tsx`，专门替代"toast + 3 秒消失"，让 retry 按钮永远可达。
- 列表渲染：`items.map(...) → <Card><CardContent className="flex items-start gap-4 py-4"><div className="flex-1 min-w-0">主内容</div><div className="flex items-center gap-1 shrink-0">操作图标按钮组</div></CardContent></Card>`（CronPage.tsx:1127-1256）。
- 操作按钮组：`<Button ghost size="icon">` + lucide icon + `title` + `aria-label`（CronPage.tsx:1206-1253）。
- 删除确认： `useConfirmDelete({ onDelete: useCallback(...) })` + `<DeleteConfirmDialog>` （CronPage.tsx:828-847 + 910-923）。
- 创建/编辑模态框：状态 `createModalOpen / editJob` + `useModalBehavior({ open, onClose })` 绑定 ref + 手动 modal（`fixed inset-0 z-[100] flex items-center justify-center bg-background/85 p-4`）（CronPage.tsx:584-587、926-997）。
- 表单组件：`CronJobFormFields`（接受 `form` + `onChange` props，纯受控），父组件只管 state。

**最小骨架（精简真实代码）**：

```tsx
// /media/data_old/ChenXi/LifeCore/hermes/web/src/pages/CronPage.tsx
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Clock, Pause, Pencil, Play, Trash2, X, Zap } from "lucide-react";
import { Badge } from "@nous-research/ui/ui/components/badge";
import { Button } from "@nous-research/ui/ui/components/button";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { H2 } from "@nous-research/ui/ui/components/typography/h2";
import { api } from "@/lib/api";
import { DeleteConfirmDialog } from "@/components/DeleteConfirmDialog";
import { useConfirmDelete } from "@nous-research/ui/hooks/use-confirm-delete";
import { useModalBehavior } from "@/hooks/useModalBehavior";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { Card, CardContent } from "@nous-research/ui/ui/components/card";
import { usePageHeader } from "@/contexts/usePageHeader";
import { LoadErrorNotice } from "@/components/LoadErrorNotice";
import { cn, themedBody } from "@/lib/utils";
import { errorMessage } from "@/lib/api-error";

export default function CronPage() {
  const [jobs, setJobs] = useState<CronJob[]>([]);
  const [loading, setLoading] = useState(true);
  const [jobsLoadError, setJobsLoadError] = useState<string | null>(null);
  const { toast, showToast } = useToast();
  const { t } = useI18n();
  const { setEnd } = usePageHeader();

  const jobsRequestGenerationRef = useRef(0);

  const loadJobs = useCallback((profile: string) => {
    const generation = ++jobsRequestGenerationRef.current;
    api.getCronJobs(profile).then((next) => {
      if (jobsRequestGenerationRef.current !== generation) return;
      setJobs(next);
      setJobsLoadError(null);
    }).catch((e) => {
      if (jobsRequestGenerationRef.current !== generation) return;
      setJobsLoadError(errorMessage(e));
    }).finally(() => {
      if (jobsRequestGenerationRef.current === generation) setLoading(false);
    });
  }, []);

  useEffect(() => { loadJobs(selectedProfile); }, [loadJobs, selectedProfile]);

  const jobDelete = useConfirmDelete({
    onDelete: useCallback(async (key: string) => {
      const { profile, id } = splitJobKey(key);
      try {
        await api.deleteCronJob(id, profile);
        showToast(`Delete ✓`, "success");
        loadJobs(selectedProfile);
      } catch (e) { showToast(errorMessage(e), "error"); throw e; }
    }, [loadJobs, selectedProfile, showToast]),
  });

  useLayoutEffect(() => {
    setEnd(<Button className="uppercase" size="sm" onClick={() => setCreateModalOpen(true)}>
      {t.common.create}
    </Button>);
    return () => setEnd(null);
  }, [setEnd, t.common.create]);

  if (loading) return <div className="flex items-center justify-center py-24">
    <Spinner className="text-2xl text-primary" />
  </div>;

  return (
    <div className="flex flex-col gap-6">
      <Toast toast={toast} />
      {jobsLoadError && (
        <LoadErrorNotice what={t.cron.loadWhat} detail={jobsLoadError}
          onRetry={() => loadJobs(selectedProfile)} />
      )}

      <div className="flex flex-col gap-3">
        <H2 className="flex items-center gap-2 text-muted-foreground">
          <Clock className="h-4 w-4" /> Jobs ({jobs.length})
        </H2>

        {jobs.map((job) => (
          <Card key={job.id}>
            <CardContent className="flex items-start gap-4 py-4">
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className="font-medium text-sm truncate">{job.name}</span>
                  <Badge tone={STATUS_TONE[state]}>{state}</Badge>
                </div>
                {/* ... */}
              </div>
              <div className="flex items-center gap-1 shrink-0">
                <Button ghost size="icon" onClick={() => handlePauseResume(job)}>
                  {paused ? <Play /> : <Pause />}
                </Button>
                <Button ghost size="icon" onClick={() => handleTrigger(job)}><Zap /></Button>
                <Button ghost size="icon" onClick={() => openEdit(job)}><Pencil /></Button>
                <Button ghost destructive size="icon"
                  onClick={() => jobDelete.requestDelete(job.id)}>
                  <Trash2 />
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <DeleteConfirmDialog
        open={jobDelete.isOpen}
        onCancel={jobDelete.cancel}
        onConfirm={jobDelete.confirm}
        title="Delete job?"
        description="Cannot be undone."
        loading={jobDelete.isDeleting}
      />
    </div>
  );
}
```

### 2.2 流式对话型 — `ChatPage.tsx`（**特殊**：嵌 PTY，不适合做 React 流式模板）

> **AGENTS.md 警告**：`web/src/pages/ChatPage.tsx` 嵌入的是真实 `hermes --tui`（xterm + WebSocket `/api/pty`），**不要**用 React 重写 transcript/composer。

**它解决什么业务问题**：让用户在 dashboard 里跑交互式 TUI agent 会话。

但因为 LifeCore 的 ChatPage 几乎肯定是 **React 流式对话**（不嵌 PTY），这里把 hermes 真实存在的两个流式片段贴出来供参考（来自 ChatPage.tsx 的 `ws.onmessage`）：

**真实 WebSocket 流式片段**（hermes `web/src/pages/ChatPage.tsx:1323-1384`）：

```tsx
const decoder = new TextDecoder();
const sanitizer = new PtyResumeSanitizer();

ws.onmessage = (ev) => {
  if (typeof ev.data === "string") {
    const resumeId = parseResumeControlMessage(ev.data);
    if (resumeId) { beginResumeReplay(); return; }
  }
  const text = typeof ev.data === "string"
    ? ev.data
    : decoder.decode(new Uint8Array(ev.data as ArrayBuffer), { stream: true });

  const rendered = effectiveResume ? sanitizer.next(text) : text;
  const followScroll = shouldFollowPtyOutput(effectiveResume, stickToBottomRef.current)
    ? () => termRef.current?.scrollToBottom()
    : undefined;
  term.write(rendered, followScroll);
};
```

**用 fetch + reader 的 SSE/分块流式片段**（hermes 没有完整例子，**这是建议模板**，**不是**真实代码——见末尾注释）。LifeCore 的 ChatPage 用 fetch reader 时建议这样写：

```tsx
// 这是 LifeCore 推荐写法（hermes 当前 ChatPage 是 PTY，本节无可直接复制的 React fetch reader 页面；
//  最接近的 fetch+reader 实例在 ChatPage.tsx:1365，仅一行 decoder.decode，不是完整 fetch stream。
//  请按本模板落地，遵循 hermes 错误处理与 toast 模式。）
const resp = await fetch("/api/lifecore/chat", {
  method: "POST",
  headers: { "Content-Type": "application/json", "X-Session-Token": token },
  body: JSON.stringify({ session_id, prompt }),
});
if (!resp.ok || !resp.body) throw apiErrorFromResponse(resp.status, "", "/api/lifecore/chat");

const reader = resp.body.getReader();
const decoder = new TextDecoder();
let acc = "";
try {
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    acc += decoder.decode(value, { stream: true });
    // 解析 SSE/data 帧：按 '\n\n' 切，每段 'data: {...}\n\n'
    let idx;
    while ((idx = acc.indexOf("\n\n")) !== -1) {
      const frame = acc.slice(0, idx); acc = acc.slice(idx + 2);
      const line = frame.split("\n").find(l => l.startsWith("data:"));
      if (!line || line.endsWith("data: [DONE]")) continue;
      try { onChunk(JSON.parse(line.slice(5).trim())); } catch { /* ignore */ }
    }
  }
  // flush
  if (acc.length) {
    const tail = acc.split("\n").find(l => l.startsWith("data:"))?.slice(5).trim();
    if (tail && tail !== "[DONE]") try { onChunk(JSON.parse(tail)); } catch {}
  }
} finally {
  reader.releaseLock();
}
```

**关键模式**（取自真实 hermes 错误处理 + 日志流，LogsPage 模式）：
- 用 `errorMessage(err)` 抛错；
- 用 `useToast()` + `<Toast toast={toast} />`；
- 加载/连接态用 `<Spinner>`；
- 容器参考 CronPage：`flex flex-col gap-6` + 内嵌 Card。

### 2.3 决策/反馈型 — `PairingPage.tsx`（参考文件）

> **它解决什么业务问题**：每条 pending 请求是一个卡片，列出用户信息 + 操作按钮（Approve / Revoke）；分类两个分组（pending、approved），各自独立列表。

**关键模式注解**：

- 双列表布局：`两个 <div className="flex flex-col gap-3">`，每个上方一段 `<H2 variant="sm" className="flex items-center gap-2 text-muted-foreground">{icon} 标题 ({count})</H2>`（PairingPage.tsx:158-167, 219-227）。
- 空状态：`<Card><CardContent className="py-8 text-center text-sm text-muted-foreground">No pending requests</CardContent></Card>`（PairingPage.tsx:167-173）。
- 行内 approve：`approve` 操作放在 `<CardContent>` 右上的 `<div className="flex items-center gap-1 shrink-0">` 里，按钮 `uppercase` + `<Spinner>` 替 prefix（PairingPage.tsx:195-211）。
- 删除确认：`useConfirmDelete({ onDelete: useCallback(...) })` + `<DeleteConfirmDialog>`（PairingPage.tsx:87-106 + 143-155）。`useConfirmDelete` 返回 `{ isOpen, pendingId, isDeleting, requestDelete, cancel, confirm }`。
- toast 反馈：`showToast("Approved: ...", "success")` / `showToast("Could not approve: ...", "error")`（PairingPage.tsx:64, 67）。
- 批量操作（clear pending）放在 page header end 槽：`useLayoutEffect(() => setEnd(<Button>...</Button>), [...])`（PairingPage.tsx:109-125）。

**真实源码**（节选 `web/src/pages/PairingPage.tsx:139-216`）：

```tsx
return (
  <div className="flex flex-col gap-6">
    <Toast toast={toast} />

    <DeleteConfirmDialog
      open={userRevoke.isOpen}
      onCancel={userRevoke.cancel}
      onConfirm={userRevoke.confirm}
      title="Revoke access"
      description={pendingRevokeUser
        ? `"${getUserLabel(pendingRevokeUser)}" will lose access. This cannot be undone.`
        : "This user will lose access. This cannot be undone."}
      confirmLabel="Revoke"
      loading={userRevoke.isDeleting}
    />

    {/* Pending requests */}
    <div className="flex flex-col gap-3">
      <H2 variant="sm" className="flex items-center gap-2 text-muted-foreground">
        <Users className="h-4 w-4" />
        Pending requests ({pending.length})
      </H2>

      {pending.length === 0 && (
        <Card>
          <CardContent className="py-8 text-center text-sm text-muted-foreground">
            No pending pairing requests
          </CardContent>
        </Card>
      )}

      {pending.map((user) => (
        <Card key={getUserKey(user)}>
          <CardContent className="flex items-start gap-4 py-4">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1">
                <Badge tone="outline">{user.platform}</Badge>
                <span className="font-medium text-sm truncate">{getUserLabel(user)}</span>
              </div>
              <div className="flex items-center gap-4 text-xs text-muted-foreground">
                <span className="truncate">{user.user_id}</span>
                {typeof user.age_minutes === "number" && <span>{user.age_minutes}m ago</span>}
              </div>
            </div>
            <div className="flex items-center gap-1 shrink-0">
              <Button size="sm" className="uppercase"
                onClick={() => handleApprove(user)}
                disabled={approving === getUserKey(user) || !user.request_id}
                prefix={approving === getUserKey(user) ? <Spinner /> : <Check className="h-4 w-4" />}>
                Approve
              </Button>
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  </div>
);
```

### 2.4 配置型 — `EnvPage.tsx`（参考文件）+ `ConfigPage.tsx`（次参考）

> **它解决什么业务问题**：管理多个 provider 分组下的 API key/secret 等环境变量。每个 key 一行可编辑（save/clear/reveal），按 provider 前缀分组。

**关键模式注解**（EnvPage 是典型分组卡片）：

- 分组规则：在模块顶层定义 `PROVIDER_GROUPS: { prefix, name, priority }[]`（EnvPage.tsx:49-71），`getProviderGroup(key)` 按前缀分桶（EnvPage.tsx:73-78）。
- 加载：mount 时 `Promise.all([api.getEnv(), api.getProviders(), ...])`，按组排序，状态：`envVars`、`providers`、`saving`、`revealed` 等多个（EnvPage.tsx:235-300）。
- 行内编辑：`EnvVarRow` 子组件（EnvPage.tsx:103-330），状态 lifted up；"edit"、"save"、"cancel"、"reveal"、"clear" 五种交互用图标按钮 + `uppercase` 文字。
- 删除确认：`useConfirmDelete({ onDelete: useCallback(...) })`（EnvPage.tsx:721-770），`DeleteConfirmDialog` 用于批量删除。
- page header 工具按钮：`useLayoutEffect(() => setEnd(<Plus>), [...])` 用于"add key"（EnvPage.tsx:340-365 区域）。
- 分组展示模板：

```tsx
{/* 来自 EnvPage.tsx 的真实分组渲染（节选 line 470-520）*/}
<section className="flex flex-col gap-3">
  <header className="flex items-center gap-2 border-b border-border pb-2">
    <Zap className="h-4 w-4 text-primary" />
    <h3 className="font-mondwest text-display text-base tracking-wider">{group.name}</h3>
    <Badge tone="outline" className="ml-auto text-xs">{count} keys</Badge>
  </header>
  <div className="grid gap-1.5">
    {group.entries.map(([key, info]) => (
      <EnvVarRow key={key} varKey={key} info={info} {...} />
    ))}
  </div>
</section>
```

**配置文件 `ConfigPage.tsx` 的补充模式**（适用于 SettingsPage 的"高级分组/搜索/分类"场景）：

- 三种状态：`config | null`、`schema | null`、`categoryOrder`、`defaults`、`activeCategory`（ConfigPage.tsx:106-127）。
- 加载：mount 时 4 个 `api.*` 并发（`getConfig / getSchema / getDefaults / getConfigRaw / getStatus`），各自独立 try/catch（ConfigPage.tsx:165-204）。
- 搜索：`searchQuery` + `useMemo` 计算 `searchMatchedFields`（ConfigPage.tsx:249-269），搜索态用一个 `<Card>` 覆盖 active category。
- 左侧 sidebar + 右侧 active card 的布局：`<div className="flex flex-col sm:flex-row gap-4">` + `<aside className="sm:w-56 sm:shrink-0">` + `<div className="flex-1 min-w-0">`（ConfigPage.tsx:551-660）。
- 重置作用域：`setConfirmReset(true)` + `<ConfirmDialog destructive>`（ConfigPage.tsx:309-339, 662-677）。

### 2.5 详情/时间线型 — `SessionsPage.tsx`（参考文件）

> **它解决什么业务问题**：列表 + 点击展开行内详情（消息流/工具调用），支持搜索、分页、批量选择、详情内的 sub-fetch。

**关键模式注解**（最关键的"展开行内详情"骨架（行 466-762 + 816-2125））：

- 主表 `SessionsPage` 顶层只管 sessions 列表与筛选；`SessionRow` 子组件管自己行的展开状态。
- 行状态 lift 方案：父组件持 `expandedId: string | null`，行 `<SessionRow isExpanded={expandedId === s.id} onToggle={() => setExpandedId(prev => prev === s.id ? null : s.id)} />`（SessionsPage.tsx:2097-2115）。
- 行内展开懒加载消息：`SessionRow` 内 `useState<SessionMessage[] | null>(null)`，`useEffect(() => { if (!isExpanded || messages !== null) return; api.getSessionMessages(...).then(setMessages); return () => { cancelled = true; }; }, [isExpanded, session.id, session.profile, messages])`（SessionsPage.tsx:479-501）。**取消标志 `cancelled` 写在 cleanup 里**——这是 hermes 一致模式。
- 行内时间线展开区域：

```tsx
{isExpanded && (
  <div className="min-w-0 border-t border-border bg-background/50 p-4">
    {messages === null && !error && (
      <div className="flex items-center justify-center py-8">
        <Spinner className="text-xl text-primary" />
      </div>
    )}
    {error && <p className="text-sm text-destructive py-4 text-center">{error}</p>}
    {messages && messages.length === 0 && (
      <p className="text-sm text-muted-foreground py-4 text-center">{t.sessions.noMessages}</p>
    )}
    {messages && messages.length > 0 && (
      <MessageList messages={messages} highlight={searchQuery} />
    )}
  </div>
)}
```
（节选 SessionsPage.tsx:740-759）

- 整页空状态：`flex flex-col items-center justify-center py-16 text-muted-foreground` + 大 icon（半透明 `opacity-40`）+ 文字 + 可选次级 CTA（SessionsPage.tsx:2078-2092）。
- 分页：`PAGE_SIZE = 20` + `<SessionsPagination page total onPageChange />`（SessionsPage.tsx:766-814 + 2118-2124）。
- 详情/时间线型常用容器 className：
  - 列表区：`<div className="flex min-w-0 flex-col gap-1.5">`（每条 `SessionRow` 一个 div 容器，行号 2095-2115）。
  - 行骨架：`<div className="flex min-w-0 items-start gap-3 border border-border p-3 sm:items-center sm:justify-between">`。
  - 展开区：`<div className="min-w-0 border-t border-border bg-background/50 p-4">`。
- 批量选择 banner：`showList && selectedIds.size > 0` 时渲染固定颜色 banner 替换 header 区（SessionsPage.tsx:2011-2074）。

---

## 3. 路由注册模式

**真实代码块**（hermes `web/src/App.tsx:80-225`）：

```tsx
// Route pages are lazy-loaded so the initial dashboard shell does not pay for
// every admin surface (and heavy deps like xterm) up front.
const ConfigPage = lazy(() => import("@/pages/ConfigPage"));
const DocsPage   = lazy(() => import("@/pages/DocsPage"));
const EnvPage    = lazy(() => import("@/pages/EnvPage"));
const FilesPage  = lazy(() => import("@/pages/FilesPage"));
const SessionsPage = lazy(() => import("@/pages/SessionsPage"));
const LogsPage   = lazy(() => import("@/pages/LogsPage"));
const AnalyticsPage = lazy(() => import("@/pages/AnalyticsPage"));
const ModelsPage = lazy(() => import("@/pages/ModelsPage"));
const CronPage   = lazy(() => import("@/pages/CronPage"));
const ProfilesPage = lazy(() => import("@/pages/ProfilesPage"));
const ProfileBuilderPage = lazy(() => import("@/pages/ProfileBuilderPage"));
const SkillsPage = lazy(() => import("@/pages/SkillsPage"));
const PluginsPage = lazy(() => import("@/pages/PluginsPage"));
const McpPage    = lazy(() => import("@/pages/McpPage"));
const PairingPage = lazy(() => import("@/pages/PairingPage"));
const ChannelsPage = lazy(() => import("@/pages/ChannelsPage"));
const WebhooksPage = lazy(() => import("@/pages/WebhooksPage"));
const SystemPage = lazy(() => import("@/pages/SystemPage"));
const ChatPage   = lazy(() => import("@/pages/ChatPage"));

const BUILTIN_ROUTES_CORE: Record<string, ComponentType> = {
  "/": RootRedirect,
  "/sessions": SessionsPage,
  "/files": FilesPage,
  "/analytics": AnalyticsPage,
  "/models": ModelsPage,
  "/logs": LogsPage,
  "/cron": CronPage,
  "/skills": SkillsPage,
  "/plugins": PluginsPage,
  "/mcp": McpPage,
  "/pairing": PairingPage,
  "/channels": ChannelsPage,
  "/webhooks": WebhooksPage,
  "/system": SystemPage,
  "/profiles": ProfilesPage,
  "/profiles/new": ProfileBuilderPage,
  "/config": ConfigPage,
  "/env": EnvPage,
  "/docs": DocsPage,
};

const BUILTIN_NAV_REST: NavItem[] = [
  { path: "/sessions", labelKey: "sessions", label: "Sessions", icon: MessageSquare },
  { path: "/files", label: "Files", icon: FolderOpen },
  { path: "/analytics", labelKey: "analytics", label: "Analytics", icon: BarChart3 },
  { path: "/models", labelKey: "models", label: "Models", icon: Cpu },
  { path: "/logs", labelKey: "logs", label: "Logs", icon: FileText },
  { path: "/cron", labelKey: "cron", label: "Cron", icon: Clock },
  { path: "/skills", labelKey: "skills", label: "Skills", icon: Package },
  { path: "/plugins", labelKey: "plugins", label: "Plugins", icon: Puzzle },
  { path: "/mcp", label: "MCP", icon: Plug },
  { path: "/channels", label: "Channels", icon: Radio },
  { path: "/webhooks", label: "Webhooks", icon: Webhook },
  { path: "/pairing", label: "Pairing", icon: ShieldCheck },
  { path: "/profiles", labelKey: "profiles", label: "Profiles", icon: Users },
  { path: "/config", labelKey: "config", label: "Config", icon: Settings },
  { path: "/env", labelKey: "keys", label: "Keys", icon: KeyRound },
  { path: "/system", label: "System", icon: Wrench },
  { path: "/docs", labelKey: "documentation", label: "Documentation", icon: BookOpen },
];
```

**加新路由的步骤**（按 hermes 真实顺序）：

1. 在 `web/src/pages/` 放 `LifecoreXxxPage.tsx`，默认 export 组件。
2. `App.tsx` 顶部加一行：`const LifecoreXxxPage = lazy(() => import("@/pages/LifecoreXxxPage"));`（行 80-98 区域）。
3. `BUILTIN_ROUTES_CORE` 字典加一行：`"/lifecore/xxx": LifecoreXxxPage,`（行 157-177 区域，路径前导 `/`）。
4. `BUILTIN_NAV_REST` 数组加一行 `{ path: "/lifecore/xxx", label: "Xxx", icon: SomeLucideIcon }`（行 187-225 区域，**图标直接从 `lucide-react` import**）。
5. （可选）路由根目录 layout 已经做了 `px-3 sm:px-6`、`pt-2 sm:pt-4 lg:pt-6`、`pb-[calc(2rem+env(safe-area-inset-bottom,0px))] lg:pb-8`（App.tsx:759-777），页面不必自管 padding。
6. （可选）`resolvePageTitle` 在 `lib/resolve-page-title.ts` 维护，需要新标题时加上 i18n key。
7. `BUILTIN_NAV_REST` 用的是单行 `{ path, label, icon }` 形态；`labelKey` 可选，存在 i18n 时更佳（拉 `t.app.nav[labelKey]`）。

**关于 protected route 模式**：hermes **没有**单独的 protected route wrapper。整 dashboard 由 Python 后端 `hermes_cli/web_server.py` 注入 `window.__HERMES_SESSION_TOKEN__`，所有 `/api/*` 自动带 token；路由层没有 React 端守卫。

---

## 4. 交互模式代码片段

### 4.1 表单提交（form + handleSubmit + loading + error toast）

**真实来源**：`PairingPage.tsx:55-71` + `CronPage.tsx:709-735`（handleCreate）+ `ChannelsPage.tsx:187-235`（handleSave）。

```tsx
// 真实代码节选 web/src/pages/PairingPage.tsx:55-71
const handleApprove = async (user: PairingUser) => {
  if (!user.request_id) {
    showToast("Missing pairing request", "error");
    return;
  }
  const key = getUserKey(user);
  setApproving(key);
  try {
    await api.approvePairing(user.platform, user.request_id);
    showToast(`Approved: "${getUserLabel(user)}"`, "success");
    loadPairing();
  } catch (e) {
    showToast(`Could not approve the pairing request: ${errorMessage(e)}`, "error");
  } finally {
    setApproving(null);
  }
};
```

### 4.2 流式响应（fetch + reader + 帧解析）

**注**：hermes `pages/` 里没有完整 React fetch reader 流式对话模板。ChatPage 走的是 WebSocket（PTY）。最接近的分块读取是 `ChatPage.tsx:1365` `decoder.decode(new Uint8Array(ev.data as ArrayBuffer), { stream: true })`。

**实际遇到的真实片段**（hermes `ChatPage.tsx:1361-1382`）：

```tsx
const text = typeof ev.data === "string"
  ? ev.data
  : decoder.decode(new Uint8Array(ev.data as ArrayBuffer), { stream: true });
const rendered = effectiveResume ? sanitizer.next(text) : text;
term.write(rendered, followScroll);
```

LifeCore 的 ChatPage 推荐写法（沿用 hermes 错误处理 + Toast + Spinner 模式，**fetch+reader 部分按 Hermes 错误处理包装**）：

```tsx
const handleStream = async (prompt: string, onChunk: (delta: string) => void) => {
  setBusy(true);
  try {
    const resp = await fetch("/api/lifecore/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Session-Token": token },
      body: JSON.stringify({ prompt }),
    });
    if (!resp.ok || !resp.body) throw apiErrorFromResponse(resp.status, "", "/api/lifecore/chat");
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf("\n\n")) >= 0) {
        const frame = buf.slice(0, idx); buf = buf.slice(idx + 2);
        const line = frame.split("\n").find(l => l.startsWith("data:"));
        if (!line) continue;
        const payload = line.slice(5).trim();
        if (payload === "[DONE]") { reader.cancel(); break; }
        try { onChunk(JSON.parse(payload).delta ?? ""); } catch { /* ignore */ }
      }
    }
  } catch (e) {
    showToast(`Stream failed: ${errorMessage(e)}`, "error");
  } finally {
    setBusy(false);
  }
};
```

### 4.3 轮询（setInterval + cleanup）

**真实来源**：`LogsPage.tsx:150-158` + `ChannelsPage.tsx:228-229, 277`（`setTimeout(load, 4000)`）。

```tsx
// 真实代码节选 web/src/pages/LogsPage.tsx:150-158
useEffect(() => {
  if (!autoRefresh) return;
  const interval = setInterval(fetchLogs, 5000);
  return () => clearInterval(interval);
}, [autoRefresh, fetchLogs]);
```

配合 loading 态与顶部刷新按钮：

```tsx
// 真实节选 web/src/pages/LogsPage.tsx:92-149
useLayoutEffect(() => {
  setAfterTitle(
    <span className="flex items-center gap-1.5">
      <Badge tone="secondary" className="text-xs">
        {formatFilterLabel(file)} · {formatFilterLabel(level)} · {formatFilterLabel(component)}
      </Badge>
      <Button type="button" ghost size="icon"
        className="text-muted-foreground hover:text-foreground"
        onClick={fetchLogs} disabled={loading}
        aria-label={t.common.refresh}>
        {loading ? <Spinner /> : <RefreshCw />}
      </Button>
    </span>,
  );
  setEnd(
    <div className="flex w-full min-w-0 flex-wrap items-center justify-start gap-2 sm:justify-end sm:gap-3">
      <div className="flex items-center gap-2">
        <Label htmlFor="logs-auto-refresh" className="text-xs cursor-pointer">{t.logs.autoRefresh}</Label>
        <Switch checked={autoRefresh} onCheckedChange={setAutoRefresh} id="logs-auto-refresh" />
        {autoRefresh && <Badge tone="success" className="text-xs"><span className="mr-1 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-current" />{t.common.live}</Badge>}
      </div>
    </div>,
  );
  return () => { setAfterTitle(null); setEnd(null); };
}, [autoRefresh, component, file, level, loading, setAfterTitle, setEnd, t.common.live, t.common.refresh, t.logs.autoRefresh, fetchLogs]);
```

### 4.4 确认对话框（useConfirmDelete / ConfirmDialog）

**真实来源**：`PairingPage.tsx:87-106 + 143-155`（删除）+ `ChannelsPage.tsx:361-534`（自定义模态框）+ `App.tsx:1080-1098`（ConfirmDialog 直用）。

```tsx
// 真实代码 web/src/pages/PairingPage.tsx:87-106
const userRevoke = useConfirmDelete({
  onDelete: useCallback(
    async (key: string) => {
      const { platform, user_id } = splitUserKey(key);
      const user = approved.find((u) => getUserKey(u) === key);
      try {
        await api.revokePairing(platform, user_id);
        showToast(`Revoked: "${user ? getUserLabel(user) : user_id}"`, "success");
        loadPairing();
      } catch (e) {
        showToast(`Could not revoke access: ${errorMessage(e)}`, "error");
        throw e; // 让 useConfirmDelete 退出 loading
      }
    },
    [approved, loadPairing, showToast],
  ),
});

// 真实代码 web/src/pages/PairingPage.tsx:143-155
<DeleteConfirmDialog
  open={userRevoke.isOpen}
  onCancel={userRevoke.cancel}
  onConfirm={userRevoke.confirm}
  title="Revoke access"
  description={
    pendingRevokeUser
      ? `"${getUserLabel(pendingRevokeUser)}" will lose access. This cannot be undone.`
      : "This user will lose access. This cannot be undone."
  }
  confirmLabel="Revoke"
  loading={userRevoke.isDeleting}
/>
```

`DeleteConfirmDialog` 组件（`web/src/components/DeleteConfirmDialog.tsx`，全文）：

```tsx
import { ConfirmDialog } from "@nous-research/ui/ui/components/confirm-dialog";
import { useI18n } from "@/i18n";

export function DeleteConfirmDialog({ cancelLabel, confirmLabel, description, loading,
  onCancel, onConfirm, open, title }: DeleteConfirmDialogProps) {
  const { t } = useI18n();
  return (
    <ConfirmDialog open={open} onCancel={onCancel} onConfirm={onConfirm}
      title={title} description={description} loading={loading}
      destructive confirmLabel={confirmLabel ?? t.common.delete}
      cancelLabel={cancelLabel ?? t.common.cancel} />
  );
}
```

**手工模态框**（`ChannelsPage.tsx:362-411` 头 + 513-531 底）：

```tsx
{editing && (
  <div
    ref={editModalRef}
    className={cn(
      "fixed inset-0 z-[100] flex min-h-dvh items-start justify-center overflow-y-auto bg-background/85 px-4",
      "pb-[calc(1rem+env(safe-area-inset-bottom))] pt-[calc(1rem+env(safe-area-inset-top))]",
      "sm:items-center sm:p-4",
    )}
    onClick={(e) => e.target === e.currentTarget && setEditing(null)}
    role="dialog" aria-modal="true" aria-labelledby="channel-config-title"
  >
    <div className={cn(themedBody, "relative flex max-h-[calc(100dvh-2rem)] w-full max-w-lg flex-col border border-border bg-card shadow-2xl sm:max-h-[90dvh]")}>
      <Button ghost size="icon" onClick={() => setEditing(null)}
        className="absolute right-2 top-2 text-muted-foreground hover:text-foreground"
        aria-label="Close"><X /></Button>

      <header className="p-5 pb-3 border-b border-border">
        <h2 id="channel-config-title" className="font-mondwest text-display text-base tracking-wider">
          {editing.id === "telegram" ? "Use your own Telegram bot" : `Configure ${editing.name}`}
        </h2>
      </header>

      <div className="grid gap-4 overflow-y-auto overscroll-contain p-4 sm:p-5">
        {/* fields */}
        <div className="flex flex-col-reverse gap-2 pt-1 sm:flex-row sm:justify-end">
          <Button ghost size="sm" className="w-full sm:w-auto" onClick={() => setEditing(null)}>Cancel</Button>
          <Button className="w-full uppercase sm:w-auto" size="sm" onClick={handleSave}
            disabled={saving} prefix={saving ? <Spinner /> : undefined}>
            {saving ? "Saving…" : "Save & enable"}
          </Button>
        </div>
      </div>
    </div>
  </div>
)}
```

`editModalRef = useModalBehavior({ open: editing !== null, onClose: closeEdit });`（ChannelsPage.tsx:152）——自动绑 Escape + body 锁 + 焦点恢复。

### 4.5 Toast / Banner

**真实来源**：`useToast`（`@nous-research/ui/hooks/use-toast`，外部包；hermes 仅用）；`Toast` 组件（`@nous-research/ui/ui/components/toast`）。模式三件套：

```tsx
// 1. 顶部 hook
const { toast, showToast } = useToast();

// 2. JSX 顶部渲染占位
<Toast toast={toast} />

// 3. 调用
showToast("Saved ✓", "success");
showToast(`Could not load: ${errorMessage(e)}`, "error");
```

**Banner / 通知条**（ChannelsPage.tsx:319-339 — restart 提示，sticky 顶部卡片）：

```tsx
{restartNeeded && (
  <Card className="border-warning/50">
    <CardContent className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-center gap-2 text-sm">
        <AlertTriangle className="h-4 w-4 shrink-0 text-warning" />
        <span>Changes are saved. Restart the gateway for them to take effect.</span>
      </div>
      <Button size="sm" className="uppercase shrink-0" onClick={handleRestart}
        disabled={restarting} prefix={restarting ? <Spinner /> : <RotateCw className="h-4 w-4" />}>
        {restarting ? "Restarting…" : "Restart now"}
      </Button>
    </CardContent>
  </Card>
)}
```

**整页 Error Card + Retry**（用 `LoadErrorNotice`，CronPage.tsx:885-891）：

```tsx
{jobsLoadError && (
  <LoadErrorNotice what={t.cron.loadWhat} detail={jobsLoadError}
    onRetry={() => loadJobs(selectedProfile)} />
)}
```

### 4.6 空状态（EmptyState）

hermes **没有**统一的 `<EmptyState>` 组件；全部手写卡片。三个变体：

**A. 列表为空（pairing / cron）**（PairingPage.tsx:167-173）：

```tsx
{pending.length === 0 && (
  <Card>
    <CardContent className="py-8 text-center text-sm text-muted-foreground">
      No pending pairing requests
    </CardContent>
  </Card>
)}
```

**B. 列表为空 + 行动号召（cron）**（CronPage.tsx:1091-1109）：

```tsx
{jobs.length === 0 && !jobsLoadError && (
  <Card>
    <CardContent className="flex flex-col items-center gap-3 py-8 text-center text-sm text-muted-foreground">
      <span>{t.cron.noJobs}</span>
      <Button className="uppercase" size="sm" onClick={() => setCreateModalOpen(true)}>
        {t.common.create}
      </Button>
    </CardContent>
  </Card>
)}
```

**C. 整页空 + 大图标**（SessionsPage.tsx:2078-2092）：

```tsx
<div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
  <Clock className="h-8 w-8 mb-3 opacity-40" />
  <p className="text-sm font-medium">...</p>
  {!search && sessionCategory === "chats" && selectedSources === null && (
    <p className="text-xs mt-1 text-text-tertiary">{t.sessions.startConversation}</p>
  )}
</div>
```

---

## 5. 样式约定

### 5.1 页面布局

- **内容容器 padding** 已在 `App.tsx` 的 route outlet wrapper 设好（`px-3 sm:px-6`、`pt-2 sm:pt-4 lg:pt-6`、`pb-[calc(2rem+env(safe-area-inset-bottom,0px))] lg:pb-8`，App.tsx:759-777），**页面不需要再加 max-width 或外层 padding**。
- **页面根容器**：`flex flex-col gap-6`（Channels/Cron/Pairing/Sessions，最常见）或 `gap-4`（Config/Models/Docs/Logs）。
- **`min-w-0`**：每个 flex/grid 子项必备；用于 `<div className="flex-1 min-w-0">` 包裹长文本（标题、消息、行内容）。

### 5.2 卡片间距与列表间距

- 列表项间距：`<div className="grid gap-3">` 包裹 `items.map(Card)`（ChannelsPage.tsx:537） 或 `<div className="flex flex-col gap-3">` 包裹 `items.map(...)`（CronPage.tsx:1064、PairingPage.tsx:158）。
- Card 内部：`<CardContent className="flex items-start gap-4 py-4">`（Cron/Pairing/Files 都是这条样式）。
- 行内分组间距：`gap-1.5`（密集）、`gap-2`（按钮组）、`gap-3`（卡片之间）、`gap-4`（卡片内大块）、`gap-6`（页面 section 之间）。

### 5.3 sticky 头部

- **Page header**（title + 工具栏）由 `PageHeaderProvider` 自动渲染并 `box-border border-b border-current/20`，已经 sticky 到 viewport 顶部；不需要页面再做。
- **页面内 section 头**不 sticky，除非像 ConfigPage 那样 `<aside className="sm:w-56 sm:shrink-0"><div className="sm:sticky sm:top-4">…</div></aside>`（ConfigPage.tsx:552-602）。

### 5.4 加载骨架 / 动画

- **整页加载**：`py-24` 容器 + `<Spinner className="text-2xl text-primary" />`（ChannelsPage.tsx:306-312、PairingPage.tsx:127-133、CronPage.tsx:868-874）。
- **行内 / 卡片内**：`py-8` + `<Spinner className="text-xl text-primary" />`（SessionsPage.tsx:742-746）。
- **按钮前缀**：`prefix={busy ? <Spinner /> : <Icon className="h-4 w-4" />}`——所有 hermes 按钮遵循此模式（ChannelsPage.tsx:294-296、PairingPage.tsx:200-207）。
- **无独立 Skeleton 组件**；空白态 + Spinner 是统一模式。
- **动效细节**：`animate-pulse` 用于"live"指示点（LogsPage.tsx:125）、`transition-opacity duration-300` 用于折叠面板（PairingPage.tsx 区域）、`group-hover:opacity-5` 用绝对定位伪高亮（App.tsx:914-917）。

### 5.5 字体 / 排版

- **layout shell（sidebar、page title、section header、按钮 chrome）**：`font-mondwest text-display`，全大写 + `tracking-[0.12em]`。
- **正文 / 列表文本 / 行内容**：`font-mondwest normal-case`，i18n `t.app.nav.*` 等用户可见英文用此 class（utils.ts:8-15）。
- **代码 / 等宽**：`font-courier`（ChannelsPage.tsx:347, 581）或 `font-mono-ui`（LogsPage.tsx:238, CronPage.tsx:1177）。**`font-courier` 用于显示外部命令、token 字面量；`font-mono-ui` 用于显示 UI/状态值**。
- **图标**：`lucide-react`，默认尺寸 `h-4 w-4`（行内）/ `h-3.5 w-3.5`（密集）/ `h-5 w-5`（section title）。

### 5.6 颜色语义

- `text-success`：成功（connected、enabled、scheduled、approved）。
- `text-warning`：暂停 / 待重启 / 配置缺失。
- `text-destructive`：失败、删除、错误信息。
- `text-muted-foreground`：次要文本（描述、辅助说明、时间戳）。
- `bg-destructive/10` + `border-destructive/40`：错误 banner 底色（ChannelsPage.tsx:951-953、PairingPage 错误区）。
- `bg-warning/30`：搜索高亮 `<mark>`（SessionsPage.tsx:183）。
- `bg-background/45 / 50`：展开区域内背景（SessionsPage.tsx:981、CronPage 区域）。

---

## 6. 速查：LifeCore 新页面 → hermes 模板对照表

| LifeCore 新页面 | 用 hermes 哪个模板 | 关键参考文件 / 行号 |
|---|---|---|
| **ChatPage**（流式对话） | ChatPage 模板（**特殊**：hermes 嵌 PTY；LifeCore 应做 React 流式，套用 §4.2 推荐 fetch+reader + §1 骨架） | `web/src/pages/ChatPage.tsx:192-200`（entry skeleton）+ §4.2 推荐写法 |
| **NotifyPage**（决策/反馈型） | PairingPage 模板 | `web/src/pages/PairingPage.tsx:139-216`（双分组 + Approve 按钮 + 空状态） |
| **ChannelsPage**（列表+操作型） | CronPage 模板 | `web/src/pages/CronPage.tsx:1111-1256`（行卡 + 图标按钮组 + useConfirmDelete） |
| **JobsPage**（列表+操作型，配置详情/状态） | CronPage + EnvPage 混合 | `web/src/pages/CronPage.tsx:1111-1256`（列表+操作）+ `web/src/pages/EnvPage.tsx:103-330`（EnvVarRow 内嵌字段） |
| **SettingsPage**（配置型，**多分组卡片**） | EnvPage 模板 | `web/src/pages/EnvPage.tsx:49-78, 470-520`（PROVIDER_GROUPS 分桶 + 分组 section）；高级/搜索需求加 `ConfigPage.tsx:165-204, 551-660` |
| **TodayPage**（新增，决策/反馈型 + 详情时间线） | PairingPage + SessionsPage 混合 | `web/src/pages/PairingPage.tsx:139-216`（决策卡）+ `web/src/pages/SessionsPage.tsx:466-762`（行内展开 + 时间线） |

> **判断依据**：每行右侧"它解决什么业务问题"已经写在 §2 各小节标题下，可以直接照搬。

---

## 7. 不要踩的坑（来自 AGENTS.md + 真实代码模式）

1. **不要重新实现 ChatPage 的 React transcript/composer**——hermes ChatPage 是嵌 PTY，但 LifeCore 的 ChatPage 如果是 React 流式，按 §4.2 模板写。
2. **不要把 `useToast` / `useConfirmDelete` / `ConfirmDialog` 自己再封装一遍**——直接复用 `@nous-research/ui` 提供的版本。LifeCore 如果用同一套 UI 包可直接 `import`；否则把 hermes 这三个 hook 移植过去（hook 本身 50-100 行，纯 React + Toast 渲染）。
3. **不要把 `<h1>` 写在页面里**——title 由 `PageHeaderProvider` 自动解析，工具按钮走 `setEnd / setAfterTitle / setTitle`。
4. **不要忘记 `setEnd(null)`**——`useLayoutEffect` 必须返回 cleanup，否则切页时按钮会"漏"到下一个页面。
5. **不要用 `String(e)` 或 `Error: ${e}` 拼 toast**——永远 `errorMessage(err)`（lib/api-error.ts:102）。
6. **不要把路由根 layout 的 padding/width 重复加**——`App.tsx:759-777` 已经管了 `px-3 sm:px-6` + top/bottom padding。
7. **不要给 `setInterval` 写 cleanup**——`LogsPage.tsx:150-158` 是模板。
8. **不要给行内 fetch 漏 `cancelled`**——`SessionRow.tsx:487-501` 的 cleanup 模式是 hermes 一致写法。
9. **不要为"删除"自己手写 modal**——`useConfirmDelete + <DeleteConfirmDialog>` 是统一接口。
10. **不要忘记"loading + error + empty"三态**——CronPage / PairingPage / SessionsPage 都演示了。

---

## 8. 一句话总结

**hermes dashboard 页面 = `<Toast/>` + `<LoadErrorNotice/>`（按需）+ `<Card><CardContent className="flex items-start gap-4 py-4">…</CardContent></Card>` + 操作按钮走 page header（`setEnd`）或行内图标（`ghost size="icon"`）+ 删除走 `useConfirmDelete/DeleteConfirmDialog` + 流式走 fetch reader + 轮询走 `setInterval/cleanup` + i18n 用 `useI18n()`**。