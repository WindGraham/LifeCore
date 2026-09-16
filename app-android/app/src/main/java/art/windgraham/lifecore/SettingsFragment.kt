package art.windgraham.lifecore

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/** 设置页：网关状态 / 设备身份 / 服务器地址 / 网关配置编辑（channel_overrides、require_mention、mcp_servers）/ 退出。 */
class SettingsFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_settings, c, false)
        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipe)
        val statusTv = v.findViewById<TextView>(R.id.gwStatus)
        val meTv = v.findViewById<TextView>(R.id.deviceInfo)
        val urlInput = v.findViewById<EditText>(R.id.baseUrlInput)

        urlInput.setText(Api.baseUrl)
        v.findViewById<MaterialButton>(R.id.btnSaveUrl).setOnClickListener {
            Api.baseUrl = urlInput.text.toString()
            toast("已保存")
        }

        v.findViewById<MaterialButton>(R.id.btnEditConfig).setOnClickListener { editConfig() }
        v.findViewById<MaterialButton>(R.id.btnEditMcp).setOnClickListener { editMcp() }
        v.findViewById<MaterialButton>(R.id.btnChOverride).setOnClickListener { channelOverrideForm() }
        v.findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            Api.logout()
            startActivity(Intent(context, PairActivity::class.java))
            activity?.finish()
        }

        fun load() = Api.bg(requireContext()) {
            val gw = try { Api.getJson("/v2/gateway/status") } catch (_: Exception) { JSONObject() }
            val me = try { Api.getJson("/v2/me") } catch (_: Exception) { JSONObject() }
            val plats = gw.optJSONObject("platforms")
            val sb = StringBuilder()
            plats?.keys()?.forEach { k -> sb.append("$k: ${plats.getJSONObject(k).optString("state")}\n") }
            Api.ui {
                statusTv.text = "网关：${gw.optString("state", "?")} · v${gw.optString("version", "?")}\n$sb\n指纹：${Api.fingerprint?.take(20)}…"
                meTv.text = "设备：${me.optString("device", "?")} · 注册于 ${me.optString("registered_at", "?").take(10)}"
                swipe.isRefreshing = false
            }
        }
        swipe.setOnRefreshListener { load() }; load()
        return v
    }

    /** 原始配置编辑：JSON 文本直编（能力全集，面向能看懂的用户）。 */
    private fun editConfig() {
        val ctx = requireContext()
        Api.bg(ctx) {
            val cfg = Api.getJson("/v2/admin/config").optJSONObject("config") ?: JSONObject()
            Api.ui {
                val et = EditText(ctx).apply {
                    setText(cfg.toString(2)); typeface = android.graphics.Typeface.MONOSPACE
                    minLines = 12; maxLines = 24
                }
                val sv = android.widget.ScrollView(ctx); sv.addView(et)
                AlertDialog.Builder(ctx).setTitle("网关配置（允许键: platforms/mcp_servers/display/gateway）").setView(sv)
                    .setPositiveButton("保存并重启网关") { _, _ ->
                        Api.bg(ctx) {
                            val newCfg = JSONObject(et.text.toString())
                            val r = Api.call("PUT", "/v2/admin/config",
                                body = JSONObject().put("config", newCfg))
                            if (r.first !in 200..299) throw Api.ApiException(r.first, r.second)
                            Api.ui { toast("已保存，网关重启中（约 10 秒）") }
                        }
                    }.setNegativeButton("取消", null).show()
            }
        }
    }

    /** channel_overrides 快速表单：按频道设 system_prompt（回复意向/人设），docs/04。 */
    private fun channelOverrideForm() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val platform = EditText(ctx).apply { hint = "平台名（webhook/telegram/...）"; setText("webhook") }
        val channel = EditText(ctx).apply { hint = "频道/会话 ID" }
        val prompt = EditText(ctx).apply { hint = "system_prompt（该频道的 agent 人设/回复规则）"; minLines = 4 }
        layout.addView(platform); layout.addView(channel); layout.addView(prompt)
        AlertDialog.Builder(ctx).setTitle("频道级 prompt 覆盖").setView(layout)
            .setPositiveButton("保存并重启") { _, _ ->
                Api.bg(ctx) {
                    val cur = Api.getJson("/v2/admin/config").optJSONObject("config") ?: JSONObject()
                    val plats = cur.optJSONObject("platforms") ?: JSONObject()
                    val p = plats.optJSONObject(platform.text.toString()) ?: JSONObject()
                    val ov = p.optJSONObject("channel_overrides") ?: JSONObject()
                    ov.put(channel.text.toString(), JSONObject()
                        .put("system_prompt", prompt.text.toString()))
                    p.put("channel_overrides", ov); plats.put(platform.text.toString(), p)
                    cur.put("platforms", plats)
                    val r = Api.call("PUT", "/v2/admin/config", body = JSONObject().put("config", cur))
                    if (r.first !in 200..299) throw Api.ApiException(r.first, r.second)
                    Api.ui { toast("已保存") }
                }
            }.setNegativeButton("取消", null).show()
    }

    /** MCP server 增删（agent 能力挂载，/reload-mcp 免重启。重启同样生效）。 */
    private fun editMcp() {
        val ctx = requireContext()
        Api.bg(ctx) {
            val servers = Api.getJson("/v2/admin/mcp").optJSONObject("mcp_servers") ?: JSONObject()
            val names = mutableListOf<String>()
            servers.keys().forEach { names.add(it) }
            Api.ui {
                val items = names.toTypedArray()
                AlertDialog.Builder(ctx).setTitle("已挂载 MCP（点选删除）")
                    .setItems(items) { _, which ->
                        val name = items[which]
                        AlertDialog.Builder(ctx).setTitle("删除 MCP $name？")
                            .setPositiveButton("删除") { _, _ ->
                                Api.bg(ctx) {
                                    val cur = Api.getJson("/v2/admin/config").optJSONObject("config") ?: JSONObject()
                                    val mcp = cur.optJSONObject("mcp_servers") ?: JSONObject()
                                    mcp.remove(name); cur.put("mcp_servers", mcp)
                                    Api.call("PUT", "/v2/admin/config", body = JSONObject().put("config", cur))
                                    Api.ui { toast("已删除 $name") }
                                }
                            }.setNegativeButton("取消", null).show()
                    }
                    .setNeutralButton("新增 MCP") { _, _ ->
                        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
                        val name = EditText(ctx).apply { hint = "名称" }
                        val url = EditText(ctx).apply { hint = "URL (Streamable HTTP)" }
                        layout.addView(name); layout.addView(url)
                        AlertDialog.Builder(ctx).setTitle("新增 MCP server").setView(layout)
                            .setPositiveButton("添加") { _, _ ->
                                Api.bg(ctx) {
                                    val cur = Api.getJson("/v2/admin/config").optJSONObject("config") ?: JSONObject()
                                    val mcp = cur.optJSONObject("mcp_servers") ?: JSONObject()
                                    mcp.put(name.text.toString(), JSONObject().put("url", url.text.toString()))
                                    cur.put("mcp_servers", mcp)
                                    val r = Api.call("PUT", "/v2/admin/config",
                                        body = JSONObject().put("config", cur))
                                    if (r.first !in 200..299) throw Api.ApiException(r.first, r.second)
                                    Api.ui { toast("已添加，网关重启后生效") }
                                }
                            }.setNegativeButton("取消", null).show()
                    }.show()
            }
        }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(context, s, android.widget.Toast.LENGTH_SHORT).show()
}
