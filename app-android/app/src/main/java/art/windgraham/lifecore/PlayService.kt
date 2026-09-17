package art.windgraham.lifecore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File

/** 自动播报服务：WS 长连收 /v2/notify/stream 快照（低耗电统治模式，契约 §2），
 *  断线指数退避重连，断开超 2 分钟降级 60s 轮询兜底；新条目自动 TTS + 线程身份通知续命（契约 §3）。 */
class PlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var lastPlayedId = -1
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false

    // ── WS 状态 ──
    private var ws: WebSocket? = null
    private var backoffIdx = 0
    private var disconnectedAt = 0L          // elapsedRealtime 时间戳；0 = 在线
    @Volatile private var polling = false

    private val reconnectRunnable = object : Runnable { override fun run() { connectWs() } }
    private val pollRunnable = object : Runnable { override fun run() { pollOnce() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        running = true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifecore:playback").apply {
            acquire(24L * 60 * 60 * 1000)   // 24h 上限，服务活着就持续持有
        }
        startForeground(NOTIF_ID, buildNotif("LifeCore 自动播报", "等待新汇报…"))
        // 单调递增去重：持久化已播报的最大 item id，重启/WS 重连回放快照不会重播
        lastPlayedId = getSharedPreferences("lifecore", MODE_PRIVATE).getInt("last_spoken_id", -1)
        connectWs()
        return START_STICKY
    }

    // ── WS 长连（契约 §2）──

    private fun connectWs() {
        if (!running) return
        val t = Api.token
        if (t == null) { scheduleReconnect(); return }
        val url = Api.wsUrl("/v2/notify/stream?token=" + Uri.encode(t))
        runCatching {
            ws = Api.client.newWebSocket(Request.Builder().url(url).build(), wsListener)
        }.onFailure { onWsDown() }
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            handler.post {
                backoffIdx = 0
                disconnectedAt = 0
                stopPolling()
                updateNotif("自动播报运行中 · 已连接")
            }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val snap = JSONObject(text)
                handler.post { handleSnapshot(snap) }
            } catch (_: Exception) { }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            handler.post { onWsDown() }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handler.post { onWsDown() }
        }
    }

    private fun onWsDown() {
        if (!running) return
        if (disconnectedAt == 0L) disconnectedAt = SystemClock.elapsedRealtime()
        updateNotif("自动播报运行中 · 连接断开，重连中…")
        scheduleReconnect()
        // 契约 §2：WS 断开超过 2 分钟才降级 60s 轮询兜底
        if (!polling && SystemClock.elapsedRealtime() - disconnectedAt > FALLBACK_AFTER_MS) startPolling()
    }

    /** 指数退避重连：2s→5s→10s→30s→60s 封顶。 */
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
        Thread {
            try { handleSnapshot(Api.getJson("/v2/notify/active")) } catch (_: Exception) { }
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
            updateNotif("待处理：" + active.optString("summary", "有新事项").take(40))
            postItemNotif(active)
            if (id > lastPlayedId) {          // 单调递增：仅全新条目朗读（重连回放/空active不重置）
                lastPlayedId = id
                getSharedPreferences("lifecore", MODE_PRIVATE)
                    .edit().putInt("last_spoken_id", id).apply()
                val title = active.optString("thread_title", "")
                val line = active.optString("summary", "").substringBefore('\n').take(60)
                speak(listOf(title, line).filter { it.isNotBlank() }.joinToString("。"))
            }
        } else {
            updateNotif("自动播报运行中 · 队列 ${q.length()}")
        }
    }

    // ── 通知续命（契约 §3：notification_id / MessagingStyle / Action / 引用行 / PRIVATE）──

    private fun postItemNotif(o: JSONObject) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ALERT, "事项提醒", NotificationManager.IMPORTANCE_HIGH)
            ch.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            mgr.createNotificationChannel(ch)
        }
        val iid = o.getInt("id")
        val tid = if (o.has("thread_id") && !o.isNull("thread_id")) o.optInt("thread_id", -1) else -1
        val nid = if (tid > 0) 100000 + tid else 200000 + iid   // 同议题永远更新同一条通知

        val tctx = o.optJSONObject("thread_ctx")
        val generation = o.optInt("generation", 1)
        val topic = o.optString("thread_title", "")
        val title = if (topic.isNotBlank())
            "📌 $topic" + (if (generation > 1) "（第${generation}次跟进）" else "")
        else "📌 LifeCore 有新事项"

        val summary = o.optString("summary", "有新事项需要处理")

        // MessagingStyle：aiPerson 固定 "LifeCore"，通知内保留最近 3 条
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

        // 点击 → ThreadDetailActivity（extra thread_id）；无 thread 回主界面
        val contentPi = if (tid > 0)
            PendingIntent.getActivity(this, nid,
                Intent(this, ThreadDetailActivity::class.java).putExtra("thread_id", tid),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else
            PendingIntent.getActivity(this, nid, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val b = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(style)
            .setContentIntent(contentPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)       // heads-up
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // 锁屏 PRIVATE
            .setPublicVersion(                                     // 脱敏 public 副本
                NotificationCompat.Builder(this, CHANNEL_ALERT)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("LifeCore")
                    .setContentText("LifeCore 有新事项")
                    .build())

        // options 前 3 → Action 按钮；末位含"稍后"→ snooze 默认 30min
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
            b.addAction(0, label, pi)
        }
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

    // ── TTS / 前台通知 ──

    private fun speak(text: String) {
        // 网络拉取必须在后台线程（主线程 NetworkOnMainThreadException 会被静默吞掉）
        Thread {
            try {
                val (code, bytes) = Api.callBytes("POST", "/v2/tts",
                    body = JSONObject().put("text", "LifeCore 提醒：$text"))
                android.util.Log.i("LC-TTS", "tts fetch code=$code bytes=${bytes.size}")
                if (code != 200) return@Thread
                val f = File.createTempFile("lc_auto", ".mp3", cacheDir)
                f.writeBytes(bytes); f.deleteOnExit()
                handler.post {
                    player?.release()
                    player = MediaPlayer().apply {
                        setOnErrorListener { _, what, extra ->
                            android.util.Log.e("LC-TTS", "MediaPlayer error what=$what extra=$extra")
                            false
                        }
                        setOnCompletionListener { android.util.Log.i("LC-TTS", "play complete") }
                        setDataSource(f.absolutePath); prepare(); start()
                        android.util.Log.i("LC-TTS", "playback started")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LC-TTS", "speak failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun buildNotif(title: String, body: String): Notification {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "自动播报", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, PlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(body)
            .setContentIntent(pi)
            .addAction(0, "停止播报", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(body: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotif("LifeCore 自动播报", body))
    }

    override fun onDestroy() {
        running = false
        stopPolling()
        handler.removeCallbacks(reconnectRunnable)
        runCatching { ws?.close(1000, "service exit") }
        runCatching { wakeLock?.release() }
        handler.removeCallbacksAndMessages(null)
        player?.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "lc_playback"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "art.windgraham.lifecore.STOP"
        const val CHANNEL_ALERT = "lc_alert"
        const val ACTION_FEEDBACK = "art.windgraham.lifecore.NOTIFY_FEEDBACK"
        /** 断线重连退避：2s→5s→10s→30s→60s 封顶（契约 §2）。 */
        val BACKOFF_MS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)
        /** WS 断开超过 2 分钟才降级轮询；轮询间隔 60s。 */
        const val FALLBACK_AFTER_MS = 120_000L
        const val POLL_FALLBACK_MS = 60_000L
    }
}
