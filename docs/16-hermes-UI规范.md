# hermes Dashboard UI 规范（用于 LifeCore 页面统一）

> 调研对象：`/media/data_old/ChenXi/LifeCore/hermes/web/`（SPA 入口：`web/src/main.tsx`）
> 调研时间：基于仓库当前 main 分支（`@nous-research/ui@0.18.2`，React 19.2，Tailwind 4.3）
> 注意：所有 UI 组件 **全部来自 npm 包 `@nous-research/ui`**（Nous DS / "LENS" 设计系统），**不是 hermes 自写的**。Hermes 只是消费者。LifeCore 自己写页面时，要么复用这个 npm 包，要么按下面这套 token/写法 1:1 复刻。

---

## 0. 最关键的事实（先看这条）

Hermes Dashboard **没有** `components/ui/button.tsx` 这种自维护的 shadcn-style 组件库。所有 Button / Card / Input / Select / Dialog / Toast / Switch / Checkbox / ListItem / Segmented / Stats / BottomSheet / Badge / Spinner / Label / H2 / Typography 全部从 `@nous-research/ui` 导入，例如：

```tsx
// web/src/pages/WebhooksPage.tsx:12-27
import { Badge } from "@nous-research/ui/ui/components/badge";
import { Button } from "@nous-research/ui/ui/components/button";
import { Select, SelectOption } from "@nous-research/ui/ui/components/select";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { H2 } from "@nous-research/ui/ui/components/typography/h2";
import { Card, CardContent } from "@nous-research/ui/ui/components/card";
import { Input } from "@nous-research/ui/ui/components/input";
import { Label } from "@nous-research/ui/ui/components/label";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { ConfirmDialog } from "@nous-research/ui/ui/components/confirm-dialog";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import { useConfirmDelete } from "@nous-research/ui/hooks/use-confirm-delete";
import { useBelowBreakpoint } from "@nous-research/ui/hooks/use-below-breakpoint";
```

`web/src/components/` 下所有 `.tsx` 都是 hermes 的 **业务组件**（不是基础 UI 库），例如 `ConfirmDialog`（基于 DS 的 ConfirmDialog 二次封装）、`SkillEditorDialog`、`AutoField`、`ModelPickerDialog`、`ThemeSwitcher`、`LanguageSwitcher` 等。**`ConfirmDialog` 例外**：本地封装的版本会自己用 `createPortal` 渲染（`components/ConfirmDialog.tsx`）。

CSS 入口在 `web/src/index.css`，它做三件事：

1. `@import '@nous-research/ui/styles/fonts.css';` + `@import '@nous-research/ui/styles/globals.css';` —— 把 DS 的全局样式和字体注册进来。
2. `@source '../node_modules/@nous-research/ui/dist';` —— 把 DS 的工具类加入 Tailwind v4 JIT 扫描，否则 DS 的 utility 类会被 purge 掉。
3. 自己定义 `:root` 上的 `--background/-base/-alpha`、`--midground/-base/-alpha`、`--foreground/-base/-alpha` 三层色（这是 LENS_0 Hermes Teal 默认主题的值），再加 shadcn 兼容别名（`--color-card`、`--color-destructive`、`--color-success`、`--color-warning`、`--color-border`、`--color-input`、`--color-ring`、`--color-popover` …）。**注意：`--foreground` 在 LENS_0 是 alpha 0**，所以 `text-foreground` 会失效——hermes 故意把它 remap 到 `--midground`（见 index.css:157）。

字体路径：`--theme-font-sans` / `--theme-font-mono` / `--theme-font-display`，由 `themes/context.tsx` 的 `applyTheme` 在切换主题时写到 `:root`。

---

## 1. 全局 CSS 变量（hermes 自己写在 index.css 的部分）

来源：`web/src/index.css:50-89` 和 `web/src/index.css:154-187`。

### 1.1 DS 三层色（默认 Hermes Teal LENS_0 主题）

```css
:root {
  --foreground:        color-mix(in srgb, #ffffff 0%,  transparent);   /* alpha 0，单独不可见 */
  --foreground-base:   #ffffff;
  --foreground-alpha:  0;

  --midground:         color-mix(in srgb, #ffe6cb 100%, transparent);   /* 主文字 / 强调 */
  --midground-base:    #ffe6cb;                                         /* 默认 cream */
  --midground-alpha:   1;

  --background:        color-mix(in srgb, #041c1c 100%, transparent);   /* 深画布 */
  --background-base:   #041c1c;
  --background-alpha:  1;

  --series-input-token:  #ffe6cb;
  --series-output-token: #34d399;
}
```

> 写法约定：**hex + 单独 alpha 字段**，通过 `color-mix(in srgb, hex <pct>%, transparent)` 渲染色。这样主题切换时只改 hex 与 alpha 两字段。

### 1.2 Typography / Layout

```css
:root {
  --theme-font-sans:     system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  --theme-font-mono:     ui-monospace, "SF Mono", "Cascadia Mono", Menlo, Consolas, monospace;
  --theme-font-display:  var(--theme-font-sans);
  --theme-base-size:     15px;
  --theme-line-height:   1.55;
  --theme-letter-spacing: 0;

  --radius:        0.5rem;
  --theme-radius:  0.5rem;
  --theme-spacing-mul: 1;     /* compact=0.85, comfortable=1, spacious=1.2 */
  --theme-density: comfortable;
}

@theme inline {
  --spacing: calc(0.25rem * var(--theme-spacing-mul, 1));
  --font-sans: var(--theme-font-sans);
  --font-mono: var(--theme-font-mono);
}
```

> `--theme-spacing-mul` 直接缩放 Tailwind v4 全部 `p-N` / `gap-N` / `space-*` 工具类。Density 切到 spacious（mul=1.2）时整页会跟着放大。

### 1.3 Shadcn 兼容 token（hermes 在 index.css:154-187 自己重新映射）

```css
@theme inline {
  --color-foreground:           var(--midground);                      /* shadcn 的 text-foreground 走 midground */
  --color-card:                 color-mix(in srgb, var(--midground-base) 4%, var(--background-base));
  --color-card-foreground:      var(--midground);
  --color-primary:              var(--midground);
  --color-primary-foreground:   var(--background-base);
  --color-secondary:            color-mix(in srgb, var(--midground-base) 6%, var(--background-base));
  --color-secondary-foreground: var(--midground);
  --color-muted:                color-mix(in srgb, var(--midground-base) 8%, var(--background-base));
  --color-muted-foreground:     var(--color-text-secondary);           /* 来自 DS */
  --color-accent:               color-mix(in srgb, var(--midground-base) 10%, var(--background-base));
  --color-accent-foreground:    var(--midground);
  --color-destructive:          #fb2c36;
  --color-destructive-foreground: #ffffff;
  --color-success:              #4ade80;
  --color-warning:              #ffbd38;
  --color-border:               color-mix(in srgb, var(--midground-base) 15%, transparent);
  --color-input:                color-mix(in srgb, var(--midground-base) 15%, transparent);
  --color-ring:                 var(--midground);
  --color-popover:              color-mix(in srgb, var(--midground-base) 4%, var(--background-base));
  --color-popover-foreground:   var(--midground);

  --radius-sm: calc(var(--theme-radius) - 4px);
  --radius-md: calc(var(--theme-radius) - 2px);
  --radius-lg: var(--theme-radius);
  --radius-xl: calc(var(--theme-radius) + 4px);
}
```

### 1.4 LifeCore 写页面要用的"色票 / 字号 / 间距 / 圆角"汇总

| 用途 | class / token |
| --- | --- |
| 画布背景 | `bg-background-base` |
| 卡片背景 | `bg-card` 或 `bg-background-base/80`（页面级玻璃） |
| 弹出框面板 | `bg-card` + `border border-border`（**必须不透明**，见 `lib/dashboard-modal-shell.ts:18`） |
| 模态遮罩 | `bg-background/85`，固定 `z-[100]`，并 `createPortal(..., document.body)` |
| 主文字 | `text-midground` 或 `text-foreground`（在 LENS_0 都被 remap 到 cream） |
| 次级文字 | `text-muted-foreground`、`text-text-secondary`、`text-text-tertiary`（3 级递降） |
| 危险文字 | `text-destructive` |
| 警告文字 | `text-warning` |
| 成功文字 | `text-success` |
| 主描边 | `border border-border` 或 `border border-current/20` |
| 危险描边 | `border border-destructive/40` |
| 警告描边 | `border border-warning/50` |
| 主题色按钮文字高亮 | `text-primary`（= midground） |
| 圆角 | `--radius-sm` / `--radius-md` / `--radius-lg`（默认 0.5rem） |
| 间距 | Tailwind v4 全部 `p-N` `gap-N` `space-N`，乘 `--theme-spacing-mul`（默认 1） |
| 字体大小 | `text-xs` `text-sm`（默认 15px 在 :root） |
| 字体（sans） | `font-sans`（= `--theme-font-sans`） |
| 字体（mono） | `font-mono-ui` 或 `font-mono`（= `--theme-font-mono`） |
| 主题 chrome 字体 | `font-mondwest` + `text-display`（专有 DS 字体） |

### 1.5 主题切换机制

- 入口：`web/src/main.tsx:8` 把 `<ThemeProvider>` 嵌在 `<I18nProvider>` 里面，包裹整个 App。
- 数据源：`BUILTIN_THEMES` 在 `web/src/themes/presets.ts` 注册了 8 个内置主题（`default`、`default-large`、`nous-blue`、`midnight`、`ember`、`mono`、`cyberpunk`、`rose`）。每个主题都自带 `palette` / `typography` / `layout` / `layoutVariant` / `componentStyles` / `colorOverrides` / `seriesColors` / `terminalBackground`。
- 应用：`themes/context.tsx:applyTheme()` 在主题切换时清掉旧 inline CSS vars、写入新 inline vars，并（如果有）注入 Google Fonts stylesheet + 注入 `customCSS` 到 `<style id="hermes-theme-custom-css">`。还会设置 `document.documentElement.dataset.layoutVariant` 为 `standard | cockpit | tiled`。
- 持久化：localStorage `hermes-dashboard-theme` + 后端 `/api/dashboard/themes`；字体覆盖 localStorage `hermes-dashboard-font` + 后端 `/api/dashboard/prefs/font`。
- 字体可独立于主题切换：用户在 font picker 里选 Inter，主题切到 cyberpunk 时 Inter 仍然生效（`applyTheme` 末尾会重写一遍 `_ACTIVE_FONT_OVERRIDE`，见 `themes/context.tsx:404`）。

### 1.6 字体体系（hermes 实际在用的）

- Sans：system stack 默认；主题可换成 `"Inter"`、`"IBM Plex Sans"`、`"Work Sans"`、`"Atkinson Hyperlegible"`、`"DM Sans"` 等。Spectral / Fraunces / Source Serif 4 提供 serif；JetBrains Mono / IBM Plex Mono / Space Mono 提供 mono。完整 catalog 见 `web/src/themes/fonts.ts:56-144`。
- Mono：所有 `<code>` / `kbd` / `pre` / `samp` / `.font-mono` / `.font-mono-ui` 自动走 `var(--theme-font-mono)`（`index.css:111-113`）。`.font-mono-ui` 是 hermes 自己加的工具类（`index.css:230-232`），用于密集数据格（mono 但不带衬线）。
- 主题字体：所有大写 chrome（侧边栏、页头 nav、Dialog 标题、Button uppercase 变体）走 `font-mondwest`（专有 DS 字体）+ `text-display` 工具类。
- 终端（TUI）：xterm.js 直接用 `JetBrains Mono`（`index.css:23-43` 注册的 woff2），由 `ChatPage` 的 `fontFamily` 选项传给 xterm。
- 字号：root `15px` / `1.55` line-height / `0` letter-spacing；`default-large` 主题升到 `18px` / `1.65`。

---

## 2. 全局样式工具类（`index.css`）

来源：`web/src/index.css:190-254`。

```css
/* toast 进出动画 */
@keyframes toast-in  { from { opacity: 0; transform: translateX(16px); } to { opacity: 1; transform: translateX(0); } }
@keyframes toast-out { from { opacity: 1; transform: translateX(0);  } to { opacity: 0; transform: translateX(16px); } }

/* 通用淡入 + 弹出 */
@keyframes fade-in   { from { opacity: 0; } to { opacity: 1; } }
@keyframes dialog-in { from { opacity: 0; transform: translateY(4px) scale(0.98); } to { opacity: 1; transform: translateY(0) scale(1); } }

/* 隐藏滚动条 */
.scrollbar-none {
  -ms-overflow-style: none;
  scrollbar-width: none;
}
.scrollbar-none::-webkit-scrollbar { display: none; }

/* 徽章 grain overlay */
.grain { position: relative; }
.grain::after {
  content: ''; position: absolute; inset: 0; opacity: 0.12; pointer-events: none;
  background: repeating-conic-gradient(currentColor 0% 25%, #0000 0% 50%) 0 0 / 2px 2px;
}

/* RTL（阿拉伯语） — 在 <html dir="rtl"> 时自动翻转逻辑间距 */
html[dir="rtl"] { direction: rtl; }
```

> Hermes dashboard 整体框架还有：`html / body / #root` 都锁 100dvh（`index.css:93-142`）；`<small>` 实际渲染 1.0625rem、`<code>` 0.875rem（`index.css:146-147`）—— 别在 React 里硬塞 `text-xs`，sm/code 已经够小。

---

## 3. 核心组件 props + 用法（**真实从 hermes pages 复制**）

下面所有片段都来自 `web/src/pages/*` 或 `web/src/components/*`，**可以直接搬到 LifeCore 页面**。

### 3.1 Button（最常用）

来源：`@nous-research/ui/ui/components/button`（DS 原生）。props 在 hermes 实际使用中：

| Prop | 类型 | 备注 |
| --- | --- | --- |
| `variant` | `"default"`（隐含，可不写） | 大多数用法都不写 variant，走默认实心 |
| `ghost` | boolean | 透明背景 hover 时显色 |
| `outlined` | boolean | 边框风格（次级按钮） |
| `destructive` | boolean | 红色危险按钮（ghost + destructive 用来做删除小图标） |
| `size` | `"xs" \| "sm" \| "icon"` 或省略 | 省略时为默认尺寸；icon 用于方块图标按钮 |
| `prefix` | ReactNode | 按钮内文字前的图标（注意：这是 `<Spinner>` 显示 loading 的标准方式） |
| `type` | `"button" \| "submit"` | form 里要显式写 |
| `onClick` | function | |
| `disabled` | boolean | |
| `className` | string | 常用 `"uppercase"` 让按钮走 chrome 风格（小写大写字距） |
| `aria-label` / `title` | string | icon button 必填 |

**真实用法（`web/src/pages/WebhooksPage.tsx:446-455`）：**
```tsx
<Button
  className="uppercase"
  size="sm"
  onClick={handleCreate}
  disabled={creating}
  prefix={creating ? <Spinner /> : undefined}
>
  {creating ? "Creating…" : "Create"}
</Button>
```

**Icon 按钮（`web/src/pages/SkillsPage.tsx:797-806`）：**
```tsx
<Button
  ghost
  size="icon"
  className="shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100 hover:text-foreground"
  title="Edit SKILL.md"
  aria-label={`Edit ${skill.name}`}
  onClick={onEdit}
>
  <Pencil />
</Button>
```

**危险图标按钮（`web/src/pages/WebhooksPage.tsx:596-605`）：**
```tsx
<Button
  ghost
  destructive
  size="icon"
  title="Delete"
  aria-label="Delete"
  onClick={() => webhookDelete.requestDelete(sub.name)}
>
  <Trash2 />
</Button>
```

### 3.2 Card / CardContent / CardHeader / CardTitle

来源：`@nous-research/ui/ui/components/card`。

| 子组件 | 作用 |
| --- | --- |
| `Card` | 容器（默认带边框、圆角、`bg-card` 玻璃） |
| `CardContent` | 内容 padding 区，最常用 |
| `CardHeader` | 标题区（自带 padding + border-b） |
| `CardTitle` | 标题文字 |

**真实用法（`web/src/pages/SkillsPage.tsx:487-505`）：**
```tsx
<Card className="rounded-none">
  <CardHeader className="py-3 px-4">
    <div className="flex items-center justify-between">
      <CardTitle className="text-sm flex items-center gap-2">
        <Search className="h-4 w-4" />
        {t.skills.title}
      </CardTitle>
      <Badge tone="secondary" className="text-xs">
        {t.skills.resultCount.replace("{count}", String(n))}
      </Badge>
    </div>
  </CardHeader>
  <CardContent className="px-4 pb-4">
    {searchMatchedSkills.length === 0 ? (
      <p className="text-sm text-muted-foreground text-center py-8">
        {t.skills.noSkillsMatch}
      </p>
    ) : (
      <div className="grid gap-1">
        {searchMatchedSkills.map((skill) => <SkillRow ... />)}
      </div>
    )}
  </CardContent>
</Card>
```

**普通 Card（最常见，`web/src/pages/WebhooksPage.tsx:545-608`）：**
```tsx
<Card key={sub.name}>
  <CardContent className="flex items-start gap-4 py-4">
    <div className="flex-1 min-w-0">
      <div className="flex items-center gap-2 mb-1 flex-wrap">
        <span className="font-medium text-sm truncate">{sub.name}</span>
        <Badge tone="outline">{sub.deliver}</Badge>
        {!sub.enabled && <Badge tone="warning">disabled</Badge>}
      </div>
      {/* … */}
    </div>
    <div className="flex items-center gap-1 shrink-0">
      <Button ghost size="sm" className="uppercase"
              disabled={togglingName === sub.name}
              onClick={() => handleToggleEnabled(sub.name, !sub.enabled)}>
        {sub.enabled ? "Disable" : "Enable"}
      </Button>
      <Button ghost destructive size="icon" onClick={() => webhookDelete.requestDelete(sub.name)}>
        <Trash2 />
      </Button>
    </div>
  </CardContent>
</Card>
```

### 3.3 Input

来源：`@nous-research/ui/ui/components/input`。props：`id`, `value`, `onChange`, `placeholder`, `autoFocus`, `disabled`, `aria-invalid`, `onKeyDown` …

**真实用法（`web/src/pages/WebhooksPage.tsx:373-379`）：**
```tsx
<div className="grid gap-2">
  <Label htmlFor="webhook-name">Name</Label>
  <Input
    id="webhook-name"
    autoFocus
    placeholder="e.g. github-push"
    value={name}
    onChange={(e) => setName(e.target.value)}
  />
</div>
```

**带 Enter 提交（`web/src/pages/ProfilesPage.tsx:845-858`）：**
```tsx
<Input
  id="profile-name"
  autoFocus
  placeholder={t.profiles.namePlaceholder}
  value={newName}
  onChange={(e) => setNewName(e.target.value)}
  onKeyDown={(e) => { if (e.key === "Enter") handleCreate(); }}
  aria-invalid={newName.trim() !== "" && !PROFILE_NAME_RE.test(newName.trim())}
/>
```

### 3.4 Label

来源：`@nous-research/ui/ui/components/label`。**直接当 `<label>` 用**，传 `htmlFor` 关联 input。

```tsx
<Label htmlFor="webhook-description">Description</Label>
<Input id="webhook-description" ... />
```

### 3.5 Select / SelectOption

来源：`@nous-research/ui/ui/components/select`（Radix 下拉）。`value` + `onValueChange`（**不是 `onChange`**），子项必须用 `<SelectOption value="x">label</SelectOption>`。

**真实用法（`web/src/pages/WebhooksPage.tsx:405-418`）：**
```tsx
<div className="grid gap-2">
  <Label htmlFor="webhook-deliver">Deliver to</Label>
  <Select id="webhook-deliver" value={deliver} onValueChange={(v) => setDeliver(v)}>
    <SelectOption value="log">Log</SelectOption>
    <SelectOption value="telegram">Telegram</SelectOption>
    <SelectOption value="discord">Discord</SelectOption>
    <SelectOption value="slack">Slack</SelectOption>
    <SelectOption value="email">Email</SelectOption>
    <SelectOption value="github_comment">GitHub comment</SelectOption>
  </Select>
</div>
```

**选项动态生成（`web/src/pages/CronPage.tsx:173-192`）：**
```tsx
function selectOptions(current, options) {
  const known = new Set(options.map((o) => o.value));
  return [
    ...options.map((o) => <SelectOption key={o.value} value={o.value}>{o.label}</SelectOption>),
    ...(current && !known.has(current)
      ? [<SelectOption key={current} value={current}>{current}</SelectOption>]
      : []),
  ];
}
```

### 3.6 Checkbox

来源：`@nous-research/ui/ui/components/checkbox`（Radix）。**注意：原生 `<input type="checkbox">` 也经常出现**（`web/src/pages/CronPage.tsx:118-122`），但写新页面优先用 DS 版本。

**真实用法（`web/src/pages/SessionsPage.tsx:628-633`）：**
```tsx
<span className="flex shrink-0 items-center pt-0.5">
  <Checkbox
    checked={isSelected}
    onClick={handleSelectClick}
    aria-label={t.sessions.selectSession}
  />
</span>
```

### 3.7 Switch

来源：`@nous-research/ui/ui/components/switch`。`checked` + `onCheckedChange`。

**真实用法（`web/src/pages/SkillsPage.tsx:777-781`）：**
```tsx
<div className="pt-0.5 shrink-0">
  <Switch
    checked={skill.enabled}
    onCheckedChange={onToggle}
    disabled={toggling}
  />
</div>
```

### 3.8 Dialog / DialogContent / DialogHeader / DialogTitle / DialogDescription / DialogFooter

来源：`@nous-research/ui/ui/components/dialog`（Radix）。

**真实用法（`web/src/pages/SkillsPage.tsx:704-760`）：**
```tsx
import {
  Dialog, DialogContent, DialogDescription,
  DialogHeader, DialogTitle, DialogFooter,
} from "@nous-research/ui/ui/components/dialog";

<Dialog open={learnOpen} onOpenChange={setLearnOpen}>
  <DialogContent className="max-w-lg">
    <DialogHeader>
      <DialogTitle>Learn a skill</DialogTitle>
      <DialogDescription>
        Point Hermes at anything and it will distill a reusable skill...
      </DialogDescription>
    </DialogHeader>
    <div className="grid gap-3 py-2">
      <div className="grid gap-1.5">
        <label className="text-xs font-medium text-muted-foreground">Local file or directory</label>
        <Input placeholder="~/projects/some-sdk" value={learnDir} onChange={(e) => setLearnDir(e.target.value)} />
      </div>
      {/* ... */}
    </div>
    <div className="flex justify-end gap-2 pt-1">
      <Button ghost onClick={() => setLearnOpen(false)}>Cancel</Button>
      <Button onClick={submitLearn} prefix={<Sparkles />} disabled={!canSubmit}>Learn it</Button>
    </div>
  </DialogContent>
</Dialog>
```

**手动 portal 模式（hermes 实际也大量使用，因为要管 `useModalBehavior`，见 `web/src/pages/WebhooksPage.tsx:303-461`）：**
```tsx
{createModalOpen && (
  <div
    ref={createModalRef}                                      // useModalBehavior 返回的 ref
    className="fixed inset-0 z-[100] flex items-center justify-center bg-background/85 p-4"
    onClick={(e) => e.target === e.currentTarget && closeCreateModal()}
    role="dialog"
    aria-modal="true"
    aria-labelledby="create-webhook-title"
  >
    <div className={cn(themedBody, "relative w-full max-w-lg border border-border bg-card shadow-2xl flex flex-col max-h-[90vh] overflow-y-auto")}>
      <Button ghost size="icon" onClick={closeCreateModal}
              className="absolute right-2 top-2 text-muted-foreground hover:text-foreground"
              aria-label="Close">
        <X />
      </Button>
      <header className="p-5 pb-3 border-b border-border">
        <h2 id="create-webhook-title" className="font-mondwest text-display text-base tracking-wider">New subscription</h2>
      </header>
      {/* body */}
    </div>
  </div>
)}
```

> Hermes 的选择是 **Radix Dialog 在简单场景下直接用，手动 createPortal + `useModalBehavior` 在需要嵌套/复合场景下用**。两种风格都符合。

### 3.9 Toast（useToast hook + Toast 渲染器）

来源：hook `@nous-research/ui/hooks/use-toast`，组件 `@nous-research/ui/ui/components/toast`。
**用法铁律**：先 `const { toast, showToast } = useToast();`，在每个页面顶部渲染 `<Toast toast={toast} />`；调用 `showToast(message, "success" | "error")` 触发。

**真实用法（`web/src/pages/WebhooksPage.tsx:68-99`）：**
```tsx
const { toast, showToast } = useToast();

const loadWebhooks = useCallback(() => {
  return api
    .getWebhooks()
    .then(setData)
    .catch(() => showToast("Failed to load webhooks", "error"))
    .finally(() => setLoading(false));
}, [showToast]);

// …

return (
  <div className="flex flex-col gap-6">
    <Toast toast={toast} />
    {/* … */}
  </div>
);
```

**带模板插值（`web/src/pages/SessionsPage.tsx:1396-1402`）：**
```tsx
showToast(
  t.sessions.selectedSessionsDeleted.replace("{count}", String(resp.deleted)),
  "success",
);
```

### 3.10 ConfirmDialog（DS 版 + 本地封装版）

#### 3.10.1 DS 版：直接用 `@nous-research/ui/ui/components/confirm-dialog`

来源：`web/src/App.tsx:63` + `web/src/pages/PluginsPage.tsx:1254-1268`：
```tsx
<ConfirmDialog
  open={confirmRemove}
  onCancel={() => setConfirmRemove(false)}
  onConfirm={() => {
    setConfirmRemove(false);
    void setRuntimeLoading(row.name, async () => {
      await api.removeAgentPlugin(row.name);
      showToast(`${row.name} removed`, "success");
    });
  }}
  title={t.pluginsPage.removeConfirm}
  description={`This will remove the "${row.name}" plugin from your agent.`}
  destructive
  confirmLabel={t.common.delete}
/>
```

#### 3.10.2 本地封装（用得更频繁）

`web/src/components/DeleteConfirmDialog.tsx` 把 DS ConfirmDialog 包了一层（统一默认 label + destructive）。配套 hook `useConfirmDelete` 接管 open/pending 状态：

```tsx
// hook — 来自 @nous-research/ui/hooks/use-confirm-delete
const webhookDelete = useConfirmDelete({
  onDelete: useCallback(async (name: string) => {
    await api.deleteWebhook(name);
    showToast(`Deleted: "${name}"`, "success");
    loadWebhooks();
  }, [loadWebhooks, showToast]),
});

// 渲染 — DeleteConfirmDialog 已经把 destructive=true、confirmLabel=cancelLabel=t.common.cancel 写死
<DeleteConfirmDialog
  open={webhookDelete.isOpen}
  onCancel={webhookDelete.cancel}
  onConfirm={webhookDelete.confirm}
  title="Delete webhook"
  description={`"${pendingName}" — this will permanently remove this webhook subscription.`}
  loading={webhookDelete.isDeleting}
/>

// 触发 — 在删除图标按钮的 onClick 里：
<Button ghost destructive size="icon" onClick={() => webhookDelete.requestDelete(sub.name)}>
  <Trash2 />
</Button>
```

> Hermes **自己** 的 `components/ConfirmDialog.tsx`（不是 DS 的）是个独立的实现：用 `createPortal` 渲染，自己画 AlertTriangle 图标 + footer 按钮组，调用 `Button` 组件做主按钮。它跟 DS 版可互换，**但新页面建议直接用 DS 版 + DeleteConfirmDialog 封装**，避免重复维护。

### 3.11 Badge

来源：`@nous-research/ui/ui/components/badge`。`tone` 枚举（实际使用中出现过）：`"success" | "warning" | "destructive" | "secondary" | "outline"`。

**真实用法（`web/src/pages/WebhooksPage.tsx:549-575`）：**
```tsx
<div className="flex items-center gap-2 mb-1 flex-wrap">
  <span className="font-medium text-sm truncate">{sub.name}</span>
  <Badge tone="outline">{sub.deliver}</Badge>
  {sub.deliver_only && <Badge tone="secondary">deliver only</Badge>}
  {!sub.enabled && <Badge tone="warning">disabled</Badge>}
</div>

{sub.events.length === 0 ? (
  <Badge tone="secondary">(all)</Badge>
) : (
  sub.events.map((evt) => <Badge key={evt} tone="secondary">{evt}</Badge>)
)}
```

### 3.12 Spinner

来源：`@nous-research/ui/ui/components/spinner`。直接当 `<Spinner />` 用。

**真实用法（`web/src/pages/ModelsPage.tsx:1319-1322`）：**
```tsx
{loading && !data && (
  <div className="flex items-center justify-center py-24">
    <Spinner className="text-2xl text-primary" />
  </div>
)}
```

**加载页面骨架（`web/src/App.tsx:112-125`，全站通用）：**
```tsx
function RouteFallback({ label = "Loading…" }: { label?: string }) {
  return (
    <div className="flex min-h-[12rem] flex-1 items-center justify-center" aria-busy="true" aria-live="polite">
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Spinner />
        <span>{label}</span>
      </div>
    </div>
  );
}
```

### 3.13 ListItem

来源：`@nous-research/ui/ui/components/list-item`。`active` + `onClick`，典型用法是侧栏 / 设置页里的"行"。

**真实用法（`web/src/pages/SkillsPage.tsx:458-476`）：**
```tsx
<ListItem
  key={key}
  active={isActive}
  onClick={() => setActiveCategory(isActive ? null : key)}
  className="rounded-none px-2 py-1 text-xs"
>
  <span className="flex-1 truncate">{name}</span>
  <span className={`text-xs tabular-nums ${isActive ? "text-text-secondary" : "text-text-tertiary"}`}>
    {count}
  </span>
</ListItem>
```

**带图标的 ListItem（`web/src/pages/SkillsPage.tsx:813-824`）：**
```tsx
<ListItem
  active={active}
  onClick={onClick}
  className={cn(
    "rounded-none whitespace-nowrap px-2.5 py-1.5",
    "font-mondwest text-[0.7rem] tracking-[0.08em] uppercase",
    active && "bg-foreground/90 text-background hover:text-background",
  )}
>
  <Icon className="h-3.5 w-3.5 shrink-0" />
  <span className="flex-1 truncate">{label}</span>
</ListItem>
```

### 3.14 Segmented / FilterGroup

来源：`@nous-research/ui/ui/components/segmented`。`options: { value, label }[]`，`value` + `onChange`。

**真实用法（`web/src/pages/LogsPage.tsx:168-193`）：**
```tsx
<FilterGroup label={t.logs.file} className={filterGroupClass}>
  <Segmented
    className={segmentedClass}
    value={file}
    onChange={setFile}
    options={toSegmentOptions(FILES)}
  />
</FilterGroup>

<FilterGroup label={t.logs.level} className={filterGroupClass}>
  <Segmented
    className={segmentedClass}
    value={level}
    onChange={setLevel}
    options={toSegmentOptions(LEVELS)}
  />
</FilterGroup>
```

### 3.15 Stats（聚合卡片）

来源：`@nous-research/ui/ui/components/stats`。**只接受 `items: { label: string; value: string }[]`**。

**真实用法（`web/src/pages/AnalyticsPage.tsx:543-575`）：**
```tsx
<Card>
  <CardContent className="py-6">
    <Stats
      items={[
        { label: t.analytics.totalTokens,    value: formatTokens(total) },
        { label: t.analytics.input,         value: formatTokens(inp) },
        { label: t.analytics.output,        value: formatTokens(out) },
        { label: t.analytics.totalSessions, value: `${sess} (~${(sess/days).toFixed(1)}${t.analytics.perDayAvg})` },
        { label: t.analytics.apiCalls,      value: String(apiCalls) },
      ]}
    />
  </CardContent>
</Card>
```

### 3.16 Typography / H2（chrome 文本）

- `Typography`：`@nous-research/ui/ui/components/typography/index`，通用 chrome 字体容器（搭配 `className="font-mondwest text-display tracking-[0.08em] text-xs"` 这种）。
- `H2`：`@nous-research/ui/ui/components/typography/h2`，带 `variant="sm" | "md" | "lg"`。

**真实用法（`web/src/pages/McpPage.tsx:594-600`）：**
```tsx
<H2 variant="sm" className="flex items-center gap-2 text-muted-foreground">
  <Server className="h-4 w-4" />
  Your MCP servers ({servers.length})
</H2>
```

**Typography 在 App.tsx:554 的用法：**
```tsx
<Typography className="font-bold text-[0.95rem] leading-[0.95] tracking-[0.05em] text-midground">
  {t.app.brand}
</Typography>
```

### 3.17 BottomSheet（移动端抽屉）

来源：`@nous-research/ui/ui/components/bottom-sheet`。只在窄屏用，配套 `useBelowBreakpoint`。

**真实用法（`web/src/components/ThemeSwitcher.tsx:92-113`）：**
```tsx
const narrowViewport = useBelowBreakpoint(640);
const useMobileSheet = Boolean(dropUp && narrowViewport);

// …

{useMobileSheet && (
  <BottomSheet
    backdropDismissLabel={t.common.close}
    onClose={close}
    open={open}
    title={sheetTitle}
  >
    <div aria-label={sheetTitle} role="listbox">
      <ThemeSwitcherOptions ... />
      <FontSection ... />
    </div>
  </BottomSheet>
)}
```

### 3.18 CommandBlock / CopyButton（终端命令块）

来源：`@nous-research/ui/ui/components/command-block`。

**真实用法（`web/src/pages/PluginsPage.tsx:1238-1243` + `:85-92`）：**
```tsx
<CommandBlock
  label={t.pluginsPage.authRequiredHint}
  code={row.auth_command}
/>

// SetupCommandBlock — 内部用 CopyButton：
function SetupCommandBlock({ code, label }: { code: string; label: string }) {
  return (
    <div className="...">
      <span>{label}</span>
      <CopyButton text={code} />
    </div>
  );
}
```

### 3.19 useBelowBreakpoint（响应式 hook）

来源：`@nous-research/ui/hooks/use-below-breakpoint`。直接返回 boolean。

```tsx
const isMobile = useBelowBreakpoint(1024);
const narrowViewport = useBelowBreakpoint(640);
```

---

## 4. fetch / 状态 / i18n 模式

### 4.1 API 调用（`fetchJSON` + `api` 对象 + `errorMessage`）

Hermes 唯一的 API 包装在 `web/src/lib/api.ts`：
- `fetchJSON<T>(url, init?, options?)`：自动注入 `X-Hermes-Session-Token` header + `credentials: 'include'`，处理 401（gated mode 跳 /login，loopback mode 重载页面拿新 token），非 2xx 抛 `ApiError`。**这是写页面时唯一推荐用的 fetch。**
- `api.*`：所有 endpoint 的强类型方法封装。`api.getStatus() / api.getSessions() / api.createWebhook(...) / api.getProfiles() / api.getActiveProfile() / api.setTheme(name) / api.getFontPref() / api.setFontPref(id) / api.checkHermesUpdate(false) / api.restartGateway() / ...`。
- `errorMessage(e)`：`web/src/lib/api-error.ts` 导出，把 `ApiError`/`Error`/string 都解析成可展示的中文/英文短句。

**真实用法（`web/src/pages/WebhooksPage.tsx:94-100`）：**
```tsx
const loadWebhooks = useCallback(() => {
  return api
    .getWebhooks()
    .then(setData)
    .catch(() => showToast("Failed to load webhooks", "error"))
    .finally(() => setLoading(false));
}, [showToast]);
```

**POST 带 body（`web/src/pages/WebhooksPage.tsx:198-205`）：**
```tsx
const res = await api.createWebhook({
  name: name.trim(),
  description: description.trim() || undefined,
  events: eventsList.length ? eventsList : undefined,
  deliver,
  deliver_only: deliverOnly,
  prompt: prompt.trim() || undefined,
});
```

**profile-scoped 请求（透明追加 `?profile=`，无需手动做）：** `api.getSessions()` / `api.getConfig()` / `api.getStatus()` 等都会自动带上当前 `ProfileProvider` 选中的 profile，**写新页面不用手动处理**（机制见 `web/src/lib/api.ts:75-109` 的 `PROFILE_SCOPED_PREFIXES`）。

### 4.2 全局状态（Contexts）

Hermes dashboard 用 **React Context + useState** 管理全局状态，**没有 zustand / jotai / Redux**。

| Context | Provider 位置 | 提供什么 | 用法 |
| --- | --- | --- | --- |
| `ThemeContext` | `main.tsx:18`（最外层） | `{ theme, themeName, availableThemes, setTheme, fontId, fontChoices, setFont }` | `const { theme } = useTheme()`（来自 `@/themes`） |
| `I18nContext` | `main.tsx:17` | `{ locale, setLocale, t: Translations }` | `const { t, locale } = useI18n()`（来自 `@/i18n`） |
| `SystemActionsContext` | `main.tsx:19`（包在 Theme 内） | `{ activeAction, actionStatus, pendingAction, isBusy, isRunning, runAction, dismissLog }` | `const { runAction } = useSystemActions()`（来自 `@/contexts/useSystemActions`） |
| `ProfileContext` | `App.tsx:514` | `{ profile, currentProfile, profiles, setProfile }` | `const { profile } = useProfileScope()`（来自 `@/contexts/useProfileScope`） |
| `PageHeaderContext` | `App.tsx:757` | `{ setTitle, setAfterTitle, setEnd }` | `const { setEnd } = usePageHeader()`（来自 `@/contexts/usePageHeader`） |

**PageHeader 用法（页面 header "右侧 end slot" 注入按钮的标准做法，`web/src/pages/SessionsPage.tsx:1011-1025`）：**
```tsx
const { setAfterTitle, setEnd } = usePageHeader();

useEffect(() => {
  setEnd(
    <Button outlined size="sm" onClick={() => setPruneOpen(true)} prefix={<Archive />}>
      Prune old sessions
    </Button>,
  );
  return () => { setEnd(null); };
}, [setEnd]);
```

### 4.3 国际化（i18n）

#### 4.3.1 用法

```tsx
import { useI18n } from "@/i18n";

const { t, locale, setLocale } = useI18n();
t.common.save         // "Save" (en)
t.sessions.title      // "Sessions" (en)
t.app.nav.chat        // "Chat"
```

#### 4.3.2 支持的 locale（`web/src/i18n/types.ts`）

`en`, `zh`, `zh-hant`, `ja`, `de`, `es`, `fr`, `tr`, `uk`, `af`, `ko`, `it`, `ga`, `pt`, `ru`, `hu`, `ar`。共 17 种，arabic 走 RTL（`<html dir="rtl">` 由 `applyDocumentLocale(locale)` 自动设置）。

#### 4.3.3 message 文件结构

每种 locale 一个文件，导出 `Translations` 强类型对象。**所有 key 必须在 `types.ts:Translations` 接口中声明**（不是松散字符串）。

```
web/src/i18n/
├── index.ts          # I18nProvider / useI18n / Locale / Translations 出口
├── context.tsx       # I18nProvider 实际实现 + LOCALE_META
├── types.ts          # Translations 接口（18 个命名空间：common / app / status / sessions / analytics / models / logs / cron / pluginsPage / profiles / skills / config / env / oauth / language / theme / achievements / kanban）
├── define-locale.ts  # （未读，大概率是 builder helper）
├── en.ts             # 英语（主版本，所有 key 都填齐）
├── zh.ts             # 简体中文
├── zh-hant.ts
├── ja.ts
├── de.ts
├── es.ts
├── fr.ts
├── tr.ts
├── uk.ts
├── af.ts
├── ko.ts
├── it.ts
├── ga.ts
├── pt.ts
├── ru.ts
├── hu.ts
└── ar.ts
```

#### 4.3.4 真实翻译片段（`web/src/i18n/en.ts:4-55`）
```ts
export const en: Translations = {
  common: {
    save: "Save",
    saving: "Saving...",
    cancel: "Cancel",
    close: "Close",
    confirm: "Confirm",
    delete: "Delete",
    // ...
    loadFailed: "Could not load {what}. Check that the dashboard server is running and click Retry.",
    loadFailedDetails: "Details: {detail}",
    // ...
  },
  app: {
    brand: "Hermes Agent",
    brandShort: "HA",
    // ...
    nav: {
      analytics: "Analytics",
      chat: "Chat",
      config: "Config",
      // ...
    },
  },
  // ...
};
```

#### 4.3.5 模板插值约定

`{count}` / `{name}` / `{s}` 这种 `{}` 占位符——**写的时候用 `String.replace` 自己填**（没有 `t('foo', {count: 5})` 这种 ICU API）。

```ts
t.skills.resultCount   // "{count} result{s}"
// 用法：
t.skills.resultCount
  .replace("{count}", String(n))
  .replace("{s}", n !== 1 ? "s" : "")
```

#### 4.3.6 持久化

localStorage `hermes-locale`。`setLocale(l)` 由 `<LanguageSwitcher>` 触发。

---

## 5. 页面模板（直接套用）

下面是 hermes 一个最简单页面的完整骨架（融合 `SessionsPage` 的所有惯用法）。LifeCore 写新页面时直接复制这个骨架再换内容。

```tsx
// web/src/pages/SessionsPage.tsx 的关键骨架，按出现顺序摘
import {
  useEffect, useLayoutEffect, useMemo, useState, useCallback, useRef,
} from "react";
import { useNavigate } from "react-router";
import { /* your icons */ } from "lucide-react";
import { api } from "@/lib/api";
import type { /* 你的 response type */ } from "@/lib/api";
import { timeAgo } from "@/lib/utils";
import { Toast } from "@nous-research/ui/ui/components/toast";
import { Button } from "@nous-research/ui/ui/components/button";
import { Checkbox } from "@nous-research/ui/ui/components/checkbox";
import { ListItem } from "@nous-research/ui/ui/components/list-item";
import { Segmented } from "@nous-research/ui/ui/components/segmented";
import { Spinner } from "@nous-research/ui/ui/components/spinner";
import { Badge } from "@nous-research/ui/ui/components/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@nous-research/ui/ui/components/card";
import { DeleteConfirmDialog } from "@/components/DeleteConfirmDialog";
import { useConfirmDelete } from "@nous-research/ui/hooks/use-confirm-delete";
import { Input } from "@nous-research/ui/ui/components/input";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle,
} from "@nous-research/ui/ui/components/dialog";
import { useSystemActions } from "@/contexts/useSystemActions";
import { useToast } from "@nous-research/ui/hooks/use-toast";
import { useI18n } from "@/i18n";
import { usePageHeader } from "@/contexts/usePageHeader";
import { PluginSlot } from "@/plugins";
import { errorMessage } from "@/lib/api-error";

export default function MyPage() {
  // 1. State
  const [data, setData] = useState<MyResponse | null>(null);
  const [loading, setLoading] = useState(true);

  // 2. Hooks
  const { toast, showToast } = useToast();
  const { t } = useI18n();
  const { setEnd } = usePageHeader();

  // 3. Delete dialog (hermes 标准模式)
  const myDelete = useConfirmDelete({
    onDelete: useCallback(async (id: string) => {
      try {
        await api.deleteThing(id);
        showToast(`Deleted: "${id}"`, "success");
        loadData();
      } catch (e) {
        showToast(`Error: ${errorMessage(e)}`, "error");
        throw e;
      }
    }, [showToast]),
  });

  // 4. Load
  const loadData = useCallback(() => {
    return api.getThings().then(setData)
      .catch(() => showToast("Failed to load", "error"))
      .finally(() => setLoading(false));
  }, [showToast]);
  useEffect(() => { loadData(); }, [loadData]);

  // 5. Page header toolbar button
  useEffect(() => {
    setEnd(
      <Button outlined size="sm" prefix={<Plus />} onClick={() => setOpen(true)}>
        New thing
      </Button>,
    );
    return () => { setEnd(null); };
  }, [setEnd]);

  // 6. Render
  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Spinner className="text-2xl text-primary" />
      </div>
    );
  }

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-6">
      <Toast toast={toast} />

      <DeleteConfirmDialog
        open={myDelete.isOpen}
        onCancel={myDelete.cancel}
        onConfirm={myDelete.confirm}
        title="Delete thing"
        description={`"${myDelete.pendingId ?? ""}" — permanently remove?`}
        loading={myDelete.isDeleting}
      />

      {things.length === 0 ? (
        <Card>
          <CardContent className="py-8 text-center text-sm text-muted-foreground">
            No things yet.
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-3">
          {things.map((thing) => (
            <Card key={thing.id}>
              <CardContent className="flex items-start gap-4 py-4">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
                    <span className="font-medium text-sm truncate">{thing.name}</span>
                    <Badge tone="secondary">{thing.kind}</Badge>
                  </div>
                  <p className="text-xs text-muted-foreground">{thing.description}</p>
                </div>
                <Button
                  ghost
                  destructive
                  size="icon"
                  onClick={() => myDelete.requestDelete(thing.id)}
                  aria-label="Delete"
                >
                  <Trash2 />
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
```

---

## 6. Hermes 自己的业务组件（可直接复用，不是 UI 库）

下面这些**不是**基础 UI 库，是 hermes 自己写的业务组件，**LifeCore 写新页面也可以拿来复用**：

| 组件 | 路径 | 作用 |
| --- | --- | --- |
| `DeleteConfirmDialog` | `web/src/components/DeleteConfirmDialog.tsx` | 包了 `destructive=true`、`confirmLabel=t.common.delete`、`cancelLabel=t.common.cancel` 的 ConfirmDialog 封装 |
| `LoadErrorNotice` | `web/src/components/LoadErrorNotice.tsx` | 加载失败的"红色"卡片，带 Retry 按钮（替代 toast 加载错误） |
| `ModelPickerDialog` | `web/src/components/ModelPickerDialog.tsx` | 模型选择对话框 |
| `SkillEditorDialog` | `web/src/components/SkillEditorDialog.tsx` | SKILL.md 编辑器（Dialog 形式） |
| `ScheduleBuilder` | `web/src/components/ScheduleBuilder.tsx` | Cron 表达式构建器 |
| `AutomationBlueprints` | `web/src/components/AutomationBlueprints.tsx` | Cron 模板库 |
| `AuthWidget` | `web/src/components/AuthWidget.tsx` | 侧栏底部 OAuth 登录状态 |
| `AutoField` | `web/src/components/AutoField.tsx` | 根据 schema 自动渲染 Select/Switch/Input |
| `Markdown` | `web/src/components/Markdown.tsx` | 安全渲染 markdown |
| `MemoryPressureBanner` | `web/src/components/MemoryPressureBanner.tsx` | 顶部 banner：内存/磁盘压力提示 |
| `ProfileSwitcher` | `web/src/components/ProfileSwitcher.tsx` | 侧栏顶部 profile 下拉 |
| `ProfileScopeBanner` | `web/src/components/ProfileScopeBanner.tsx` | "正在管理 profile X" 的 banner |
| `SidebarFooter` | `web/src/components/SidebarFooter.tsx` | 版本号 + Nous Research 链接 |
| `SidebarStatusStrip` | `web/src/components/SidebarStatusStrip.tsx` | 侧栏 Gateway 状态行 |
| `ThemeSwitcher` | `web/src/components/ThemeSwitcher.tsx` | 主题 + 字体切换器 |
| `LanguageSwitcher` | `web/src/components/LanguageSwitcher.tsx` | 语言切换器 |

> Hermes 还有两个 hook：`useModalBehavior`（`web/src/hooks/useModalBehavior.ts`）—— 接管 ESC / 焦点陷阱 / 滚轮锁 / 点遮罩关闭，写自定义 portal 时必备；`useSidebarStatus`（`web/src/hooks/useSidebarStatus.ts`）—— 轮询 `/api/status`，返回 Gateway / agent / cron / memory 状态。

---

## 7. 关键 takeaway（一句话）

**想写得像 hermes：**
1. 用 `@nous-research/ui` 的全套组件（Button / Card / Input / Select / Dialog / Toast / Switch / Checkbox / ListItem / Segmented / Stats / BottomSheet / Badge / Spinner / Label / H2 / Typography / ConfirmDialog）—— 别自己造轮子。
2. 所有基础色用 shadcn 兼容 token（`bg-card` / `text-midground` / `text-muted-foreground` / `border-border` / `bg-background-base`），圆角靠 `--radius-lg` 间接被 Tailwind v4 `--radius-{sm,md,lg,xl}` 解析。
3. 字体上：chrome 走 `font-mondwest text-display tracking-wider uppercase`（DS 专有字体），正文走 `font-sans`，数据 / 命令走 `font-mono-ui`。
4. 模态一律 `bg-card`（**不透明**） + `border-border` + `z-[100]` + `createPortal(..., document.body)`，backdrop 是 `bg-background/85`。
5. 全局状态用 React Context（hermes **没有** zustand/jotai），API 走 `api.xxx()` + `fetchJSON`，i18n 走 `useI18n().t.xxx`，删除走 `useConfirmDelete` + `DeleteConfirmDialog`，按钮 loading 走 `prefix={busy ? <Spinner /> : icon}`。
6. **页面顶部 header 的"右侧操作按钮"必须通过 `usePageHeader().setEnd(...)` 注入**（这是 hermes 所有页面的统一约定，不允许自己另起 toolbar）。
