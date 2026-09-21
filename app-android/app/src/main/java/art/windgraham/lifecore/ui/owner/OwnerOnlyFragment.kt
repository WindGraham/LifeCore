package art.windgraham.lifecore.ui.owner

import android.content.Intent
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
import art.windgraham.lifecore.ThreadDetailActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import org.json.JSONObject

/**
 * 我的专属（P1-S4；docs/18 G6）。
 *
 * - 调 /v2/notify/all?addressed_to_owner=1
 * - 顶部 priority filter chip：全部 / 高优 / 常规
 * - high priority 项用 errorContainer 强调（构造时 setBackgroundTintList）
 */
class OwnerOnlyFragment : Fragment() {

    private val main = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<JSONObject>()
    private var priFilter: String = "all"
    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    // 延迟到 onViewCreated：构造期访问 requireContext() 会抛 IllegalStateException
    // （Fragment 此时尚未 attach 到 Activity）
    private var ec: Int = 0
    private var sc: Int = 0

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_owner_only, c, false)
        // 此时 Fragment 已 attach 到 Activity，可安全 requireContext()
        val appCtx = requireContext().applicationContext
        ec = Api.themeColor(appCtx, com.google.android.material.R.attr.colorErrorContainer)
        sc = Api.themeColor(appCtx, com.google.android.material.R.attr.colorSurfaceContainer)
        val chipAll = v.findViewById<Chip>(R.id.chipPriAll)
        val chipHigh = v.findViewById<Chip>(R.id.chipPriHigh)
        val chipNormal = v.findViewById<Chip>(R.id.chipPriNormal)
        rv = v.findViewById(R.id.listRv)
        swipe = v.findViewById(R.id.swipe)
        rv.layoutManager = LinearLayoutManager(context)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context)
                    .inflate(R.layout.item_owner, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = rows[pos]
                val pri = h.itemView.findViewById<TextView>(R.id.ownerPri)
                pri.text = o.optString("priority", "normal").uppercase()
                val isHigh = o.optString("priority", "normal").equals("urgent", true) ||
                             o.optString("priority", "normal").equals("high", true)
                // 高优强调：背景设为 errorContainer，否则保持默认
                h.itemView.setBackgroundColor(if (isHigh) ec else sc)
                val st = h.itemView.findViewById<TextView>(R.id.ownerState)
                st.text = o.optString("state", "")
                val time = h.itemView.findViewById<TextView>(R.id.ownerTime)
                time.text = Api.fmtTime(o.optString("created_at", ""))
                val sum = h.itemView.findViewById<TextView>(R.id.ownerSummary)
                sum.text = o.optString("summary", "(无摘要)").take(300)
                val ch = h.itemView.findViewById<TextView>(R.id.ownerChannel)
                val cid = o.optString("channel_id", "")
                ch.text = if (cid.isNotBlank()) "ch: $cid" else "ch: ?"
                val fb = h.itemView.findViewById<MaterialButton>(R.id.ownerFeedback)
                val iid = o.optInt("id", -1)
                val tid = if (o.has("thread_id") && !o.isNull("thread_id")) o.optInt("thread_id", -1) else -1
                val state = o.optString("state", "")
                fb.text = when (state) {
                    "awaiting_feedback" -> "去决策"
                    "queued" -> "排队中"
                    else -> "已处理"
                }
                fb.isEnabled = state == "awaiting_feedback"
                fb.setOnClickListener {
                    if (tid > 0) startActivity(
                        Intent(context, ThreadDetailActivity::class.java).putExtra("thread_id", tid)
                    )
                }
                h.itemView.setOnClickListener {
                    if (tid > 0) startActivity(
                        Intent(context, ThreadDetailActivity::class.java).putExtra("thread_id", tid)
                    )
                }
            }
        }
        rv.adapter = adapter

        // 优先级 chip 互斥
        val chips = listOf(chipAll to "all", chipHigh to "high", chipNormal to "normal")
        for ((c, code) in chips) {
            c.setOnClickListener {
                priFilter = code
                for ((other, _) in chips) other.isChecked = (other === c)
                load()
            }
        }

        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    private fun load() {
        if (!PairStore.isPaired()) return
        Thread {
            try {
                val r = Api.notifyAll(addressedToOwner = true, priority = priFilter, limit = 50)
                val got = Api.arr(r, "items")
                main.post {
                    rows.clear()
                    for (k in 0 until got.length()) rows.add(got.getJSONObject(k))
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

    override fun onResume() { super.onResume(); load() }
}
