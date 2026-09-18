package art.windgraham.lifecore.ui.today

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import art.windgraham.lifecore.Api
import art.windgraham.lifecore.ChatActivity
import art.windgraham.lifecore.PairStore
import art.windgraham.lifecore.R
import art.windgraham.lifecore.ThreadDetailActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

/**
 * 今日视图（docs/12 §2 重构 + docs/18 P1-S6 真 digest）。
 *
 * 数据源：
 *  - 主源 /v2/digest/today（服务端聚合）—— 失败时降级到旧版 /v2/notify/active + /v2/notify/threads
 *  - 三卡片：A 已发生 / B 等你回应 / C 想问你的
 *  - 顶部 4 个 counter：今日事件 / 活跃议题 / 等你回应 / 等你决策
 *  - 卡片 D：用户输入区（→ ChatActivity）
 */
class TodayFragment : Fragment() {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var cntEvents: TextView
    private lateinit var cntThreads: TextView
    private lateinit var cntFeedback: TextView
    private lateinit var cntDecision: TextView

    private lateinit var cardARv: RecyclerView
    private lateinit var cardAEmpty: TextView
    private lateinit var cardBRv: RecyclerView
    private lateinit var cardBEmpty: TextView
    private lateinit var cardCRv: RecyclerView
    private lateinit var cardCEmpty: TextView

    private val cardA = mutableListOf<JSONObject>()
    private val cardB = mutableListOf<JSONObject>()
    private val cardC = mutableListOf<JSONObject>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_today, c, false)
        swipe = v.findViewById(R.id.swipe)
        cntEvents = v.findViewById(R.id.cntEvents)
        cntThreads = v.findViewById(R.id.cntThreads)
        cntFeedback = v.findViewById(R.id.cntFeedback)
        cntDecision = v.findViewById(R.id.cntDecision)
        cardARv = v.findViewById(R.id.cardARv)
        cardAEmpty = v.findViewById(R.id.cardAEmpty)
        cardBRv = v.findViewById(R.id.cardBRv)
        cardBEmpty = v.findViewById(R.id.cardBEmpty)
        cardCRv = v.findViewById(R.id.cardCRv)
        cardCEmpty = v.findViewById(R.id.cardCEmpty)

        cardARv.layoutManager = LinearLayoutManager(context)
        cardBRv.layoutManager = LinearLayoutManager(context)
        cardCRv.layoutManager = LinearLayoutManager(context)
        cardARv.adapter = simpleAdapter(cardA)
        cardBRv.adapter = feedbackAdapter(cardB)
        cardCRv.adapter = simpleAdapter(cardC)

        val inputD = v.findViewById<EditText>(R.id.inputD)
        v.findViewById<MaterialButton>(R.id.btnSendD).setOnClickListener {
            val text = inputD.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            inputD.setText("")
            startActivity(
                Intent(context, ChatActivity::class.java)
                    .putExtra("session_id", "")
                    .putExtra("seed_text", text)
            )
        }
        val chipChips = v.findViewById<LinearLayout>(R.id.chipChips)
        val suggestions = listOf(
            "今天做了什么？",
            "现在最该拍板的是什么？",
            "5 分钟前我在想什么？"
        )
        for (label in suggestions) {
            val tv = TextView(context).apply {
                text = label
                setPadding(20, 8, 20, 8)
                setTextColor(0xFF006A6A.toInt())
                textSize = 13f
                setOnClickListener { inputD.setText(label) }
            }
            chipChips.addView(tv)
        }

        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    private fun simpleAdapter(data: MutableList<JSONObject>) =
        object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = data.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context)
                    .inflate(R.layout.item_digest, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = data[pos]
                h.itemView.findViewById<TextView>(R.id.digestTitle).text =
                    o.optString("title", o.optString("summary", "(无标题)")).take(80)
                h.itemView.findViewById<TextView>(R.id.digestTime).text =
                    Api.fmtTime(o.optString("updated_at", o.optString("created_at", "")))
                val tid = if (o.has("thread_id")) o.optInt("thread_id", -1) else -1
                h.itemView.setOnClickListener {
                    if (tid > 0) startActivity(
                        Intent(context, ThreadDetailActivity::class.java).putExtra("thread_id", tid)
                    )
                }
            }
        }

    /** 卡片 B：直接触发 feedback 按钮（同 NotifyListFragment 的 activeCard UI）。 */
    private fun feedbackAdapter(data: MutableList<JSONObject>) =
        object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = data.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context)
                    .inflate(R.layout.item_tmsg_ai, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = data[pos]
                h.itemView.findViewById<TextView>(R.id.msgContent).text =
                    o.optString("summary", "(无摘要)").take(160)
                h.itemView.findViewById<TextView>(R.id.msgTime).text =
                    Api.fmtTime(o.optString("created_at", ""))
                val iid = o.optInt("id", -1)
                val tid = if (o.has("thread_id") && !o.isNull("thread_id")) o.optInt("thread_id", -1) else -1
                // 卡片 B 项点击 = 直接进 thread 详情触发反馈
                h.itemView.setOnClickListener {
                    if (tid > 0) startActivity(
                        Intent(context, ThreadDetailActivity::class.java).putExtra("thread_id", tid)
                    ) else if (iid > 0) feedback(iid, "actioned")
                }
                // 长按 → 快速进入详情
                h.itemView.setOnLongClickListener {
                    if (tid > 0) {
                        startActivity(
                            Intent(context, ThreadDetailActivity::class.java).putExtra("thread_id", tid)
                        ); true
                    } else false
                }
            }
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

    /** 优先 /v2/digest/today；失败回退到 notify_active + notify_threads。 */
    private fun load() {
        if (!PairStore.isPaired()) return
        Thread {
            try {
                val r = Api.digestToday()
                renderDigest(r)
                main.post {
                    swipe.isRefreshing = false
                }
            } catch (_: Exception) {
                // 端点不存在或失败 → 降级旧逻辑
                try {
                    val active = Api.notifyActive()
                    val threads = Api.notifyThreads()
                    renderFallback(active, threads)
                } catch (e: Exception) {
                    main.post {
                        Toast.makeText(context, Api.errText(e), Toast.LENGTH_LONG).show()
                    }
                }
                main.post { swipe.isRefreshing = false }
            }
        }.start()
    }

    /** /v2/digest/today 响应结构（约定）：
     *   {
     *     counters: { events_today, active_threads, awaiting_feedback, awaiting_decision },
     *     card_a: [ { title, summary, updated_at, thread_id } ],
     *     card_b: [ { id, summary, options, priority, created_at, thread_id } ],
     *     card_c: [ { title, summary, created_at } ]
     *   } */
    private fun renderDigest(r: JSONObject) {
        val c = r.optJSONObject("counters") ?: JSONObject()
        main.post {
            cntEvents.text = c.optInt("events_today", 0).toString()
            cntThreads.text = c.optInt("active_threads", 0).toString()
            cntFeedback.text = c.optInt("awaiting_feedback", 0).toString()
            cntDecision.text = c.optInt("awaiting_decision", 0).toString()
        }
        val a = Api.arr(r, "card_a")
        val b = Api.arr(r, "card_b")
        val cc = Api.arr(r, "card_c")
        main.post {
            cardA.clear(); for (k in 0 until a.length()) cardA.add(a.getJSONObject(k))
            cardB.clear(); for (k in 0 until b.length()) cardB.add(b.getJSONObject(k))
            cardC.clear(); for (k in 0 until cc.length()) cardC.add(cc.getJSONObject(k))
            renderEmpties()
            cardARv.adapter?.notifyDataSetChanged()
            cardBRv.adapter?.notifyDataSetChanged()
            cardCRv.adapter?.notifyDataSetChanged()
        }
    }

    /** 降级渲染：用 /v2/notify/active + /v2/notify/threads 拼出 digest。 */
    private fun renderFallback(activeR: JSONObject, threadsR: JSONObject) {
        val act = activeR.optJSONObject("active")
        val q = Api.arr(activeR, "queue")
        val threads = Api.arr(threadsR, "threads")
        main.post {
            cntEvents.text = (q.length() + threads.length()).toString()
            cntThreads.text = threads.length().toString()
            cntFeedback.text = if (act != null) "1" else "0"
            cntDecision.text = if (act != null) "1" else "0"
            // 卡片 A：用 threads 列表作为 digest（已完成的议题）
            cardA.clear()
            for (k in 0 until threads.length()) cardA.add(threads.getJSONObject(k))
            // 卡片 B：active + queue
            cardB.clear()
            if (act != null) cardB.add(act)
            for (k in 0 until q.length()) cardB.add(q.getJSONObject(k))
            // 卡片 C：暂无内容（M2 接入后由 digest 端点填充）
            cardC.clear()
            renderEmpties()
            cardARv.adapter?.notifyDataSetChanged()
            cardBRv.adapter?.notifyDataSetChanged()
            cardCRv.adapter?.notifyDataSetChanged()
        }
    }

    private fun renderEmpties() {
        cardAEmpty.visibility = if (cardA.isEmpty()) View.VISIBLE else View.GONE
        cardBEmpty.visibility = if (cardB.isEmpty()) View.VISIBLE else View.GONE
        cardCEmpty.visibility = if (cardC.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onResume() { super.onResume(); load() }
}
