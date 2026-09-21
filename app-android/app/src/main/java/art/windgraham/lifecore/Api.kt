package art.windgraham.lifecore

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * BFF client (App only talks to BFF /v2). See docs/06.
 */
object Api {
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
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

    var lastSpokenId: Int
        get() = prefs.getInt("last_spoken_id", 0)
        set(v) { prefs.edit().putInt("last_spoken_id", v).apply() }

    fun savePair(deviceToken: String, fp: String) {
        token = deviceToken
        fingerprint = fp
    }

    fun logout() {
        prefs.edit()
            .remove("device_token")
            .remove("fingerprint")
            .apply()
    }

    fun wsUrl(path: String): String =
        baseUrl.replaceFirst(Regex("^http"), "ws") + path

    fun me(): JSONObject = getJson("/v2/me")
    fun gatewayCapabilities(): JSONObject = getJson("/v2/gateway/capabilities")
    fun stateBlock(): JSONObject = getJson("/v2/state-block")
    fun notifyActive(): JSONObject = getJson("/v2/notify/active")
    fun notifyThreads(): JSONObject = getJson("/v2/notify/threads")
    fun notifyThreadItems(tid: Int): JSONObject = getJson("/v2/notify/threads/$tid/items")

    /** 多源整合时间线（channel 原始 + AI 回复 + 用户决议 + 续报）。 */
    fun threadConversation(tid: Int): JSONObject = getJson("/v2/threads/$tid/conversation")

    /**
     * notify_items 全 state 视图（P0-S1 / P1-S4）。
     *
     * @param state           awaiting_feedback / queued / logged 等；不传 = 全状态
     * @param addressedToOwner 1=只返回主人专属（addressed_to_owner=true）；不传 = 不过滤
     * @param priority        high / normal / all
     * @param limit / offset  分页（默认 50 / 0）
     */
    fun notifyAll(state: String? = null, addressedToOwner: Boolean? = null,
                  priority: String? = null, limit: Int = 50, offset: Int = 0): JSONObject {
        val qs = mutableListOf<String>()
        state?.takeIf { it.isNotBlank() }?.let { qs.add("state=" + java.net.URLEncoder.encode(it, "UTF-8")) }
        addressedToOwner?.let { qs.add("addressed_to_owner=" + (if (it) "1" else "0")) }
        priority?.takeIf { it.isNotBlank() && it != "all" }?.let { qs.add("priority=" + java.net.URLEncoder.encode(it, "UTF-8")) }
        qs.add("limit=" + limit)
        qs.add("offset=" + offset)
        return getJson("/v2/notify/all?" + qs.joinToString("&"))
    }

    /**
     * 事件流（P0-S2）。
     *
     * @param channelId 通道过滤；不传 = 全通道
     * @param sinceSeq  从该 seq 之后拉（增量刷新）
     * @param limit     默认 100
     */
    fun events(channelId: String? = null, sinceSeq: Long? = null, limit: Int = 100): JSONObject {
        val qs = mutableListOf<String>()
        channelId?.takeIf { it.isNotBlank() }?.let { qs.add("channel_id=" + java.net.URLEncoder.encode(it, "UTF-8")) }
        sinceSeq?.takeIf { it > 0 }?.let { qs.add("since=" + it) }
        qs.add("limit=" + minOf(limit, 1000))
        return getJson("/v2/events?" + qs.joinToString("&"))
    }

    /**
     * 线程完整时间线（P1-S3）—— 复用 /v2/notify/threads/{tid}/items（已存在），
     * 包成更明确的命名以供 ThreadDetailActivity 在新增的"查看 timeline"按钮处调用。
     */
    fun threadTimeline(threadId: Long): JSONObject = getJson("/v2/notify/threads/$threadId/items")

    /**
     * 通道健康度（P1-S5）。返回 bridge_status 的精简版：
     *   { online, last_seen, last_command_at, name, detail, server_time }
     */
    fun bridgeHealth(): JSONObject = getJson("/v2/bridge/status")

    /**
     * 真 digest（P1-S6）—— docs/12 §2.5 卡片 A 服务端聚合；
     * 失败时返回空对象（TodayFragment 兜底用旧 threads 视图）。
     */
    fun digestToday(): JSONObject = getJson("/v2/digest/today")

    fun notifyFeedback(id: Int, action: String, minutes: Int? = null, until: Long? = null): JSONObject {
        val b = JSONObject().put("action", action)
        minutes?.let { b.put("minutes", it) }
        until?.let { b.put("until", it) }
        return postJson("/v2/notify/items/$id/feedback", b)
    }

    fun sessions(q: String = ""): JSONObject =
        getJson("/v2/sessions" + if (q.isNotBlank()) "?q=" + q else "")

    fun sessionCreate(title: String): JSONObject =
        postJson("/v2/sessions", JSONObject().put("title", title))

    fun sessionMessages(sid: String): JSONObject = getJson("/v2/sessions/$sid/messages")

    fun sessionChat(sid: String, message: String): JSONObject =
        postJson("/v2/sessions/$sid/chat", JSONObject().put("message", message))

    fun sessionRename(sid: String, title: String): JSONObject =
        callJson("PATCH", "/v2/sessions/$sid", JSONObject().put("title", title))

    fun sessionDelete(sid: String) {
        call("DELETE", "/v2/sessions/$sid")
    }

    fun jobs(): JSONObject = getJson("/v2/jobs")

    fun jobCreate(name: String, schedule: String, prompt: String): JSONObject =
        postJson("/v2/jobs", JSONObject()
            .put("name", name).put("schedule", schedule).put("prompt", prompt))

    fun jobAction(jid: String, action: String): JSONObject =
        postJson("/v2/jobs/$jid/$action", JSONObject())

    fun jobDelete(jid: String) {
        call("DELETE", "/v2/jobs/$jid")
    }

    fun channels(): JSONObject = getJson("/v1/channels")

    fun channelCreate(name: String, archetype: String, uplink: String, mode: String): JSONObject =
        postJson("/v1/channels", JSONObject()
            .put("name", name)
            .put("archetype", archetype)
            .put("uplink_level", uplink)
            .put("report_policy", JSONObject().put("mode", mode)))

    fun channelDelete(channelId: String) {
        call("DELETE", "/v1/channels/$channelId")
    }

    fun gatewayStatus(): JSONObject = getJson("/v2/gateway/status")
    fun adminConfig(): JSONObject = getJson("/v2/admin/config")

    fun adminConfigPut(config: JSONObject): JSONObject =
        callJson("PUT", "/v2/admin/config", JSONObject().put("config", config))

    fun adminMcp(): JSONObject = getJson("/v2/admin/mcp")
    fun voices(): JSONObject = getJson("/v2/voices")

    fun fmtTs(unix: Double?): String {
        if (unix == null || unix <= 0) return ""
        return try {
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochSecond(unix.toLong()))
        } catch (_: Exception) { "" }
    }

    fun themeColor(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

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

    fun parseIso(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0
        return try {
            val fixed = when {
                iso.endsWith("Z") -> iso
                iso.matches(Regex(".*[+-]\\d\\d:?\\d\\d$")) -> iso
                else -> iso + "Z"
            }
            java.time.OffsetDateTime.parse(fixed).toInstant().epochSecond
        } catch (_: Exception) { 0 }
    }

    /** sync call; returns (code, body). MUST be on background thread. */
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
        try {
            client.newCall(b.build()).execute().use { resp ->
                return resp.code to (resp.body?.string() ?: "")
            }
        } catch (e: IOException) {
            throw ApiException(0, "network: ${e.message ?: "unreachable"}")
        }
    }

    fun callBytes(method: String, path: String, body: JSONObject? = null,
                  contentType: String = "application/json; charset=utf-8"): Pair<Int, ByteArray> {
        val reqBody = body?.toString()?.toRequestBody(contentType.toMediaType())
        val b = Request.Builder().url(baseUrl + path).method(method, reqBody)
        if (token != null) b.header("Authorization", "Bearer $token")
        try {
            client.newCall(b.build()).execute().use { resp ->
                return resp.code to (resp.body?.bytes() ?: ByteArray(0))
            }
        } catch (e: IOException) {
            throw ApiException(0, "network: ${e.message ?: "unreachable"}")
        }
    }

    fun callStream(method: String, path: String, body: JSONObject?): okhttp3.Response {
        val url = baseUrl + path
        val rb = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val b = Request.Builder().url(url).method(method, rb)
        if (token != null) b.header("Authorization", "Bearer $token")
        b.header("Accept", "text/event-stream")
        return client.newCall(b.build()).execute()
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

    fun callJson(method: String, path: String, body: JSONObject? = null): JSONObject {
        val (code, text) = call(method, path, body = body)
        if (code !in 200..299) throw ApiException(code, text)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    class ApiException(val code: Int, val body: String) : Exception("HTTP $code")

    fun errText(e: Exception): String = when (e) {
        is ApiException -> if (e.code == 0) e.body else "HTTP ${e.code}: ${e.body.take(200)}"
        else -> e.javaClass.simpleName + ": " + (e.message ?: "")
    }

    fun bg(ctx: Context, work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                main.post { Toast.makeText(ctx, errText(e), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    fun bgSilent(work: () -> Unit, onError: ((Exception) -> Unit)? = null) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                onError?.invoke(e)
            }
        }.start()
    }

    fun ui(block: () -> Unit) = main.post(block)

    fun arr(o: JSONObject, key: String): JSONArray = o.optJSONArray(key) ?: JSONArray()
    fun jarr(text: String): JSONArray =
        if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray()
}