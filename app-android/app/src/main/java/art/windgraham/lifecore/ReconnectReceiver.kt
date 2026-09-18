package art.windgraham.lifecore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * WS 重连 alarm 触发器（设计原则 1/8 + #d6a8dfc7 修复）。
 *
 * 为什么需要：
 *   - Android 14 doze 模式下 App 进入 deep background，Handler 退避重连不可靠
 *     （fire-and-forget 16 小时 30+ 次断开没有任何 alarm/job 重新调度）。
 *   - AlarmManager.setExactAndAllowWhileIdle 是 doze 白名单唯一可靠唤醒方式。
 *
 * 流程：
 *   1. WS onFailure/onClosed → PlayService.scheduleReconnectAlarm() 注册 30s 后 alarm
 *   2. 到点系统拉起本 Receiver（即使 App 在 doze 也会触发）
 *   3. acquireWakeLock(10s) 短时持有保证 startForegroundService 完成
 *   4. startForegroundService(ACTION_RECONNECT) → PlayService.onStartCommand → connectWs()
 *   5. WS 连上 5s 后 cancel alarm（避免重复唤醒）
 */
class ReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i(TAG, "alarm fired, waking for reconnect")
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifecore:reconnect")
        wl.acquire(10_000)        // 10s 短时 wake lock，让 startForegroundService 完成
        try {
            // 触发 PlayService 重连（service 不在跑也会被拉起）
            val i = Intent(ctx, PlayService::class.java).apply {
                action = PlayService.ACTION_RECONNECT
            }
            ctx.startForegroundService(i)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService throw: ${e.message}")
        }
        // wl 会在 10s 后自动释放；不需要手动 release
    }

    companion object {
        private const val TAG = "LC-Reconnect"
    }
}
