package art.windgraham.lifecore.ui.channels

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import art.windgraham.lifecore.Api
import art.windgraham.lifecore.PairStore
import art.windgraham.lifecore.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject

/** 通道页：webhook 通道的注册/查看/删除（registrar，agent.md §2.1）。 */
class ChannelsFragment : Fragment() {

    private val rows = mutableListOf<JSONObject>()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_list, c, false)
        val rv = v.findViewById<RecyclerView>(R.id.listRv)
        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipe)
        val fab = v.findViewById<FloatingActionButton>(R.id.fab)

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = rows[pos]
                h.itemView.findViewById<TextView>(R.id.chName).text = o.getString("name")
                h.itemView.findViewById<TextView>(R.id.chMeta).text =
                    "${o.getString("archetype").uppercase()} · ${o.getString("uplink_level")} · " +
                    Api.fmtTime(o.optString("created_at", ""))
                h.itemView.setOnLongClickListener {
                    AlertDialog.Builder(context).setTitle("删除通道 ${o.getString("name")}？")
                        .setPositiveButton("删除") { _, _ ->
                            Thread {
                                try { Api.channelDelete(o.getString("channel_id")) }
                                catch (e: Exception) { main.post { Toast.makeText(context, Api.errText(e), Toast.LENGTH_LONG).show() } }
                                main.post { load() }
                            }.start()
                        }.setNegativeButton("取消", null).show()
                    true
                }
            }
        }
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter

        fab.setOnClickListener { showCreateDialog() }
        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    private fun showCreateDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val name = EditText(ctx).apply { hint = "通道名（如 wechat-monitor）" }
        val arch = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("message","metric","file","task","calendar","alert","result"))
        }
        val uplink = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("AB","A","ABC"))
        }
        val mode = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("notify","digest","alert","silent"))
        }
        layout.addView(name); layout.addView(arch); layout.addView(uplink); layout.addView(mode)
        AlertDialog.Builder(ctx).setTitle("注册通道").setView(layout)
            .setPositiveButton("注册") { _, _ ->
                Thread {
                    try {
                        val r = Api.channelCreate(
                            name = name.text.toString().trim(),
                            archetype = arch.selectedItem.toString(),
                            uplink = uplink.selectedItem.toString(),
                            mode = mode.selectedItem.toString()
                        )
                        val info = "ingest_url:\n${r.getString("ingest_url")}\n\nsecret:\n${r.getString("secret")}"
                        main.post {
                            AlertDialog.Builder(ctx).setTitle("通道已创建（点击外部关闭，请立即保存）")
                                .setMessage(info)
                                .setPositiveButton("复制") { _, _ ->
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("channel", info))
                                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                                }.show()
                            load()
                        }
                    } catch (e: Exception) {
                        main.post { Toast.makeText(ctx, Api.errText(e), Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }.setNegativeButton("取消", null).show()
    }

    private fun load() {
        if (!PairStore.isPaired()) return
        Thread {
            try {
                val r = Api.channels()
                val got = Api.arr(r, "channels")
                main.post {
                    rows.clear()
                    for (k in 0 until got.length()) rows.add(got.getJSONObject(k))
                    val rv = view?.findViewById<RecyclerView>(R.id.listRv)
                    rv?.adapter?.notifyDataSetChanged()
                    view?.findViewById<SwipeRefreshLayout>(R.id.swipe)?.isRefreshing = false
                }
            } catch (e: Exception) {
                main.post {
                    Toast.makeText(context, Api.errText(e), Toast.LENGTH_LONG).show()
                    view?.findViewById<SwipeRefreshLayout>(R.id.swipe)?.isRefreshing = false
                }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); load() }
}
