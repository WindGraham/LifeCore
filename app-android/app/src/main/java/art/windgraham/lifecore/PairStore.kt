package art.windgraham.lifecore

import android.content.Context

/**
 * 全局配对状态门面（设计原则 5：所有 fragment 读这个，不各自配对）。
 *
 * 数据源：`Api` 的 SharedPreferences（"lifecore"）。
 * 当前是薄壳：把 `Api.token / fingerprint` 的读取/写入集中收口，
 * 未来切 DataStore/EncryptedSharedPreferences 时只改这一处。
 */
object PairStore {
    fun init(ctx: Context) = Api.init(ctx.applicationContext)

    /** 当前是否已配对（token 非空）。 */
    fun isPaired(): Boolean = !Api.token.isNullOrBlank()

    /** 服务器基础 URL（trimEnd 已保证）。 */
    var baseUrl: String
        get() = Api.baseUrl
        set(v) { Api.baseUrl = v }

    /** 设备 token；null = 未配对。 */
    val token: String? get() = Api.token

    /** 服务器公钥指纹（pair 时核对，防 MITM）。 */
    val fingerprint: String? get() = Api.fingerprint

    /** 写入新配对（来自 /v1/pair 返回）。 */
    fun savePair(deviceToken: String, fingerprint: String) {
        Api.savePair(deviceToken, fingerprint)
    }

    /** 清凭证；保留 baseUrl 与单调 spoken_id。 */
    fun clear() = Api.logout()
}