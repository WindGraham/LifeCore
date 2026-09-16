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
                h.itemView.findViewById<TextView>(R.id.sessionMeta).text = r.id
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

/** 通知页：待决策卡片（单活动锁）+ 队列（docs/02 §14）。 */
class NotifyFragment : Fragment() {
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

        fun feedback(id: Int, action: String) = Api.bg(requireContext()) {
            Api.postJson("/v2/notify/items/$id/feedback", JSONObject().put("action", action))
            Api.ui { loadFn() }
        }

        fun renderActive(o: JSONObject?) {
            if (o == null) { card.visibility = View.GONE; return }
            card.visibility = View.VISIBLE
            card.findViewById<TextView>(R.id.notifySummary).text = o.optString("summary", "(无摘要)")
            card.findViewById<TextView>(R.id.notifyMeta).text =
                "通道 ${o.optString("channel_id")} · ${o.optString("priority")} · ${o.optString("created_at","").take(19)}"
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
                sb.append("• ").append(it.optString("summary", "(无摘要)")).append('\n')
            }
            Api.ui {
                renderActive(act)
                queueTv.text = if (sb.isEmpty()) "队列空" else "排队中（${q.length()}）：\n$sb"
                swipe.isRefreshing = false
            }
        } }
        swipe.setOnRefreshListener { loadFn() }; loadFn()
        refreshAutoBtn()
        return v
    }
}
