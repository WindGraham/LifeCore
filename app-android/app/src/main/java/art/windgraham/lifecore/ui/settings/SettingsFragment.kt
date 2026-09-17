package art.windgraham.lifecore.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import art.windgraham.lifecore.Api
import art.windgraham.lifecore.PairActivity
import art.windgraham.lifecore.PairStore
import art.windgraham.lifecore.R
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * 设置页：网关状态 / 设备身份 / 服务器地址 / TTS 试听 / 退出。
 *
 * 设计原则 6：未配对 → 显示配对引导视图。
 * 设计原则 1：单一源 PairStore；这里只读不写。
 */
class SettingsFragment : Fragment() {

    private val main = Handler(Looper.getMainLooper())
    private var statusTv: TextView? = null
    private var meTv: TextView? = null
    private var swipe: SwipeRefreshLayout? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_settings, c, false)
        swipe = v.findViewById(R.id.swipe)
        statusTv = v.findViewById(R.id.gwStatus)
        meTv = v.findViewById(R.id.deviceInfo)
        val urlInput = v.findViewById<EditText>(R.id.baseUrlInput)
        val btnTts = v.findViewById<MaterialButton>(R.id.btnTtsTest)
        val btnLogout = v.findViewById<MaterialButton>(R.id.btnLogout)

        if (!PairStore.isPaired()) {
            // 未配对 → 显示引导视图
            v.findViewById<View>(R.id.pairedArea).visibility = View.GONE
            v.findViewById<View>(R.id.unpairedArea).visibility = View.VISIBLE
            v.findViewById<MaterialButton>(R.id.btnGoPair).setOnClickListener {
                startActivity(Intent(context, PairActivity::class.java))
                activity?.finish()
            }
            return v
        }

        v.findViewById<View>(R.id.unpairedArea).visibility = View.GONE

        urlInput.setText(Api.baseUrl)
        v.findViewById<MaterialButton>(R.id.btnSaveUrl).setOnClickListener {
            PairStore.baseUrl = urlInput.text.toString()
            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
        }

        v.findViewById<MaterialButton>(R.id.btnEditConfig).setOnClickListener { editConfig() }
        v.findViewById<MaterialButton>(R.id.btnEditMcp).setOnClickListener { editMcp() }
        v.findViewById<MaterialButton>(R.id.btnChOverride).setOnClickListener { channelOverrideForm() }

        btnTts.setOnClickListener {
            val text = "LifeCore 语音测试，听到这段话说明 TTS 通道正常。"
            Thread {
                try {
                    val (code, bytes) = Api.callBytes("POST", "/v2/tts",
                        body = JSONObject().put("text", text))
                    if (code != 200) throw Api.ApiException(code, String(bytes, Charsets.UTF_8))
                    val f = java.io.File.createTempFile("lc_tts_test", ".mp3", requireContext().cacheDir)
                    f.writeBytes(bytes)
                    main.post {
                        val mp = android.media.MediaPlayer().apply {
                            setDataSource(f.absolutePath); prepare(); start()
                        }
                        Toast.makeText(context, "播放中…", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    main.post { Toast.makeText(context, Api.errText(e), Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        btnLogout.setOnClickListener {
            AlertDialog.Builder(context).setTitle("解除配对？")
                .setMessage("将清除本地凭证。需要重新扫描二维码才能继续使用。")
                .setPositiveButton("解除配对") { _, _ ->
                    PairStore.clear()
                    startActivity(Intent(context, PairActivity::class.java))
                    activity?.finish()
                }
                .setNegativeButton("取消", null).show()
        }

        swipe?.setOnRefreshListener { load() }
        load()
        return v
    }

    private fun editConfig() {
        val ctx = requireContext()
        Thread {
            try {
                val cfg = Api.adminConfig().optJSONObject("config") ?: JSONObject()
                main.post {
                    val et = EditText(ctx).apply {
                        setText(cfg.toString(2)); typeface = android.graphics.Typeface.MONOSPACE
                        minLines = 12; maxLines = 24
                    }
                    val sv = android.widget.ScrollView(ctx); sv.addView(et)
                    AlertDialog.Builder(ctx).setTitle("网关配置（允许键: platforms/mcp_servers/display/gateway）")
                        .setView(sv)
                        .setPositiveButton("保存并重启网关") { _, _ ->
                            Thread {
                                try {
                                    val newCfg = JSONObject(et.text.toString())
                                    Api.adminConfigPut(newCfg)
                                    main.post { Toast.makeText(ctx, "已保存，网关重启中（约 10 秒）", Toast.LENGTH_LONG).show() }
                                } catch (e: Exception) {
                                    main.post { Toast.makeText(ctx, Api.errText(e), Toast.LENGTH_LONG).show() }
                                }
                            }.start()
                        }.setNegativeButton("取消", null).show()
                }
            } catch (e: Exception) {
                main.post { Toast.makeText(ctx, Api.errText(e), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun channelOverrideForm() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val platform = EditText(ctx).apply { hint = "平台名（webhook/telegram/...）"; setText("webhook") }
        val channel = EditText(ctx).apply { hint = "频道/会话 ID" }
        val prompt = EditText(ctx).apply { hint = "system_prompt（该频道的 agent 人设/回复规则）"; minLines = 4 }
        layout.addView(platform); layout.addView(channel); layout.addView(prompt)
        AlertDialog.Builder(ctx).setTitle("频道级 prompt 覆盖").setView(layout)
            .setPositiveButton("保存并重启") { _, _ ->
                Thread {
                    try {
                        val cur = Api.adminConfig().optJSONObject("config") ?: JSONObject()
                        val plats = cur.optJSONObject("platforms") ?: JSONObject()
                        val p = plats.optJSONObject(platform.text.toString()) ?: JSONObject()
                        val ov = p.optJSONObject("channel_overrides") ?: JSONObject()
                        ov.put(channel.text.toString(), JSONObject()
                            .put("system_prompt", prompt.text.toString()))
                        p.put("channel_overrides", ov); plats.put(platform.text.toString(), p)
                        cur.put("platforms", plats)
                        Api.adminConfigPut(cur)
                        main.post { Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) {
                        main.post { Toast.makeText(ctx, Api.errText(e), Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }.setNegativeButton("取消", null).show()
    }

    private fun editMcp() {
        val ctx = requireContext()
        Thread {
            try {
                val servers = Api.adminMcp().optJSONObject("mcp_servers") ?: JSONObject()
                val names = mutableListOf<String>()
                servers.keys().forEach { names.add(it) }
                main.post {
                    val items = names.toTypedArray()
                    AlertDialog.Builder(ctx).setTitle("已挂载 MCP（点选删除）")
                        .setItems(items) { _, which ->
                            val name = items[which]
                            AlertDialog.Builder(ctx).setTitle("删除 MCP $name？")
                                .setPositiveButton("删除") { _, _ ->
                                    Thread {
                                        try {
                                            val cur = Api.adminConfig().optJSONObject("config") ?: JSONObject()
                                            val mcp = cur.optJSONObject("mcp_servers") ?: JSONObject()
                                            mcp.remove(name); cur.put("mcp_servers", mcp)
                                            Api.adminConfigPut(cur)
                                            main.post { Toast.makeText(ctx, "已删除 $name", Toast.LENGTH_SHORT).show() }
                                        } catch (e: Exception) {
                                            main.post { Toast.makeText(ctx, Api.errText(e), Toast.LENGTH_LONG).show() }
                                        }
                                    }.start()
                                }.setNegativeButton("取消", null).show()
                        }
                        .setNeutralButton("新增 MCP") { _, _ ->
                            val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
                            val name = EditText(ctx).apply { hint = "名称" }
                            val url = EditText(ctx).apply { hint = "URL (Streamable HTTP)" }
                            layout.addView(name); layout.addView(url)
                            AlertDialog.Builder(ctx).setTitle("新增 MCP server").setView(layout)
                                .setPositiveButton("添加") { _, _ ->
                                    Thread {
                                        try {
                                            val cur = Api.adminConfig().optJSONObject("config") ?: JSONObject()
                                            val mcp = cur.optJSONObject("mcp_servers") ?: JSONObject()
                                            mcp.put(name.text.toString(), JSONObject().put("url", url.text.toString()))
                                            cur.put("mcp_servers", mcp)
                                            Api.adminConfigPut(cur)
                                            main.post { Toast.makeText(ctx, "已添加，网关重启后生效", Toast.LENGTH_LONG).show() }
                                        } catch (e: Exception) {
                                            main.post { Toast.makeText(ctx, Api.errText(e), Toast.LENGTH_LONG).show() }
                                        }
                                    }.start()
                                }.setNegativeButton("取消", null).show()
                        }.show()
                }
            } catch (e: Exception) {
                main.post { Toast.makeText(ctx, Api.errText(e), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun load() {
        if (!PairStore.isPaired()) return
        Thread {
            try {
                val gw = Api.gatewayStatus()
                val me = Api.me()
                val plats = gw.optJSONObject("platforms")
                val sb = StringBuilder()
                plats?.keys()?.forEach { k ->
                    sb.append("$k: ${plats.getJSONObject(k).optString("state")}\n")
                }
                main.post {
                    statusTv?.text = "网关：${gw.optString("state", "?")} · v${gw.optString("version", "?")}\n$sb\n指纹：${Api.fingerprint?.take(20)}…"
                    meTv?.text = "设备：${me.optString("device", "?")} · 注册于 ${me.optString("registered_at", "?").take(10)}"
                    swipe?.isRefreshing = false
                }
            } catch (e: Exception) {
                main.post {
                    Toast.makeText(context, Api.errText(e), Toast.LENGTH_LONG).show()
                    swipe?.isRefreshing = false
                }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); load() }
}
