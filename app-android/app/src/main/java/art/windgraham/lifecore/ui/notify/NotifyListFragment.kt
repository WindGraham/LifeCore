package art.windgraham.lifecore.ui.notify

import android.app.ActivityManager
import android.content.Context
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
import art.windgraham.lifecore.PlayService
import art.windgraham.lifecore.R
import art.windgraham.lifecore.ThreadDetailActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

/**
 * 通知列表（含 active + queue + thread inbox）。
 *
 * 设计原则 7：底部 action（自动播报开关 + 一键重连）+ 主屏线程 inbox
 * 单活动锁面板：active 始终置顶（仅 1 条）
 */
class NotifyListFragment : Fragment() {

    private val main = Handler(Looper.getMainLooper())
    private val threads = mutableListOf<JSONObject>()
    private val queue = mutableListOf<JSONObject>()

    private lateinit var activeCard: MaterialCardView
    private lateinit var activeSummary: TextView
    private lateinit var activeMeta: TextView
    private lateinit var btnOpt1: MaterialButton
    private lateinit var btnOpt2: MaterialButton
    private lateinit var btnSnooze: MaterialButton
    private lateinit var threadsRv: RecyclerView
    private lateinit var queueTv: TextView
    private lateinit var btnAutoPlay: MaterialButton
    private var autoOn = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_notify, c, false)
        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipe)
        activeCard = v.findViewById(R.id.activeCard)
        activeSummary = v.findViewById(R.id.notifySummary)
        activeMeta = v.findViewById(R.id.notifyMeta)
        btnOpt1 = v.findViewById(R.id.btnOpt1)
        btnOpt2 = v.findViewById(R.id.btnOpt2)
        btnSnooze = v.findViewById(R.id.btnSnooze)
        threadsRv = v.findViewById(R.id.threadsRv)
        queueTv = v.findViewById(R.id.queueList)
        btnAutoPlay = v.findViewById(R.id.btnAutoPlay)

        threadsRv.layoutManager = LinearLayoutManager(context)

        val seenPref = requireContext().getSharedPreferences("lifecore", Context.MODE_PRIVATE)
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
                val fresh = gen > seen
                title.setTextColor(Api.themeColor(requireContext(),
                    if (fresh) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurface))
                if (fresh) title.text = title.text.toString() + "  ●"
                h.itemView.findViewById<TextView>(R.id.threadBadge).text =
                    if (gen > 1) "第${gen}次跟进" else ""
                val chip = h.itemView.findViewById<TextView>(R.id.threadChip)
                val snoozedUntil = runCatching {
                    if (o.isNull("snoozed_until")) 0.0 else o.getDouble("snoozed_until")
                }.getOrDefault(0.0)
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
                    startActivity(Intent(requireContext(), ThreadDetailActivity::class.java)
                        .putExtra("thread_id", tid))
                }
            }
        }
        threadsRv.adapter = threadAdapter

        btnAutoPlay.setOnClickListener { toggleAutoPlay() }

        swipe.setOnRefreshListener { load() }
        refreshAutoBtn()
        load()
        return v
    }

    private fun renderActive(o: JSONObject?) {
        if (o == null) { activeCard.visibility = View.GONE; return }
        activeCard.visibility = View.VISIBLE
        activeSummary.text = o.optString("summary", "(无摘要)")
        val pr = o.optString("priority", "normal").uppercase()
        val pt = Api.fmtTime(o.optString("created_at", ""))
        activeMeta.text = listOf(pr, pt).filter { it.isNotBlank() }.joinToString("  ·  ")
        val iid = o.getInt("id")
        val opts = Api.arr(o, "options")
        btnOpt1.text = if (opts.length() > 0) opts.getString(0) else "是（执行）"
        btnOpt1.setOnClickListener { feedback(iid, "actioned") }
        btnOpt2.text = if (opts.length() > 1) opts.getString(1) else "否"
        btnOpt2.setOnClickListener { feedback(iid, "dismissed") }
        btnSnooze.text = if (opts.length() > 2) opts.getString(2) else "稍后提醒"
        btnSnooze.setOnClickListener { feedback(iid, "snooze") }
    }

    private fun feedback(id: Int, action: String) {
        Thread {
            try {
                Api.notifyFeedback(id, action, minutes = if (action == "snooze") 30 else null)
                main.post { load() }
            } catch (e: Exception) {
                main.post { Toast.makeText(context, Api.errText(e), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun refreshAutoBtn() {
        val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        autoOn = am.getRunningServices(50).any { it.service.className == PlayService::class.java.name }
        btnAutoPlay.text = if (autoOn) "⏹ 停止自动播报" else "▶ 开启自动播报（App 存活期自动朗读）"
    }

    private fun toggleAutoPlay() {
        val ctx = requireContext()
        if (autoOn) {
            ctx.startService(Intent(ctx, PlayService::class.java).setAction(PlayService.ACTION_STOP))
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            ctx.startForegroundService(Intent(ctx, PlayService::class.java))
        }
        btnAutoPlay.postDelayed({ refreshAutoBtn() }, 800)
    }

    private fun load() {
        if (!PairStore.isPaired()) return
        Thread {
            try {
                val r = Api.notifyActive()
                val act = r.optJSONObject("active")
                val q = Api.arr(r, "queue")
                val t = Api.notifyThreads()
                val got = Api.arr(t, "threads")
                val qList = mutableListOf<JSONObject>()
                for (k in 0 until q.length()) qList.add(q.getJSONObject(k))
                main.post {
                    queue.clear(); queue.addAll(qList)
                    threads.clear()
                    for (k in 0 until got.length()) threads.add(got.getJSONObject(k))
                    renderActive(act)
                    threadsRv.adapter?.notifyDataSetChanged()
                    val sb = StringBuilder()
                    for (k in qList.indices) {
                        val it = qList[k]
                        sb.append(k + 1).append("  ").append(it.optString("summary", "(无摘要)")).append('\n')
                    }
                    queueTv.text = if (sb.isEmpty()) "队列空——没有等待中的汇报" else "排队中（${qList.size}）\n$sb"
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

    override fun onResume() {
        super.onResume()
        refreshAutoBtn()
        load()
    }
}
