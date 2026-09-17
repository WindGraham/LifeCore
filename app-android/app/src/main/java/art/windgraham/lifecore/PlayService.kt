package art.windgraham.lifecore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自动播报前台服务（docs/13 §6；设计原则 1/3/4/8/10）。
 *
 * - 单 WS 连接 `/v2/notify/stream?token=...`；OkHttp `pingInterval(20s)` 守活
 * - 指数退避：1s → 2s → 4s → 8s → 16s 上限（最多 5 次连续失败后进入 idle 模式，每 5min 重试一次）
 * - lastSpokenId 用 SharedPreferences 持久化单调守护（重启不重置）
 * - speak() 必须在 Thread{}.start() 后台跑（不能 NetworkOnMainThreadException）
 * - MessagingStyle + notification_id = 100000 + thread_id → 同 thread 只更新不新建
 * - 三个 NotificationChannel：
 *     - lc_playback     ：前台服务（IMPORTANCE_LOW）
 *     - lc_alert_heads  ：urgent 类型抬头（L2 决策）
 *     - lc_alert_silent ：普通线程续命（L0/L1 不响铃）
 * - 默认沉默：只有 notify.priority='urgent' + 用户开启震动 才震动（设计原则 10）
 */
class PlayService : Service() {

    // ── 状态 ──
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false

    // 单调守护：从 prefs 读取，只增不减
    private var lastSpokenId: Int = 0

    // ── WS / 重连 ──
    private var ws: WebSocket? = null
    private var backoffIdx = 0
    private var consecutiveFail = 0
    private var disconnectedAt = 0L            // SystemClock.elapsedRealtime；0 = 在线
    @Volatile private var polling = false
    @Volatile private var idleMode = false     // 5 次失败后转 idle

    private val reconnectRunnable = Runnable { connectWs() }
    private val pollRunnable = Runnable { pollOnce() }
    private val idleWakeup = Runnable { if (idleMode && ws == null) connectWs() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY

        Api.init(this)
        if (Api.token == null) { stopSelf(); return START_NOT_STICKY }   // 未配对直接退

        running = true
        // 持久化单调守护（设计原则 3）：重启/重连不回放
        lastSpokenId = Api.lastSpokenId

        // WakeLock 24h 上限，服务活着就持有
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifecore:playback").apply {
            acquire(24L * 60 * 60 * 1000)
        }

        ensureChannels()
        startForeground(NOTIF_ID, buildForegroundNotif("LifeCore 自动播报", "等待新汇报…"))
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Api.init(this)
        if (Api.token == null) return
        if (running) return
        running = true
        lastSpokenId = Api.lastSpokenId

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifecore:playback").apply {
            acquire(24L * 60 * 60 * 1000)
        }
        ensureChannels()
        startForeground(NOTIF_ID, buildForegroundNotif("LifeCore 自动播报", "等待新汇报…"))
        connectWs()
    }

    // ── WS 长连（设计原则 1：单连接）──

    private fun connectWs() {
        if (!running) return
        val t = Api.token ?: run { scheduleReconnect(); return }
        idleMode = false
        val url = Api.wsUrl("/v2/notify/stream?token=" + Uri.encode(t))
        runCatching {
            ws = Api.client.newWebSocket(Request.Builder().url(url).build(), wsListener)
        }.onFailure {
            Log.w(TAG, "WS connect throw: ${it.message}")
            handler.post { onWsDown() }
        }
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            handler.post {
                backoffIdx = 0
                consecutiveFail = 0
                disconnectedAt = 0
                stopPolling()
                handler.removeCallbacks(idleWakeup)
                updateForegroundNotif("自动播报运行中 · 已连接")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val snap = JSONObject(text)
                handler.post { handleSnapshot(snap) }
            } catch (e: Exception) {
                Log.w(TAG, "bad ws message: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.w(TAG, "ws failure: ${t.message}")
            handler.post { onWsDown() }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closed code=$code reason=$reason")
            handler.post { onWsDown() }
        }
    }

    private fun onWsDown() {
        if (!running) return
        if (disconnectedAt == 0L) disconnectedAt = SystemClock.elapsedRealtime()
        consecutiveFail++
        updateForegroundNotif("自动播报运行中 · 连接断开，重连中…")

        if (consecutiveFail >= IDLE_THRESHOLD) {
            // 设计原则 8：最多 5 次失败 → idle，每 5min 重试一次
            idleMode = true
            handler.postDelayed(idleWakeup, IDLE_RETRY_MS)
        } else {
            scheduleReconnect()
        }

        // 兜底：WS 断开 2 分钟 → 60s 轮询
        if (!polling && SystemClock.elapsedRealtime() - disconnectedAt > FALLBACK_AFTER_MS) startPolling()
    }

    /** 指数退避：1s → 2s → 4s → 8s → 16s 上限。 */
    private fun scheduleReconnect() {
        if (!running) return
        handler.removeCallbacks(reconnectRunnable)
        val d = BACKOFF_MS[backoffIdx.coerceAtMost(BACKOFF_MS.size - 1)]
        if (backoffIdx < BACKOFF_MS.size - 1) backoffIdx++
        handler.postDelayed(reconnectRunnable, d)
    }

    private fun startPolling() {
        polling = true
        pollOnce()
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun pollOnce() {
        if (!running || !polling) return
        // 设计原则 4：IO 后台线程
        Thread {
            try { handleSnapshot(Api.notifyActive()) } catch (e: Exception) {
                Log.w(TAG, "poll err: ${e.message}")
            }
            handler.postDelayed(pollRunnable, POLL_FALLBACK_MS)
        }.start()
    }

    // ── 快照处理（结构 = /v2/notify/active 响应体）──

    private fun handleSnapshot(r: JSONObject) {
        if (!running) return
        val active = r.optJSONObject("active")
        val q = Api.arr(r, "queue")
        if (active != null) {
            val id = active.getInt("id")
            updateForegroundNotif("待处理：" + active.optString("summary", "有新事项").take(40))
            postItemNotif(active)
            // 单调守护（设计原则 3）：id > lastSpokenId 才播报，重连回放不重播
            if (id > lastSpokenId) {
                lastSpokenId = id
                Api.lastSpokenId = id                  // 持久化
                val title = active.optString("thread_title", "")
                val line = active.optString("summary", "").substringBefore('\n').take(60)
                val speakText = listOf(title, line).filter { it.isNotBlank() }.joinToString("。")
                speak(speakText, active.optString("priority", "normal"))
            }
        } else {
            updateForegroundNotif("自动播报运行中 · 队列 ${q.length()}")
        }
    }

    // ── 通知续命（设计原则 2 + 10：MessagingStyle + 同 thread 同 nid + 沉默默认）──

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_PLAYBACK, "自动播报", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
            description = "LifeCore 后台服务的运行通知"
        })
        // L1：普通线程续命，无声
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_ALERT_SILENT, "事项提醒（静默）", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            description = "标准通知：无声音"
        })
        // L2：urgent 抬头 + TTS
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_ALERT_HEADS, "决策提醒（抬头）", NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            description = "需要立刻决策的事项"
        })
    }

    /** 选择 channel：urgent → 抬头，否则静默（设计原则 10）。 */
    private fun alertChannelFor(priority: String): String =
        if (priority.equals("urgent", ignoreCase = true)) CHANNEL_ALERT_HEADS else CHANNEL_ALERT_SILENT

    private fun postItemNotif(o: JSONObject) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val iid = o.getInt("id")
        val tid = if (o.has("thread_id") && !o.isNull("thread_id")) o.optInt("thread_id", -1) else -1
        // 设计原则 2：同 thread 永远更新同一条通知；不同 thread 的不同条
        val nid = if (tid > 0) THREAD_BASE_NOTIF_ID + tid else ITEM_BASE_NOTIF_ID + iid

        val priority = o.optString("priority", "normal")
        val channel = alertChannelFor(priority)
        val tctx = o.optJSONObject("thread_ctx")
        val generation = o.optInt("generation", 1)
        val topic = o.optString("thread_title", "")
        val title = if (topic.isNotBlank())
            "📌 $topic" + (if (generation > 1) "（第${generation}次跟进）" else "")
        else "📌 LifeCore 有新事项"

        val summary = o.optString("summary", "有新事项需要处理")

        val ai = Person.Builder().setName("LifeCore").build()
        val style = NotificationCompat.MessagingStyle(ai)
        val lines = mutableListOf<Pair<CharSequence, Long>>()
        if (tctx != null) {
            val h = Api.arr(tctx, "history")           // 新→旧
            for (k in h.length() - 1 downTo 0) {
                val m = h.getJSONObject(k)
                lines.add(m.optString("summary", "") to tsMs(m.optString("created_at", "")))
            }
        }
        lines.add(withQuoteLine(o, tctx, summary) to tsMs(o.optString("created_at", "")))
        for ((text, ts) in lines.takeLast(3)) style.addMessage(text, ts, ai)

        val contentPi = if (tid > 0)
            PendingIntent.getActivity(this, nid,
                Intent(this, ThreadDetailActivity::class.java).putExtra("thread_id", tid),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else
            PendingIntent.getActivity(this, nid, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val b = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(style)
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)                    // 同 thread 更新不重响
            .setPublicVersion(
                NotificationCompat.Builder(this, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("LifeCore")
                    .setContentText("LifeCore 有新事项")
                    .build())

        if (priority.equals("urgent", ignoreCase = true)) {
            b.setPriority(NotificationCompat.PRIORITY_HIGH)
        } else {
            b.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }

        // 设计原则 10：默认 silent，urgent 也不强制响铃（用户可在系统设置改 channel）
        val opts = Api.arr(o, "options")
        val n = minOf(opts.length(), 3)
        for (k in 0 until n) {
            val label = opts.optString(k, "选项${k + 1}")
            val action = when {
                k == n - 1 && label.contains("稍后") -> "snooze"
                k == 0 -> "actioned"
                else -> "dismissed"
            }
            val pi = PendingIntent.getBroadcast(this, nid * 10 + k,
                Intent(this, FeedbackReceiver::class.java).setAction(ACTION_FEEDBACK)
                    .putExtra("item_id", iid)
                    .putExtra("fb_action", action)
                    .putExtra("minutes", 30)
                    .putExtra("notif_id", nid),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val iconRes = when (action) {
                "actioned" -> android.R.drawable.ic_menu_send
                "dismissed" -> android.R.drawable.ic_menu_close_clear_cancel
                "snooze" -> android.R.drawable.ic_menu_recent_history
                else -> 0
            }
            b.addAction(NotificationCompat.Action.Builder(iconRes, label, pi).build())
        }

        // 撤销按钮（设计原则 9）：长按/额外按钮撤销当前决议（后端 action=undo）
        b.addAction(NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_revert, "撤销",
            PendingIntent.getBroadcast(this, nid * 10 + 9,
                Intent(this, FeedbackReceiver::class.java).setAction(ACTION_FEEDBACK)
                    .putExtra("item_id", iid).putExtra("fb_action", "undo").putExtra("notif_id", nid),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ).build())

        // 👍/👎/⏰ 反馈（docs/13 §6 反馈闭环；与通知层级联动）
        val ackPos = PendingIntent.getBroadcast(this, nid * 10 + 5,
            Intent(this, FeedbackReceiver::class.java).setAction(ACTION_FEEDBACK)
                .putExtra("item_id", iid).putExtra("fb_action", "ack").putExtra("notif_id", nid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ackNeg = PendingIntent.getBroadcast(this, nid * 10 + 6,
            Intent(this, FeedbackReceiver::class.java).setAction(ACTION_FEEDBACK)
                .putExtra("item_id", iid).putExtra("fb_action", "ack_negative").putExtra("notif_id", nid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        b.addAction(NotificationCompat.Action.Builder(0, "👍", ackPos).build())
        b.addAction(NotificationCompat.Action.Builder(0, "👎", ackNeg).build())

        mgr.notify(nid, b.build())
    }

    /** kind=resume 且上次决议 snooze：正文首行加灰字引用行（契约 §3）。 */
    private fun withQuoteLine(o: JSONObject, tctx: JSONObject?, summary: String): CharSequence {
        if (o.optString("kind") != "resume") return summary
        if (tctx?.optString("last_resolution") != "snooze") return summary
        var whenStr = Api.fmtTime(o.optString("created_at", ""))
        val h = if (tctx != null) Api.arr(tctx, "history") else return summary
        for (k in 0 until h.length()) {
            val m = h.getJSONObject(k)
            if (m.optString("resolution") == "snooze") {
                whenStr = Api.fmtTime(m.optString("created_at", "")); break
            }
        }
        val quote = "上次你说\"稍后\"" + if (whenStr.isNotBlank()) " · $whenStr" else ""
        val full = "$quote\n$summary"
        return SpannableString(full).apply {
            setSpan(ForegroundColorSpan(Api.themeColor(this@PlayService, android.R.attr.textColorSecondary)),
                0, quote.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun tsMs(iso: String): Long = try {
        val fixed = when {
            iso.endsWith("Z") -> iso
            iso.matches(Regex(".*[+-]\\d\\d:?\\d\\d$")) -> iso
            else -> iso + "Z"
        }
        java.time.OffsetDateTime.parse(fixed).toInstant().toEpochMilli()
    } catch (_: Exception) { System.currentTimeMillis() }

    // ── TTS（设计原则 4：必须 Thread{}.start() 后台跑，主线程 IO 崩）──

    private fun speak(text: String, priority: String = "normal") {
        if (text.isBlank()) return
        Thread {
            try {
                val (code, bytes) = Api.callBytes("POST", "/v2/tts",
                    body = JSONObject().put("text", "LifeCore 提醒：$text")
                        .put("priority", priority))
                Log.i(TAG, "tts fetch code=$code bytes=${bytes.size}")
                if (code != 200) return@Thread
                val f = File.createTempFile("lc_auto", ".mp3", cacheDir)
                f.writeBytes(bytes); f.deleteOnExit()
                handler.post { play(f) }
            } catch (e: Exception) {
                Log.w(TAG, "speak failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun play(f: File) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                if (Build.VERSION.SDK_INT >= 21) {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra"); false
                }
                setOnCompletionListener { Log.i(TAG, "play complete") }
                setDataSource(f.absolutePath); prepare(); start()
                Log.i(TAG, "playback started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "play throw: ${e.message}")
        }
    }

    // ── 前台服务自身通知 ──

    private fun buildForegroundNotif(title: String, body: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, PlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_PLAYBACK)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(body)
            .setContentIntent(pi)
            .addAction(0, "停止播报", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotif(body: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildForegroundNotif("LifeCore 自动播报", body))
    }

    override fun onDestroy() {
        running = false
        stopPolling()
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(idleWakeup)
        runCatching { ws?.close(1000, "service exit") }
        ws = null
        runCatching { wakeLock?.release() }
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LC-PlaySvc"

        // notification_id 命名空间（设计原则 2：同 thread 同 nid）
        const val THREAD_BASE_NOTIF_ID = 100000
        const val ITEM_BASE_NOTIF_ID = 200000
        const val NOTIF_ID = 42                  // 前台服务自身

        const val ACTION_STOP = "art.windgraham.lifecore.STOP"
        const val ACTION_FEEDBACK = "art.windgraham.lifecore.NOTIFY_FEEDBACK"

        // 三档 channel
        const val CHANNEL_PLAYBACK = "lc_playback"
        const val CHANNEL_ALERT_SILENT = "lc_alert_silent"
        const val CHANNEL_ALERT_HEADS = "lc_alert_heads"

        // 设计原则 8：退避表（1s/2s/4s/8s/16s 上限；最多 5 次后转 idle）
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000)
        const val IDLE_THRESHOLD = 5
        const val IDLE_RETRY_MS = 5L * 60 * 1000  // 5min 后重试

        // WS 断开超过 2 分钟才降级 60s 轮询
        const val FALLBACK_AFTER_MS = 120_000L
        const val POLL_FALLBACK_MS = 60_000L
    }
}