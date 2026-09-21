package art.windgraham.lifecore.ui.inbox

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import art.windgraham.lifecore.Api
import art.windgraham.lifecore.ChatActivity
import art.windgraham.lifecore.PairStore
import art.windgraham.lifecore.R
import art.windgraham.lifecore.ThreadDetailActivity
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject

/**
 * v0.3 合并首页（Inbox）：
 *  - 会话（/v2/sessions）和通知线程（/v2/notify/threads）合并成一张列表
 *  - 按 updated_at 倒序（最近活动的在前）
 *  - 顶部 4 卡片：今日概览 + 4 计数器（沿用 today 摘要）
 *  - 中部 tab 切换：全部 / 会话 / 通知
 *  - 底部 FAB：新建会话（一键跳 ChatActivity，自动创建）
 *
 * 设计目标：让用户"一打开 App 就看见所有正在跟进的事"。
 */
class InboxFragment : Fragment() {

    private val main = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<Row>()
    private var filter: String = "all"   // all / session / notify

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private lateinit var empty: TextView
    private lateinit var chipAll: Chip
    private lateinit var chipSession: Chip
    private lateinit var chipNotify: Chip
    private lateinit var fab: FloatingActionButton

    // Hero 区计数器
    private lateinit var cntActive: TextView
    private lateinit var cntAwait: TextView
    private lateinit var cntSessions: TextView
    private lateinit var cntEvents: TextView

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_inbox, c, false)
        swipe = v.findViewById(R.id.inboxSwipe)
        rv = v.findViewById(R.id.inboxRv)
        empty = v.findViewById(R.id.inboxEmpty)
        chipAll = v.findViewById(R.id.chipAll)
        chipSession = v.findViewById(R.id.chipSession)
        chipNotify = v.findViewById(R.id.chipNotify)
        fab = v.findViewById(R.id.fabNew)
        cntActive = v.findViewById(R.id.cntActive)
        cntAwait = v.findViewById(R.id.cntAwait)
        cntSessions = v.findViewById(R.id.cntSessions)
        cntEvents = v.findViewById(R.id.cntEvents)

        rv.layoutManager = LinearLayoutManager(context)
        adapter = buildAdapter()
        rv.adapter = adapter

        // tab 切换
        val chips = listOf(chipAll to "all", chipSession to "session", chipNotify to "notify")
        for ((c, code) in chips) {
            c.setOnClickListener {
                filter = code
                for ((other, _) in chips) other.isChecked = (other === c)
                applyFilter()
            }
        }

        // 新建会话 FAB
        fab.setOnClickListener {
            val ctx = requireContext()
            startActivity(Intent(ctx, ChatActivity::class.java)
                .putExtra("session_id", "")
                .putExtra("auto_create", true))
        }

        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    /** Row 抽象：会话或通知。 */
    sealed class Row {
        abstract val ts: String
        abstract val key: String

        data class Session(
            val sid: String,
            val title: String,
            val lastMessage: String,
            override val ts: String,
            val unread: Boolean
        ) : Row() {
            override val key = "S$sid"
        }

        data class Notify(
            val tid: Int,
            val title: String,
            val summary: String,
            val priority: String,
            val generation: Int,
            val state: String,
            override val ts: String,
            val fresh: Boolean
        ) : Row() {
            override val key = "N$tid"
        }
    }

    private fun buildAdapter(): RecyclerView.Adapter<RecyclerView.ViewHolder> {
        return object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun getItemViewType(pos: Int) = when (rows[pos]) {
                is Row.Session -> 0
                is Row.Notify -> 1
            }
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val layout = if (t == 0) R.layout.item_inbox_session else R.layout.item_inbox_notify
                return object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(layout, p, false)) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                when (val r = rows[pos]) {
                    is Row.Session -> bindSession(h, r)
                    is Row.Notify -> bindNotify(h, r)
                }
            }
        }
    }

    private fun bindSession(h: RecyclerView.ViewHolder, r: Row.Session) {
        val title = h.itemView.findViewById<TextView>(R.id.sessTitle)
        val preview = h.itemView.findViewById<TextView>(R.id.sessPreview)
        val time = h.itemView.findViewById<TextView>(R.id.sessTime)
        val badge = h.itemView.findViewById<ImageView>(R.id.sessBadge)
        val accent = h.itemView.findViewById<View>(R.id.sessAccent)
        title.text = r.title.ifBlank { "未命名会话" }
        preview.text = r.lastMessage.ifBlank { "(无消息)" }.take(200)
        time.text = Api.fmtTime(r.ts)
        badge.visibility = if (r.unread) View.VISIBLE else View.GONE
        title.setTextColor(Api.themeColor(requireContext(),
            if (r.unread) com.google.android.material.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurface))
        accent.setBackgroundColor(sessionColor(r.sid))
        h.itemView.setOnClickListener {
            startActivity(Intent(requireContext(), ChatActivity::class.java)
                .putExtra("session_id", r.sid))
        }
    }

    private fun bindNotify(h: RecyclerView.ViewHolder, r: Row.Notify) {
        val title = h.itemView.findViewById<TextView>(R.id.notTitle)
        val summary = h.itemView.findViewById<TextView>(R.id.notSummary)
        val badge = h.itemView.findViewById<TextView>(R.id.notBadge)
        val time = h.itemView.findViewById<TextView>(R.id.notTime)
        val dot = h.itemView.findViewById<View>(R.id.notDot)
        // BUGFIX: 服务端旧数据 thread.title 是 AI 完整回复（500 字），
        // 客户端兜底：显示超过 60 字就截断避免爆行
        val titleStr = r.title.ifBlank { "事项 #${r.tid}" }
        title.text = if (titleStr.length > 60) titleStr.take(60) + "…" else titleStr
        summary.text = r.summary.ifBlank { "(无摘要)" }.take(240)
        badge.text = if (r.generation > 1) "第${r.generation}次跟进" else ""
        badge.visibility = if (r.generation > 1) View.VISIBLE else View.GONE
        time.text = Api.fmtTime(r.ts)
        // 优先级色圆点
        val drawableRes = when {
            r.priority.equals("urgent", true) -> R.drawable.dot_urgent
            r.priority.equals("high", true) -> R.drawable.dot_high
            else -> R.drawable.dot_unread
        }
        dot.setBackgroundResource(drawableRes)
        title.setTextColor(Api.themeColor(requireContext(),
            if (r.fresh) com.google.android.material.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurface))
        h.itemView.setOnClickListener {
            val ctx = requireContext()
            ctx.getSharedPreferences("lifecore", Context.MODE_PRIVATE)
                .edit().putInt("seen_gen_${r.tid}", r.generation).apply()
            startActivity(Intent(ctx, ThreadDetailActivity::class.java)
                .putExtra("thread_id", r.tid))
        }
    }

    /** 会话颜色 = sid 散列 → HSL 色环挑一档，柔和不刺眼 */
    private fun sessionColor(sid: String): Int {
        val h = sid.hashCode()
        val hue = ((h and 0x7FFFFFFF) % 360).toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.55f))
    }

    private fun applyFilter() {
        // 简化：直接 reload，因为 rows 列表本身没变（filter 由重新 load 处理）
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (!PairStore.isPaired()) return
        Thread {
            try {
                // 1) 会话列表
                val sr = Api.sessions()
                // BUGFIX: hermes /api/sessions 返回 {"object":"list","data":[...]}
                // 字段名是 data 不是 sessions
                val sArr = Api.arr(sr, "data")
                val sessions = mutableListOf<Row.Session>()
                for (k in 0 until sArr.length()) {
                    val o = sArr.getJSONObject(k)
                    val sid = o.optString("id", o.optString("session_id", ""))
                    if (sid.isBlank()) continue
                    val title = o.optString("title", "")
                    // messages 数组可能嵌在 session 内（如果服务端返回），否则用 last_message 字段
                    val msgs = o.optJSONArray("messages")
                    val lastMsg = when {
                        msgs != null && msgs.length() > 0 -> {
                            val last = msgs.getJSONObject(msgs.length() - 1)
                            last.optString("content", last.optString("text", ""))
                        }
                        else -> o.optString("last_message", o.optString("preview", ""))
                    }
                    val ts = o.optString("updated_at", o.optString("last_active_at", ""))
                    // 未读：用 last_role 字段判断"AI 最后一条未读"
                    val lastRole = o.optString("last_role", "")
                    val unread = lastRole.equals("assistant", true)
                    sessions.add(Row.Session(sid, title, lastMsg, ts, unread))
                }

                // 2) 通知 threads
                val nr = Api.notifyThreads()
                val nArr = Api.arr(nr, "threads")
                val seenPref = requireContext().getSharedPreferences("lifecore", Context.MODE_PRIVATE)
                val notifies = mutableListOf<Row.Notify>()
                for (k in 0 until nArr.length()) {
                    val o = nArr.getJSONObject(k)
                    val tid = o.optInt("id", -1)
                    if (tid <= 0) continue
                    val gen = o.optInt("item_count", 1)
                    val seen = seenPref.getInt("seen_gen_$tid", 0)
                    val fresh = gen > seen
                    notifies.add(Row.Notify(
                        tid = tid,
                        title = o.optString("title", ""),
                        summary = o.optString("summary", ""),
                        priority = o.optString("priority", "normal"),
                        generation = gen,
                        state = o.optString("last_resolution", ""),
                        ts = o.optString("updated_at", ""),
                        fresh = fresh
                    ))
                }

                // 3) 合并 + 排序（按 ts 倒序）—— 空串排最后
                val merged = (sessions + notifies).toMutableList<Row>()
                merged.sortByDescending { it.ts }

                // 4) filter
                val filtered = when (filter) {
                    "session" -> merged.filterIsInstance<Row.Session>()
                    "notify" -> merged.filterIsInstance<Row.Notify>()
                    else -> merged
                }

                // 5) 计数器（用未过滤的总数）
                val activeThreads = notifies.count { it.state.isBlank() }
                val awaiting = notifies.count { it.state == "awaiting_feedback" || it.state.isBlank() }

                main.post {
                    rows.clear()
                    rows.addAll(filtered)
                    adapter.notifyDataSetChanged()
                    empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                    cntActive.text = activeThreads.toString()
                    cntAwait.text = awaiting.toString()
                    cntSessions.text = sessions.size.toString()
                    cntEvents.text = (sessions.size + notifies.size).toString()
                    swipe.isRefreshing = false
                }
            } catch (e: Exception) {
                main.post {
                    empty.text = "加载失败：${Api.errText(e)}\n\n下拉重试"
                    empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    swipe.isRefreshing = false
                }
            }
        }.start()
    }
}
