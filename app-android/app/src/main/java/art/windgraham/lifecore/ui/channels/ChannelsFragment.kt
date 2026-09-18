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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject

/**
 * 通道页（P1-S5 健康度 + 长按弹窗）。
 *
 * - 每个 channel 卡片右上：health chip（active / stale / silent / revoked）
 * - 长按 channel 行 → 弹 BottomSheetDialog 显示该 channel 最近 5 条 events
 */
class ChannelsFragment : Fragment() {

    private val rows = mutableListOf<JSONObject>()
    private val eventsByChannel = mutableMapOf<String, List<JSONObject>>()
    private val channelHealth = mutableMapOf<String, String>()
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
                val cid = o.getString("channel_id")
                h.itemView.findViewById<TextView>(R.id.chName).text = o.getString("name")
                h.itemView.findViewById<TextView>(R.id.chMeta).text =
                    "${o.getString("archetype").uppercase()} · ${o.getString("uplink_level")} · " +
                    Api.fmtTime(o.optString("created_at", ""))
                val chip = h.itemView.findViewById<Chip>(R.id.chHealth)
                chip.text = healthLabel(channelHealth[cid])
                val lastEv = h.itemView.findViewById<TextView>(R.id.chLastEvent)
                val events = eventsByChannel[cid]
                lastEv.text = if (events.isNullOrEmpty()) "最近事件：无"
                else "最近事件：${Api.fmtTime(events.first().optString("at", ""))}"
                h.itemView.setOnLongClickListener {
                    showChannelEventsSheet(cid, o.getString("name"))
                    true
                }
                // 短按 = 删除（保留原行为）
                h.itemView.setOnClickListener {
                    AlertDialog.Builder(context).setTitle("删除通道 ${o.getString("name")}？")
                        .setPositiveButton("删除") { _, _ ->
                            Thread {
                                try { Api.channelDelete(cid) }
                                catch (e: Exception) { main.post { Toast.makeText(context, Api.errText(e), Toast.LENGTH_LONG).show() } }
                                main.post { load() }
                            }.start()
                        }.setNegativeButton("取消", null).show()
                }
            }
        }
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter

        fab.setOnClickListener { showCreateDialog() }
        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    /** P1-S5：根据 bridge_status + events 状态判定 health。 */
    private fun healthLabel(s: String?): String = when (s) {
        "active" -> getString(R.string.health_active)
        "stale" -> getString(R.string.health_stale)
        "silent" -> getString(R.string.health_silent)
        "revoked" -> getString(R.string.health_revoked)
        else -> "—"
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

    /** P1-S5：长按 channel → 弹 BottomSheet 显示该 channel 最近 5 条 events。 */
    private fun showChannelEventsSheet(channelId: String, channelName: String) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_channel_events, null, false)
        val bsTitle = view.findViewById<TextView>(R.id.bsTitle)
        val bsSub = view.findViewById<TextView>(R.id.bsSubtitle)
        val bsRv = view.findViewById<RecyclerView>(R.id.bsRv)
        val bsEmpty = view.findViewById<TextView>(R.id.bsEmpty)
        bsTitle.text = "通道 $channelName"
        bsSub.text = "channel_id: $channelId"
        val events = eventsByChannel[channelId] ?: emptyList()
        if (events.isEmpty()) bsEmpty.visibility = View.VISIBLE else bsEmpty.visibility = View.GONE
        bsRv.layoutManager = LinearLayoutManager(ctx)
        bsRv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = events.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context)
                    .inflate(R.layout.item_event, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = events[pos]
                h.itemView.findViewById<TextView>(R.id.evSeq).text = "#" + o.optLong("seq", 0L)
                h.itemView.findViewById<TextView>(R.id.evChannel).text = o.optString("channel_id", "")
                h.itemView.findViewById<TextView>(R.id.evTime).text = Api.fmtTime(o.optString("at", ""))
                val p = o.optJSONObject("payload")
                val s = if (p != null) p.optString("summary", p.optString("level", p.toString())) else "(空)"
                h.itemView.findViewById<TextView>(R.id.evPayload).text = s.take(160)
            }
        }
        val dlg = BottomSheetDialog(ctx)
        dlg.setContentView(view)
        dlg.show()
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
                }
                // 拉 bridge 健康度 + 各 channel 的最近 events（≤5 条） + 计算 health
                runCatching {
                    val br = Api.bridgeHealth()
                    val brOnline = br.optBoolean("online", false)
                    val brName = br.optString("name", "")
                    if (!brOnline) channelHealth["_bridge"] = "silent"
                    else channelHealth["_bridge"] = "active"
                }
                // 给每个 channel 拉一次 events
                val tmpEvents = mutableMapOf<String, List<JSONObject>>()
                val tmpHealth = mutableMapOf<String, String>()
                for (ch in rows) {
                    val cid = ch.optString("channel_id", "")
                    if (cid.isBlank()) continue
                    try {
                        val ev = Api.events(channelId = cid, sinceSeq = null, limit = 5)
                        val arr = Api.arr(ev, "events")
                        val list = mutableListOf<JSONObject>()
                        for (k in 0 until arr.length()) list.add(arr.getJSONObject(k))
                        tmpEvents[cid] = list
                        // health 计算：有 recent events (5min 内) → active；有 events 但 >5min → stale；没有 → silent
                        val lastAt = list.firstOrNull()?.optString("at", "")
                        tmpHealth[cid] = if (list.isEmpty()) "silent"
                        else {
                            val parsed = Api.parseIso(lastAt)
                            val age = System.currentTimeMillis() / 1000 - parsed
                            if (age in 0..300) "active"
                            else if (age <= 3600) "stale"
                            else "silent"
                        }
                    } catch (_: Exception) {
                        tmpEvents[cid] = emptyList()
                        tmpHealth[cid] = "silent"
                    }
                }
                main.post {
                    eventsByChannel.clear(); eventsByChannel.putAll(tmpEvents)
                    channelHealth.putAll(tmpHealth)
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
