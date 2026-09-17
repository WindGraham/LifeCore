package art.windgraham.lifecore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启自动播报（root Pixel 上配合电池白名单 = 事实常驻）。
 *
 * 设计原则 6：未配对 → 不自启（避免弹前台服务通知却什么都不能干）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        PairStore.init(ctx)
        if (!PairStore.isPaired()) return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(Intent(ctx, PlayService::class.java))
            } else {
                ctx.startService(Intent(ctx, PlayService::class.java))
            }
        }
    }
}