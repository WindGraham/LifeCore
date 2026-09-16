package art.windgraham.lifecore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File

/** 自动播报服务：前台服务轮询 notify 队列，新待播报条目自动 TTS 播放。
 *  覆盖"App 存活期自动接收语音并播放"；被杀后推送唤醒属下一阶段（保活三通道）。 */
class PlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var lastPlayedId = -1
    private var lastQueueHash = -1
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        running = true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifecore:playback").apply {
            acquire(24L * 60 * 60 * 1000)   // 24h 上限，服务活着就持续持有
        }
        startForeground(NOTIF_ID, buildNotif("自动播报运行中", "等待新汇报…"))
        lastPlayedId = -1
        poll()
        return START_STICKY
    }

    private fun poll() {
        if (!running) return
        Thread {
            try {
                val r = Api.getJson("/v2/notify/active")
                val active = r.optJSONObject("active")
                val q = Api.arr(r, "queue")
                val qHash = (0 until q.length()).joinToString { q.getJSONObject(it).getInt("id").toString() }.hashCode()
                if (active != null) {
                    val id = active.getInt("id")
                    val summary = active.optString("summary", "有新事项需要处理")
                    updateNotif("待处理：${summary.take(40)}")
                    if (id != lastPlayedId) {          // 新条目 → 自动播报一次
                        lastPlayedId = id
                        if (active.optString("priority") == "high" || active.optString("priority") == "alert")
                            alertNotif(summary)
                        speak(summary)
                    }
                } else {
                    updateNotif("自动播报运行中 · 队列 ${q.length()}")
                    lastPlayedId = -1
                }
                lastQueueHash = qHash
            } catch (_: Exception) { /* 网络抖动：下轮再来 */ }
            val pm2 = getSystemService(POWER_SERVICE) as PowerManager
            handler.postDelayed({ poll() }, if (pm2.isInteractive) POLL_MS else POLL_MS_SCREEN_OFF)
        }.start()
    }

    private fun speak(text: String) {
        try {
            val (code, bytes) = Api.callBytes("POST", "/v2/tts",
                body = JSONObject().put("text", "LifeCore 提醒：$text"))
            if (code != 200) return
            val f = File.createTempFile("lc_auto", ".mp3", cacheDir)
            f.writeBytes(bytes); f.deleteOnExit()
            handler.post {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(f.absolutePath); prepare(); start()
                }
            }
        } catch (_: Exception) { }
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

    /** alert 级：全屏意图通知（锁屏弹出；Android 14+ 需 root 授予 USE_FULL_SCREEN_INTENT）。 */
    private fun alertNotif(body: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "紧急提醒", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("LifeCore 紧急提醒").setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIF_ALERT_ID, n)
    }

    private fun updateNotif(body: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotif("LifeCore 自动播报", body))
    }

    override fun onDestroy() {
        running = false
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
        const val NOTIF_ALERT_ID = 43
        const val POLL_MS = 15_000L
        const val POLL_MS_SCREEN_OFF = 30_000L
    }
}
