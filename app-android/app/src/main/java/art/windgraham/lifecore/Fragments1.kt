package art.windgraham.lifecore

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject

class SessionRow(val id: String, val title: String, val updated: String)

/** 对话页：agent 会话列表 → 进入聊天。 */
class SessionsFragment : Fragment() {
    private val rows = mutableListOf<SessionRow>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_list, c, false)
        val rv = v.findViewById<RecyclerView>(R.id.listRv)
        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipe)
        lateinit var loadFn: () -> Unit
        val fab = v.findViewById<FloatingActionButton>(R.id.fab)
        fab.visibility = View.GONE
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_session, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val r = rows[pos]
                h.itemView.findViewById<TextView>(R.id.sessionTitle).text = r.title
                val t = Api.fmtTime(r.updated)
                h.itemView.findViewById<TextView>(R.id.sessionMeta).text =
                    if (t.isNotBlank()) t else r.id.take(13)
                h.itemView.setOnClickListener {
                    startActivity(Intent(context, ChatActivity::class.java)
                        .putExtra("session_id", r.id).putExtra("title", r.title))
                }
            }
        }
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter
        loadFn = { Api.bg(requireContext()) {
            val r = Api.getJson("/v2/sessions")
            val data = Api.arr(r, "data")
            val got = mutableListOf<SessionRow>()
            for (k in 0 until data.length()) {
                val o = data.getJSONObject(k)
                val id = o.optString("session_id", o.optString("id", ""))
                val title = o.optString("title", o.optString("name", id.take(24)))
                val upd = o.optString("updated_at", o.optString("last_active_at", ""))
                if (id.isNotBlank()) got.add(SessionRow(id, title, upd))
            }
            Api.ui { rows.clear(); rows.addAll(got); adapter.notifyDataSetChanged(); swipe.isRefreshing = false }
        } }
        swipe.setOnRefreshListener { loadFn() }; loadFn()
        return v
    }
}

/** 通知页 = 线程收件箱（按议题聚合）+ 自动播报开关 + 当前待决策卡片（docs/10 §1）。 */
class NotifyFragment : Fragment() {
    private val threads = mutableListOf<JSONObject>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_notify, c, false)
        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipe)
        lateinit var loadFn: () -> Unit
        val btnAuto = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAutoPlay)
        fun refreshAutoBtn() {
            val on = android.app.ActivityManager.RunningServiceInfo::class.java != null &&
                run {
                    val am = requireContext().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    am.getRunningServices(50).any { it.service.className == PlayService::class.java.name }
                }
            btnAuto.text = if (on) "⏹ 停止自动播报" else "▶ 开启自动播报（App 存活期自动朗读新汇报）"
        }
        btnAuto.setOnClickListener {
            val ctx = requireContext()
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val on = am.getRunningServices(50).any { it.service.className == PlayService::class.java.name }
            if (on) ctx.startService(Intent(ctx, PlayService::class.java).setAction(PlayService.ACTION_STOP))
            else {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
                }
                ctx.startForegroundService(Intent(ctx, PlayService::class.java))
            }
            btnAuto.postDelayed({ refreshAutoBtn() }, 800)
        }
        val card = v.findViewById<View>(R.id.activeCard)
        val queueTv = v.findViewById<TextView>(R.id.queueList)

        // ── 线程收件箱：标题 + 代际角标 + 末次决议 chip + updated_at ──
        val seenPref = requireContext().getSharedPreferences("lifecore", android.content.Context.MODE_PRIVATE)
        val threadsRv = v.findViewById<RecyclerView>(R.id.threadsRv)
        val threadAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = threads.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_thread, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = threads[pos]
                val tid = o.getInt("id")
                val gen = o.optInt("item_count", 1)
                val title = h.itemView.findViewById<TextView>(R.id.threadTitle)
                title.text = o.optString("title", "事项 #$tid")
                val seen = seenPref.getInt("seen_gen_$tid", 0)
                val fresh = gen > seen                       // 代际增加 → 未读高亮
                title.setTextColor(Api.themeColor(requireContext(),
                    if (fresh) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface))
                title.text = title.text.toString() + if (fresh) "  ●" else ""
                h.itemView.findViewById<TextView>(R.id.threadBadge).text =
                    if (gen > 1) "第${gen}次跟进" else ""
                val chip = h.itemView.findViewById<TextView>(R.id.threadChip)
                val snoozedUntil = runCatching { if (o.isNull("snoozed_until")) 0.0 else o.getDouble("snoozed_until") }.getOrDefault(0.0)
                chip.text = when {
                    snoozedUntil > 0 -> "将于 ${Api.fmtTs(snoozedUntil)} 再提醒"
                    o.optString("last_resolution") == "actioned" -> "已办"
                    o.optString("last_resolution") == "snooze" -> "稍后"
                    o.optString("last_resolution") == "dismissed" -> "已忽略"
                    else -> ""
                }
                h.itemView.findViewById<TextView>(R.id.threadTime).text =
                    Api.fmtTime(o.optString("updated_at", ""))
                h.itemView.setOnClickListener {
                    seenPref.edit().putInt("seen_gen_$tid", gen).apply()
                    startActivity(Intent(context, ThreadDetailActivity::class.java).putExtra("thread_id", tid))
                    title.setTextColor(Api.themeColor(requireContext(), com.google.android.material.R.attr.colorOnSurface))
                    title.text = o.optString("title", "事项 #$tid")
                }
            }
        }
        threadsRv.layoutManager = LinearLayoutManager(context)
        threadsRv.adapter = threadAdapter

        fun feedback(id: Int, action: String) = Api.bg(requireContext()) {
            Api.postJson("/v2/notify/items/$id/feedback", JSONObject().put("action", action))
            Api.ui { loadFn() }
        }

        fun renderActive(o: JSONObject?) {
            if (o == null) { card.visibility = View.GONE; return }
            card.visibility = View.VISIBLE
            card.findViewById<TextView>(R.id.notifySummary).text = o.optString("summary", "(无摘要)")
            val pr = o.optString("priority", "normal").uppercase()
            val pt = Api.fmtTime(o.optString("created_at", ""))
            card.findViewById<TextView>(R.id.notifyMeta).text =
                listOf(pr, pt).filter { it.isNotBlank() }.joinToString("  ·  ")
            val iid = o.getInt("id")
            val opts = Api.arr(o, "options")
            card.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpt1).apply {
                text = if (opts.length() > 0) opts.getString(0) else "是（执行）"
                setOnClickListener { feedback(iid, "actioned") }
            }
            card.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpt2).apply {
                text = if (opts.length() > 1) opts.getString(1) else "否"
                setOnClickListener { feedback(iid, "dismissed") }
            }
            card.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSnooze).apply {
                text = if (opts.length() > 2) opts.getString(2) else "稍后提醒"
                setOnClickListener { feedback(iid, "snooze") }
            }
        }

        loadFn = { Api.bg(requireContext()) {
            val r = Api.getJson("/v2/notify/active")
            val act = r.optJSONObject("active")
            val q = Api.arr(r, "queue")
            val sb = StringBuilder()
            for (k in 0 until q.length()) {
                val it = q.getJSONObject(k)
                sb.append(k + 1).append("  ").append(it.optString("summary", "(无摘要)")).append('\n')
            }
            val t = Api.notifyThreads()
            val got = Api.arr(t, "threads")
            Api.ui {
                threads.clear(); for (k in 0 until got.length()) threads.add(got.getJSONObject(k))
                threadAdapter.notifyDataSetChanged()
                renderActive(act)
                queueTv.text = if (sb.isEmpty()) "队列空——没有等待中的汇报" else "排队中（${q.length()}）\n$sb"
                swipe.isRefreshing = false
            }
        } }
        swipe.setOnRefreshListener { loadFn() }; loadFn()
        refreshAutoBtn()
        return v
    }
}
