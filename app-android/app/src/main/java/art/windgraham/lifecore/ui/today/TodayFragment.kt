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
 * 今日视图（docs/12 §2 主面板重构）。
 *
 * 三张主动卡片自上而下：
 *  - A. 今天已为你做完（list 5-10 条）
 *  - B. 现在需要你拍板（仅 active 非空时）
 *  - C. 想问点什么（输入框 + 上下文 chip）
 *
 * 数据源：
 *  - A: 复用 /v2/notify/threads（M2 接 /v2/digest/today）
 *  - B: /v2/notify/active
 *  - C: 用户输入 → ChatActivity
 */
class TodayFragment : Fragment() {

    private val main = Handler(Looper.getMainLooper())
    private val digest = mutableListOf<JSONObject>()
    private lateinit var digestRv: RecyclerView
    private lateinit var cardB: MaterialCardView
    private lateinit var cardBSummary: TextView
    private lateinit var cardBMeta: TextView
    private lateinit var btnOpt1: MaterialButton
    private lateinit var btnOpt2: MaterialButton
    private lateinit var btnSnooze: MaterialButton

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_today, c, false)
        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipe)

        digestRv = v.findViewById(R.id.digestRv)
        cardB = v.findViewById(R.id.cardB)
        cardBSummary = v.findViewById(R.id.activeSummary)
        cardBMeta = v.findViewById(R.id.activeMeta)
        btnOpt1 = v.findViewById(R.id.btnOpt1)
        btnOpt2 = v.findViewById(R.id.btnOpt2)
        btnSnooze = v.findViewById(R.id.btnSnooze)

        digestRv.layoutManager = LinearLayoutManager(context)

        val aAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = digest.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context)
                    .inflate(R.layout.item_digest, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = digest[pos]
                val titleTv = h.itemView.findViewById<TextView>(R.id.digestTitle)
                titleTv.text = o.optString("title", o.optString("summary", "(无标题)")).take(80)
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
        digestRv.adapter = aAdapter

        val chipChips = v.findViewById<LinearLayout>(R.id.chipChips)
        val inputC = v.findViewById<EditText>(R.id.inputC)
        v.findViewById<MaterialButton>(R.id.btnSendC).setOnClickListener {
            val text = inputC.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            inputC.setText("")
            startActivity(
                Intent(context, ChatActivity::class.java)
                    .putExtra("session_id", "")
                    .putExtra("seed_text", text)
            )
        }
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
                setOnClickListener { inputC.setText(label) }
            }
            chipChips.addView(tv)
        }

        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    private fun renderActive(o: JSONObject?) {
        if (o == null) { cardB.visibility = View.GONE; return }
        cardB.visibility = View.VISIBLE
        cardBSummary.text = o.optString("summary", "(无摘要)")
        val pr = o.optString("priority", "normal").uppercase()
        val pt = Api.fmtTime(o.optString("created_at", ""))
        cardBMeta.text = listOf(pr, pt).filter { it.isNotBlank() }.joinToString("  ·  ")
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

    private fun load() {
        if (!PairStore.isPaired()) return
        Thread {
            try {
                val active = Api.notifyActive()
                val threads = Api.notifyThreads()
                val actObj = active.optJSONObject("active")
                val arr = Api.arr(threads, "threads")
                main.post {
                    renderActive(actObj)
                    digest.clear()
                    for (k in 0 until arr.length()) digest.add(arr.getJSONObject(k))
                    digestRv.adapter?.notifyDataSetChanged()
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
        load()
    }
}
