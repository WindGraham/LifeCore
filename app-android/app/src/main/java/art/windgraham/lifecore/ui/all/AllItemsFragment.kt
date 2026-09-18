package art.windgraham.lifecore.ui.all

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
import com.google.android.material.tabs.TabLayout
import org.json.JSONObject

/**
 * 全部事项视图（P0-S1；docs/18 G1）。
 *
 * 顶部 4 个 tab：全部 / 待决（awaiting_feedback）/ 已记录（logged）/ 已处理（resolved）。
 * 每条 item 渲染 priority + summary + kind + created_at + channel_id；
 * 点击跳 ThreadDetailActivity（如果有 thread_id）。
 */
class AllItemsFragment : Fragment() {

    private val main = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<JSONObject>()
    private var currentState: String? = null  // null = 全部
    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_all_items, c, false)
        val tabs = v.findViewById<TabLayout>(R.id.tabs)
        rv = v.findViewById(R.id.listRv)
        swipe = v.findViewById(R.id.swipe)
        rv.layoutManager = LinearLayoutManager(context)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context)
                    .inflate(R.layout.item_notify_all, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = rows[pos]
                val pri = h.itemView.findViewById<TextView>(R.id.niPriority)
                pri.text = o.optString("priority", "normal").uppercase()
                val st = h.itemView.findViewById<TextView>(R.id.niState)
                st.text = o.optString("state", "")
                val kind = h.itemView.findViewById<TextView>(R.id.niKind)
                kind.text = o.optString("kind", "")
                val summary = h.itemView.findViewById<TextView>(R.id.niSummary)
                summary.text = o.optString("summary", "(无摘要)").take(300)
                val ch = h.itemView.findViewById<TextView>(R.id.niChannel)
                val channelId = o.optString("channel_id", "")
                val addressed = o.optBoolean("addressed_to_owner", false)
                ch.text = buildString {
                    if (channelId.isNotBlank()) append("ch: ").append(channelId)
                    if (addressed) {
                        if (isNotEmpty()) append(" · ")
                        append("📬 主人专属")
                    }
                }
                val time = h.itemView.findViewById<TextView>(R.id.niTime)
                time.text = Api.fmtTime(o.optString("created_at", ""))
                val tid = if (o.has("thread_id") && !o.isNull("thread_id")) o.optInt("thread_id", -1) else -1
                h.itemView.setOnClickListener {
                    if (tid > 0) startActivity(
                        Intent(context, ThreadDetailActivity::class.java).putExtra("thread_id", tid)
                    )
                }
            }
        }
        rv.adapter = adapter

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentState = when (tab.position) {
                    1 -> "awaiting_feedback"
                    2 -> "logged"
                    3 -> "resolved"
                    else -> null
                }
                load()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) { load() }
        })

        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    private fun load() {
        if (!PairStore.isPaired()) return
        Thread {
            try {
                val r = Api.notifyAll(state = currentState, limit = 50)
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
