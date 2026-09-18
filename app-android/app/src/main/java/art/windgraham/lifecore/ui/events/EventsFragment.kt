package art.windgraham.lifecore.ui.events

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import org.json.JSONObject

/**
 * 事件流（P0-S2；docs/18 G2）。
 *
 * - 调 /v2/events?since=&limit=&channel_id=
 * - 顶部 channel chip 弹 BottomSheet 选通道过滤
 * - 增量刷新：sinceSeq 传上一次 max_seq
 */
class EventsFragment : Fragment() {

    private val main = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<JSONObject>()
    private var lastMaxSeq: Long = 0
    private var channelFilter: String? = null
    private val knownChannels = mutableListOf<String>()
    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private lateinit var chip: Chip
    private lateinit var maxSeqTv: TextView

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_events, c, false)
        chip = v.findViewById(R.id.chipChannel)
        maxSeqTv = v.findViewById(R.id.maxSeq)
        rv = v.findViewById(R.id.listRv)
        swipe = v.findViewById(R.id.swipe)
        val refreshBtn = v.findViewById<MaterialButton>(R.id.btnRefresh)
        rv.layoutManager = LinearLayoutManager(context)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context)
                    .inflate(R.layout.item_event, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = rows[pos]
                val seq = h.itemView.findViewById<TextView>(R.id.evSeq)
                seq.text = "#" + o.optLong("seq", 0L)
                val ch = h.itemView.findViewById<TextView>(R.id.evChannel)
                ch.text = o.optString("channel_id", "(无通道)")
                val time = h.itemView.findViewById<TextView>(R.id.evTime)
                time.text = Api.fmtTime(o.optString("at", ""))
                val payload = h.itemView.findViewById<TextView>(R.id.evPayload)
                payload.text = summarizePayload(o.optJSONObject("payload"))
            }
        }
        rv.adapter = adapter

        chip.setOnClickListener { showChannelPicker() }
        refreshBtn.setOnClickListener { load(forceFresh = true) }
        swipe.setOnRefreshListener { load(forceFresh = false) }
        load(forceFresh = true)
        return v
    }

    /** 提取 payload 关键字段做摘要（前 100 字符）。 */
    private fun summarizePayload(p: JSONObject?): String {
        if (p == null) return "(空 payload)"
        val summary = p.optString("summary", "")
        val level = p.optString("level", "")
        val kind = p.optString("kind", "")
        val sb = StringBuilder()
        if (level.isNotBlank()) sb.append(level).append(' ')
        if (kind.isNotBlank()) sb.append(kind).append(": ")
        if (summary.isNotBlank()) sb.append(summary) else sb.append(p.toString())
        return sb.toString().take(180)
    }

    private fun showChannelPicker() {
        val ctx = requireContext()
        val items = mutableListOf("全部")
        items.addAll(knownChannels)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("按通道过滤")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) channelFilter = null
                else channelFilter = items[which]
                chip.text = if (channelFilter == null) "通道: 全部" else "通道: $channelFilter"
                load(forceFresh = true)
            }.show()
    }

    private fun load(forceFresh: Boolean) {
        if (!PairStore.isPaired()) return
        Thread {
            try {
                // 第一次或强制刷新 → since=0；否则用 lastMaxSeq 做增量
                val since = if (forceFresh || lastMaxSeq == 0L) null else lastMaxSeq
                val r = Api.events(channelId = channelFilter, sinceSeq = since, limit = 100)
                val got = Api.arr(r, "events")
                val maxSeq = r.optLong("max_seq", 0L)
                val list = mutableListOf<JSONObject>()
                val chSet = mutableSetOf<String>()
                for (k in 0 until got.length()) {
                    val o = got.getJSONObject(k)
                    list.add(o)
                    val c = o.optString("channel_id", "")
                    if (c.isNotBlank()) chSet.add(c)
                }
                main.post {
                    if (forceFresh) {
                        rows.clear()
                        rows.addAll(list)
                    } else {
                        // 增量：去重 + append（按 seq 升序）
                        val existing = rows.map { it.optLong("seq", 0L) }.toHashSet()
                        for (o in list) if (!existing.contains(o.optLong("seq", 0L))) rows.add(o)
                    }
                    rows.sortBy { it.optLong("seq", 0L) }
                    // 更新 channel 列表
                    val cur = knownChannels.toSet()
                    val newCh = chSet - cur
                    if (newCh.isNotEmpty()) knownChannels.addAll(newCh.sorted())
                    // 更新 maxSeq
                    if (maxSeq > lastMaxSeq) lastMaxSeq = maxSeq
                    maxSeqTv.text = "max_seq=$lastMaxSeq · ${rows.size}条"
                    adapter.notifyDataSetChanged()
                    swipe.isRefreshing = false
                }
            } catch (e: Exception) {
                main.post {
                    Toast.makeText(context, Api.errText(e), Toast.LENGTH_LONG).show()
                    swipe.isRefreshing = false
                }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); load(forceFresh = true) }
}
