package art.windgraham.lifecore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启自动播报（root Pixel 上配合电池白名单 = 事实常驻）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Api.init(ctx)
        if (Api.token == null) return
        runCatching { ctx.startForegroundService(Intent(ctx, PlayService::class.java)) }
    }
}
