package art.windgraham.lifecore

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知 Action 按钮的回执接收器（docs/13 §6 反馈闭环；设计原则 7）。
 *
 * 支持的 action：
 *   - 👍 ack / 👎 ack_negative / ⏰ snooze30 / ✓ actioned / ✕ dismissed
 *   - undo（设计原则 9 撤销决策）
 *
 * 流程：解析 extras → 取消通知 nid → 后台线程 POST /v2/notify/items/{id}/feedback
 * （失败也吞掉——通知已收回；下次 WS 推送会带回新快照）。
 */
class FeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != PlayService.ACTION_FEEDBACK) return
        val id = intent.getIntExtra("item_id", -1)
        val action = intent.getStringExtra("fb_action") ?: return
        if (id < 0) return
        val nid = intent.getIntExtra("notif_id", -1)
        val minutes = intent.getIntExtra("minutes", 30)

        // 设计原则 7：通知 action 一律先关通知
        if (nid > 0) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(nid)
        }

        // 设计原则 4：IO 一律后台线程
        Thread {
            runCatching {
                Api.notifyFeedback(id, action, minutes = if (action.startsWith("snooze")) minutes else null)
            }
        }.start()
    }
}