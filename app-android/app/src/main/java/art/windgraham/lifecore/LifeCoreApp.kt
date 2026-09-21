package art.windgraham.lifecore

import android.app.Application

/** 自定义 Application：挂载 CrashHandler，保留入口供后续扩展。 */
class LifeCoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
