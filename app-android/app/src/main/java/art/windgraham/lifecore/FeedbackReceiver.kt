package art.windgraham.lifecore

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知 Action 按钮的回执接收器（契约 §3）：点击即 POST feedback；
 *  "稍后"选后收起原通知，到期由服务端 resume 触发同 id 续命复活。 */
class FeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != PlayService.ACTION_FEEDBACK) return
        val id = intent.getIntExtra("item_id", -1)
        val action = intent.getStringExtra("fb_action") ?: return
        if (id < 0) return
        val nid = intent.getIntExtra("notif_id", -1)
        if (action == "snooze" && nid > 0) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(nid)
        }
        val minutes = intent.getIntExtra("minutes", 30)
        Thread {
            runCatching {
                Api.notifyFeedback(id, action, minutes = if (action == "snooze") minutes else null)
            }
        }.start()
    }
}
