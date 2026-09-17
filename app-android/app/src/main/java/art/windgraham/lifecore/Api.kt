package art.windgraham.lifecore

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** BFF 客户端：App 只跟 BFF 说话（docs/06 接口原则），token/baseUrl 存本地。 */
object Api {
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("lifecore", Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = prefs.getString("base_url", "https://windgraham.art")!!.trimEnd('/')
        set(v) = prefs.edit().putString("base_url", v.trimEnd('/')).apply()

    var token: String?
        get() = prefs.getString("device_token", null)
        private set(v) = prefs.edit().putString("device_token", v).apply()

    var fingerprint: String?
        get() = prefs.getString("fingerprint", null)
        private set(v) = prefs.edit().putString("fingerprint", v).apply()

    fun savePair(deviceToken: String, fp: String) {
        token = deviceToken; fingerprint = fp
    }

    fun logout() = prefs.edit().remove("device_token").apply()

    /** ISO 时间 → "MM-dd HH:mm"；解析失败截断原样返回。 */
    fun fmtTime(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val fixed = when {
                iso.endsWith("Z") -> iso
                iso.matches(Regex(".*[+-]\\d\\d:?\\d\\d$")) -> iso
                else -> iso + "Z"
            }
            val p = java.time.OffsetDateTime.parse(fixed).toInstant()
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault()).format(p)
        } catch (_: Exception) {
            iso.take(16)
        }
    }

    /** 同步调用；必须在后台线程。返回 (httpCode, bodyString)。 */
    fun call(method: String, path: String, body: JSONObject? = null, raw: ByteArray? = null,
             contentType: String? = null, withAuth: Boolean = true): Pair<Int, String> {
        val url = baseUrl + path
        val reqBody = when {
            raw != null -> raw.toRequestBody((contentType ?: "application/octet-stream").toMediaType())
            body != null -> body.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            else -> null
        }
        val b = Request.Builder().url(url).method(method, reqBody)
        if (withAuth && token != null) b.header("Authorization", "Bearer $token")
        val resp = client.newCall(b.build()).execute()
        return resp.code to (resp.body?.string() ?: "")
    }

    /** 二进制安全调用（TTS 音频等）。 */
    fun callBytes(method: String, path: String, body: JSONObject? = null): Pair<Int, ByteArray> {
        val reqBody = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val b = Request.Builder().url(baseUrl + path).method(method, reqBody)
        if (token != null) b.header("Authorization", "Bearer $token")
        client.newCall(b.build()).execute().use { resp ->
            return resp.code to (resp.body?.bytes() ?: ByteArray(0))
        }
    }

    fun getJson(path: String): JSONObject {
        val (code, text) = call("GET", path)
        if (code !in 200..299) throw ApiException(code, text)
        return JSONObject(text)
    }

    fun postJson(path: String, body: JSONObject? = null): JSONObject {
        val (code, text) = call("POST", path, body = body ?: JSONObject())
        if (code !in 200..299) throw ApiException(code, text)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    class ApiException(val code: Int, val body: String) : Exception("HTTP $code")

    fun errText(e: Exception): String = when (e) {
        is ApiException -> "HTTP ${e.code}: ${e.body.take(200)}"
        else -> e.javaClass.simpleName + ": " + (e.message ?: "")
    }

    /** 后台执行 + 主线程回调；自动 toast 错误。 */
    fun bg(ctx: Context, work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                main.post { Toast.makeText(ctx, errText(e), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    fun ui(block: () -> Unit) = main.post(block)

    // ── 防御性 JSON 小工具 ──
    fun arr(o: JSONObject, key: String): JSONArray = o.optJSONArray(key) ?: JSONArray()
    fun jarr(text: String): JSONArray =
        if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray()
}
