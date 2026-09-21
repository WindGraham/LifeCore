package art.windgraham.lifecore

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃兜底：捕获未处理异常 → 写 filesDir/crashes/ + 尝试 /sdcard/Download
 * → 启动时 PairActivity 检查并提示用户。
 *
 * 不替代系统 ANR/Crash 报告，只兜 Java 层的未捕获异常。
 */
object CrashHandler {

    private const val TAG = "LC/Crash"
    private const val PREFS = "lifecore_crash"
    private const val KEY_LAST_TS = "last_crash_ts"
    private const val KEY_LAST_FILE = "last_crash_file"

    private lateinit var appCtx: Context
    private var installed = false

    /** Application.onCreate 里调用一次 */
    fun install(app: Application) {
        if (installed) return
        installed = true
        appCtx = app.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                writeCrash(thread, e)
            } catch (t: Throwable) {
                Log.e(TAG, "写崩溃日志失败", t)
            }
            // 走系统默认（杀进程 + 弹系统对话框）
            prev?.uncaughtException(thread, e)
        }
        Log.i(TAG, "CrashHandler 已安装")
    }

    private fun writeCrash(thread: Thread, e: Throwable) {
        val ts = System.currentTimeMillis()
        val df = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "lifecore-crash-${df.format(Date(ts))}.log"
        val privDir = File(appCtx.filesDir, "crashes").apply { mkdirs() }
        val priv = File(privDir, name)
        val pub = File("/sdcard/Download", name)
        val body = buildString {
            appendLine("== LifeCore Crash Report ==")
            appendLine("time: ${df.format(Date(ts))} ($ts)")
            appendLine("thread: ${thread.name} (id=${thread.id})")
            appendLine("pkg: ${appCtx.packageName}")
            appendLine("ver: ${try {
                appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName
            } catch (_: Throwable) { "?" }}")
            appendLine()
            appendLine("--- stack ---")
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            append(sw.toString())
            appendLine()
            var c: Throwable? = e.cause
            var depth = 1
            while (c != null && depth < 6) {
                appendLine("--- cause #$depth ---")
                val sw2 = StringWriter()
                c.printStackTrace(PrintWriter(sw2))
                appendLine(sw2.toString())
                c = c.cause
                depth++
            }
        }
        priv.writeText(body, Charsets.UTF_8)
        runCatching {
            // /sdcard/Download 在 Android 10+ 需要 MediaStore；root 设备可直接写
            pub.writeText(body, Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "写 Download 失败（正常）", it) }
        val sp: SharedPreferences = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit()
            .putLong(KEY_LAST_TS, ts)
            .putString(KEY_LAST_FILE, name)
            .apply()
        Log.e(TAG, "崩溃已记录：$name", e)
    }

    data class LastCrash(val ts: Long, val fileName: String, val summary: String)

    fun peekLast(ctx: Context): LastCrash? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = sp.getLong(KEY_LAST_TS, 0L)
        if (ts <= 0) return null
        val name = sp.getString(KEY_LAST_FILE, null) ?: return null
        val f = File(File(ctx.filesDir, "crashes"), name)
        val summary = runCatching { f.readText().take(4000) }.getOrDefault("(读失败)")
        return LastCrash(ts, name, summary)
    }

    fun clearLast(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().clear().apply()
    }
}
