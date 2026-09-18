/**
 * LifeCore translations — a single object that satisfies the optional
 * `lifecore?` namespace declared in `types.ts`.
 *
 * To keep en.ts / zh.ts (and the other 15 locales) untouched, we define
 * both English and Chinese here and let the language context pick the
 * right one based on the active `Locale`. The shape is a flat key map
 * keyed by locale.
 */
import type { Translations } from "./types";

export interface LifecoreTranslations {
  common?: Translations["common"];
  open?: string;
  voice?: {
    on: string;
    off: string;
    bannerTitle: string;
    bannerHint: string;
    agree: string;
  };
  pair?: {
    title: string;
    subtitle: string;
    baseLabel: string;
    codeLabel: string;
    codePlaceholder: string;
    codeHint: string;
    pairing: string;
    pair: string;
    success: string;
    failPrefix: string;
    needBase: string;
    needCode: string;
    footer: string;
  };
  today?: {
    statNotify: string;
    statSessions: string;
    statChannels: string;
    statJobs: string;
    recentSessions: string;
    noSessions: string;
    openChat: string;
    channelsTitle: string;
    goChannels: string;
    goJobs: string;
  };
  chat?: {
    pickerTitle: string;
    noSessions: string;
    empty: string;
    back: string;
    inputPlaceholder: string;
    sending: string;
    mic: string;
    micFail: string;
    asrEmpty: string;
    asrFail: string;
  };
  notify?: {
    pendingTitle: string;
    empty: string;
    queueTitle: string;
    queueEmpty: string;
    singleLock: string;
    decided: string;
  };
  channels?: {
    empty: string;
    test: string;
    testHint: string;
    tested: string;
    createTitle: string;
    name: string;
    nameHint: string;
    archetype: string;
    uplink: string;
    copy: string;
    secretWarn: string;
    created: string;
    deleted: string;
    deleteTitle: string;
    deleteDesc: string;
  };
  jobs?: {
    title: string;
    total: string;
    running: string;
    paused: string;
    runBadge: string;
    pauseBadge: string;
    unknownBadge: string;
    resume: string;
    pause: string;
    trigger: string;
    empty: string;
    ok_pause: string;
    ok_resume: string;
    ok_trigger: string;
  };
  settings?: {
    deviceTitle: string;
    deviceName: string;
    deviceFp: string;
    registeredAt: string;
    serverTime: string;
    serverTitle: string;
    baseLabel: string;
    baseSaved: string;
    ttsTitle: string;
    ttsLabel: string;
    ttsPlay: string;
    ttsNeedConsent: string;
    voiceTitle: string;
    voiceEnable: string;
    voiceDisable: string;
    asrTitle: string;
    asrDesc: string;
    asrHint: string;
    asrCta: string;
    capsTitle: string;
    unpairTitle: string;
    unpairDesc: string;
    unpairCta: string;
    unpairConfirmTitle: string;
    unpairConfirmDesc: string;
    unpaired: string;
  };
  /** P0-S1 — AllItemsPage */
  all?: {
    title: string;
    subtitle: string;
    empty: string;
    columns: {
      summary: string;
      priority: string;
      state: string;
      channel: string;
      createdAt: string;
      ownerOnly: string;
    };
    stateLabels: {
      logged: string;
      queued: string;
      awaiting_feedback: string;
      resolved: string;
    };
  };
  /** P0-S2 — EventsPage */
  events?: {
    title: string;
    subtitle: string;
    channelLabel: string;
    channelAll: string;
    columns: {
      seq: string;
      channel: string;
      payload: string;
      receivedAt: string;
    };
    pullNew: string;
    empty: string;
    maxSeq: string;
  };
  /** P1-S3 — NotifyPage timeline dialog */
  timeline?: {
    title: string;
    open: string;
    empty: string;
    loading: string;
    threadPrefix: string;
  };
  /** P1-S4 — OwnerOnlyPage */
  owner?: {
    title: string;
    subtitle: string;
    empty: string;
    feedback: string;
    feedbackSnooze: string;
    feedbackDismissed: string;
    feedbackActioned: string;
  };
  /** P1-S5 — ChannelsPage health chip */
  bridge?: {
    healthTitle: string;
    healthActive: string;
    healthStale: string;
    healthSilent: string;
    healthRevoked: string;
    healthLoading: string;
    lastEventAt: string;
    neverSeen: string;
    count24h: string;
    upstreamStatus: string;
  };
  /** P1-S6 — TodayPage real digest */
  digest?: {
    countersTitle: string;
    counterEvents: string;
    counterThreads: string;
    counterAwaitingOwner: string;
    counterNeedsFeedback: string;
    cardA: string;
    cardB: string;
    cardC: string;
    empty: string;
    askReplyPlaceholder: string;
    askReplySend: string;
  };
  /** Shared filter copy */
  filter?: {
    state: {
      logged: string;
      queued: string;
      awaiting_feedback: string;
      resolved: string;
      all: string;
    };
    priority: {
      high: string;
      normal: string;
      all: string;
    };
  };
}

/** English LC strings — fallback when locale isn't `zh*`. */
export const enLc: LifecoreTranslations = {
  common: {
    save: "Save",
    saving: "Saving...",
    cancel: "Cancel",
    close: "Close",
    confirm: "Confirm",
    delete: "Delete",
    refresh: "Refresh",
    retry: "Retry",
    search: "Search...",
    loading: "Loading...",
    create: "Create",
    creating: "Creating...",
    set: "Set",
    replace: "Replace",
    clear: "Clear",
    live: "Live",
    off: "Off",
    enabled: "enabled",
    disabled: "disabled",
    active: "active",
    inactive: "inactive",
    unknown: "unknown",
    untitled: "Untitled",
    none: "None",
    form: "Form",
    noResults: "No results",
    of: "of",
    page: "Page",
    msgs: "msgs",
    tools: "tools",
    match: "match",
    other: "Other",
    configured: "configured",
    removed: "removed",
    failedToToggle: "Failed to toggle",
    failedToRemove: "Failed to remove",
    failedToReveal: "Failed to reveal",
    collapse: "Collapse",
    expand: "Expand",
    general: "General",
    messaging: "Messaging",
    pluginLoadFailed: "Plugin failed to load",
    pluginNotRegistered: "Plugin not registered",
  },
  open: "Open",
  voice: {
    on: "Voice on",
    off: "Voice off",
    bannerTitle: "Streaming voice playback needs your consent.",
    bannerHint: "Once enabled, every reply is read aloud (MiniMax TTS).",
    agree: "Agree & enable",
  },
  pair: {
    title: "Pair this browser",
    subtitle:
      "Enter the 8-digit pairing code to register this browser as your device.",
    baseLabel: "Server",
    codeLabel: "Pairing code",
    codePlaceholder: "8 digits (valid for 10 minutes)",
    codeHint: "Get a code at {base}/pair",
    pairing: "Pairing…",
    pair: "Pair",
    success: "Paired",
    failPrefix: "Pair failed: ",
    needBase: "Please fill in the server URL",
    needCode: "Pairing code must be 8 digits",
    footer: "Already paired? Open the Today view.",
  },
  today: {
    statNotify: "Pending",
    statSessions: "Sessions",
    statChannels: "Channels",
    statJobs: "Jobs",
    recentSessions: "Recent sessions",
    noSessions: "No sessions yet",
    openChat: "Continue chat",
    channelsTitle: "Channels overview",
    goChannels: "Manage channels",
    goJobs: "Manage jobs",
  },
  chat: {
    pickerTitle: "Pick a session to continue",
    noSessions: "No sessions available",
    empty: "Say hello",
    back: "Sessions",
    inputPlaceholder: "Type a message, Enter to send",
    sending: "Sending…",
    mic: "Voice input",
    micFail: "Microphone unavailable: ",
    asrEmpty: "No speech detected",
    asrFail: "ASR failed: ",
  },
  notify: {
    pendingTitle: "Pending decisions",
    empty: "No pending decisions",
    queueTitle: "Queue",
    queueEmpty: "Queue is empty",
    singleLock: "Single-activity lock — one decision at a time",
    decided: "Decision recorded",
  },
  channels: {
    empty: "No channels yet",
    test: "Test event",
    testHint: "Inject a synthetic event into this channel",
    tested: "Test event injected",
    createTitle: "Register a new channel",
    name: "Name",
    nameHint: "e.g. wechat-monitor",
    archetype: "Archetype",
    uplink: "Uplink level",
    copy: "Copy",
    secretWarn: "Secret shown only once — save it now.",
    created: "Channel registered",
    deleted: "Channel deleted",
    deleteTitle: "Delete channel?",
    deleteDesc: "Once deleted, the ingest URL will stop accepting events.",
  },
  jobs: {
    title: "Scheduled jobs",
    total: "Total",
    running: "Running",
    paused: "Paused",
    runBadge: "▶ running",
    pauseBadge: "⏸ paused",
    unknownBadge: "?",
    resume: "Resume",
    pause: "Pause",
    trigger: "Trigger",
    empty: "No jobs yet",
    ok_pause: "Job paused",
    ok_resume: "Job resumed",
    ok_trigger: "Job triggered",
  },
  settings: {
    deviceTitle: "Device",
    deviceName: "Name",
    deviceFp: "Fingerprint",
    registeredAt: "Registered",
    serverTime: "Server time",
    serverTitle: "Server",
    baseLabel: "Server URL",
    baseSaved: "Server URL saved",
    ttsTitle: "TTS test",
    ttsLabel: "Text to speak",
    ttsPlay: "Play",
    ttsNeedConsent:
      "Voice consent required first (banner at top of ChatPage).",
    voiceTitle: "Voice consent",
    voiceEnable: "Enable",
    voiceDisable: "Disable",
    asrTitle: "Voice input (ASR)",
    asrDesc:
      "Open ChatPage and use the microphone button next to the input.",
    asrHint: "ASR entry lives on ChatPage's mic button.",
    asrCta: "Open ChatPage",
    capsTitle: "Gateway capabilities",
    unpairTitle: "Danger zone",
    unpairDesc:
      "Unpair this browser. You will need to enter a new pairing code to reconnect.",
    unpairCta: "Unpair",
    unpairConfirmTitle: "Unpair this browser?",
    unpairConfirmDesc:
      "This browser immediately loses access. The action cannot be undone.",
    unpaired: "Unpaired",
  },
  // P0-S1 — AllItemsPage
  all: {
    title: "All items",
    subtitle:
      "Every notify_item across all states. Click a row to open the thread timeline.",
    empty: "No items in this state",
    columns: {
      summary: "Summary",
      priority: "Priority",
      state: "State",
      channel: "Channel",
      createdAt: "Created",
      ownerOnly: "Owner only",
    },
    stateLabels: {
      logged: "logged",
      queued: "queued",
      awaiting_feedback: "awaiting_feedback",
      resolved: "resolved",
    },
  },
  // P0-S2 — EventsPage
  events: {
    title: "Event stream",
    subtitle:
      "Raw events as they land on the server — useful for tracing channel → notify_items.",
    channelLabel: "Channel",
    channelAll: "All channels",
    columns: {
      seq: "seq",
      channel: "channel",
      payload: "payload",
      receivedAt: "received_at",
    },
    pullNew: "Pull new (since max seq)",
    empty: "No events for this filter",
    maxSeq: "max seq",
  },
  // P1-S3 — NotifyPage timeline dialog
  timeline: {
    title: "Thread timeline",
    open: "Timeline",
    empty: "No items in this thread",
    loading: "Loading timeline…",
    threadPrefix: "thread",
  },
  // P1-S4 — OwnerOnlyPage
  owner: {
    title: "Owner-only",
    subtitle:
      "Items flagged for your eyes only — high-priority / awaiting your decision.",
    empty: "No owner-only items right now",
    feedback: "Feedback",
    feedbackSnooze: "Snooze",
    feedbackDismissed: "Dismiss",
    feedbackActioned: "Action",
  },
  // P1-S5 — ChannelsPage health chip
  bridge: {
    healthTitle: "Health",
    healthActive: "active",
    healthStale: "stale > 1h",
    healthSilent: "silent > 24h",
    healthRevoked: "revoked",
    healthLoading: "…",
    lastEventAt: "last event",
    neverSeen: "never",
    count24h: "24h",
    upstreamStatus: "upstream",
  },
  // P1-S6 — TodayPage real digest
  digest: {
    countersTitle: "Today's pulse",
    counterEvents: "events today",
    counterThreads: "active threads",
    counterAwaitingOwner: "awaiting you",
    counterNeedsFeedback: "needs feedback",
    cardA: "Happened today",
    cardB: "Your turn",
    cardC: "Asks for you",
    empty: "Nothing here yet",
    askReplyPlaceholder: "Reply…",
    askReplySend: "Send",
  },
  // Shared filter copy
  filter: {
    state: {
      logged: "Logged",
      queued: "Queued",
      awaiting_feedback: "Awaiting you",
      resolved: "Resolved",
      all: "All",
    },
    priority: {
      high: "High priority",
      normal: "Normal",
      all: "All priorities",
    },
  },
};

/** Simplified Chinese LC strings. */
export const zhLc: LifecoreTranslations = {
  common: enLc.common, // LC UI 中文也直接复用英文 common 兜底
  open: "打开",
  voice: {
    on: "播报已开",
    off: "播报已关",
    bannerTitle: "流式语音播报需要你的同意。",
    bannerHint: "启用后，每条回复将以流式音频朗读（MiniMax TTS）。",
    agree: "同意并启用",
  },
  pair: {
    title: "LifeCore 设备配对",
    subtitle: "输入 8 位配对码，把这个浏览器注册为你的设备。",
    baseLabel: "服务器",
    codeLabel: "配对码",
    codePlaceholder: "8 位数字（10 分钟内有效）",
    codeHint: "在 {base}/pair 页面获取配对码",
    pairing: "配对中…",
    pair: "配对",
    success: "配对成功",
    failPrefix: "配对失败：",
    needBase: "请填写服务器地址",
    needCode: "配对码应为 8 位数字",
    footer: "已配对？直接前往今日视图。",
  },
  today: {
    statNotify: "待裁决",
    statSessions: "活跃会话",
    statChannels: "已注册通道",
    statJobs: "任务数",
    recentSessions: "最近会话",
    noSessions: "暂无会话",
    openChat: "继续对话",
    channelsTitle: "通道速览",
    goChannels: "管理通道",
    goJobs: "管理任务",
  },
  chat: {
    pickerTitle: "选择一个会话继续对话",
    noSessions: "暂无可选会话",
    empty: "开始对话吧",
    back: "会话列表",
    inputPlaceholder: "输入消息，Enter 发送",
    sending: "发送中…",
    mic: "语音输入",
    micFail: "麦克风不可用：",
    asrEmpty: "未识别到语音",
    asrFail: "转写失败：",
  },
  notify: {
    pendingTitle: "等待你的决策",
    empty: "当前没有等待决策的事项",
    queueTitle: "排队中",
    queueEmpty: "排队为空",
    singleLock: "单活动锁：同一时间只一条决策",
    decided: "已记录决策",
  },
  channels: {
    empty: "暂无通道",
    test: "测试事件",
    testHint: "向该通道注入一条测试事件",
    tested: "测试事件已注入",
    createTitle: "注册新通道",
    name: "名称",
    nameHint: "例如 wechat-monitor",
    archetype: "类型",
    uplink: "上行等级",
    copy: "复制",
    secretWarn: "secret 仅显示一次，请立即保存。",
    created: "通道已注册",
    deleted: "已删除通道",
    deleteTitle: "删除通道？",
    deleteDesc: "删除后该通道的 ingest_url 将无法继续上报事件。",
  },
  jobs: {
    title: "定时任务",
    total: "总数",
    running: "运行中",
    paused: "已暂停",
    runBadge: "▶ 运行中",
    pauseBadge: "⏸ 暂停",
    unknownBadge: "?",
    resume: "恢复",
    pause: "暂停",
    trigger: "触发",
    empty: "暂无任务",
    ok_pause: "任务已暂停",
    ok_resume: "任务已恢复",
    ok_trigger: "任务已触发",
  },
  settings: {
    deviceTitle: "设备",
    deviceName: "名称",
    deviceFp: "指纹",
    registeredAt: "注册时间",
    serverTime: "服务器时间",
    serverTitle: "服务器",
    baseLabel: "服务器地址",
    baseSaved: "服务器地址已更新",
    ttsTitle: "TTS 试听",
    ttsLabel: "要说的话",
    ttsPlay: "播放",
    ttsNeedConsent: "需要先同意语音播报（ChatPage 顶部 banner）。",
    voiceTitle: "语音播报同意",
    voiceEnable: "启用",
    voiceDisable: "关闭",
    asrTitle: "语音输入 (ASR)",
    asrDesc: "打开 ChatPage，使用输入框旁的麦克风按钮录制语音。",
    asrHint: "ASR 入口在 ChatPage 麦克风按钮",
    asrCta: "前往 ChatPage",
    capsTitle: "网关能力",
    unpairTitle: "危险操作",
    unpairDesc: "解除配对后本浏览器将失去访问权限，需要重新输入配对码。",
    unpairCta: "解除配对",
    unpairConfirmTitle: "解除配对？",
    unpairConfirmDesc: "本浏览器将立即失去访问权限。继续操作无法撤销。",
    unpaired: "已解除配对",
  },
  // P0-S1 — AllItemsPage
  all: {
    title: "全部事项",
    subtitle: "所有 notify_item 的全 state 视图。点击行打开 thread 时间线。",
    empty: "该 state 下没有事项",
    columns: {
      summary: "摘要",
      priority: "优先级",
      state: "状态",
      channel: "通道",
      createdAt: "创建时间",
      ownerOnly: "主人专属",
    },
    stateLabels: {
      logged: "已记录",
      queued: "排队中",
      awaiting_feedback: "等你决策",
      resolved: "已处理",
    },
  },
  // P0-S2 — EventsPage
  events: {
    title: "事件流",
    subtitle: "服务端接收到的原始事件流，可用于追溯通道→事项链路。",
    channelLabel: "通道",
    channelAll: "全部通道",
    columns: {
      seq: "序号",
      channel: "通道",
      payload: "载荷",
      receivedAt: "接收时间",
    },
    pullNew: "增量拉新（since 当前 max seq）",
    empty: "该过滤下暂无事件",
    maxSeq: "最大 seq",
  },
  // P1-S3 — NotifyPage 时间线弹窗
  timeline: {
    title: "议题时间线",
    open: "时间线",
    empty: "该议题暂无事项",
    loading: "加载时间线中…",
    threadPrefix: "议题",
  },
  // P1-S4 — OwnerOnlyPage
  owner: {
    title: "我的专属",
    subtitle: "标记给你的事项——高优先级 / 等你决策。",
    empty: "当前无主人专属事项",
    feedback: "反馈",
    feedbackSnooze: "稍后",
    feedbackDismissed: "忽略",
    feedbackActioned: "已办",
  },
  // P1-S5 — ChannelsPage 健康度
  bridge: {
    healthTitle: "健康度",
    healthActive: "活跃",
    healthStale: "静默>1h",
    healthSilent: "静默>24h",
    healthRevoked: "已吊销",
    healthLoading: "…",
    lastEventAt: "最后事件",
    neverSeen: "从未",
    count24h: "24h",
    upstreamStatus: "上游",
  },
  // P1-S6 — TodayPage 真 digest
  digest: {
    countersTitle: "今日脉搏",
    counterEvents: "今日事件",
    counterThreads: "活跃议题",
    counterAwaitingOwner: "待你回应",
    counterNeedsFeedback: "等你决策",
    cardA: "今日已发生",
    cardB: "等你回应",
    cardC: "想问你的",
    empty: "暂无内容",
    askReplyPlaceholder: "回复…",
    askReplySend: "发送",
  },
  // 共享 filter 文案
  filter: {
    state: {
      logged: "已记录",
      queued: "排队中",
      awaiting_feedback: "等你决策",
      resolved: "已处理",
      all: "全部",
    },
    priority: {
      high: "高优先级",
      normal: "普通",
      all: "全部优先级",
    },
  },
};
