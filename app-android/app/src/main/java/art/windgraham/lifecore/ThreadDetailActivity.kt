package art.windgraham.lifecore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/** 线程详情页：单议题时间线（契约 §1 GET /v2/notify/threads/{tid}/items，旧→新）。
 *  AI 条目左气泡、用户决议右气泡（IM 样式）；底部 awaiting 时显示 options 按钮组。 */
class ThreadDetailActivity : AppCompatActivity() {
    private val tid: Int get() = intent.getIntExtra("thread_id", -1)
    private val rows = mutableListOf<JSONObject>()
    private var titleText = "事项线程"
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private lateinit var optBar: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        setContentView(R.layout.activity_thread)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        optBar = findViewById(R.id.optBar)
        val rv = findViewById<RecyclerView>(R.id.threadRv)
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemViewType(pos: Int) =
                if (rows[pos].optString("resolution").isNullOrBlank()) 0 else 1
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(
                LayoutInflater.from(p.context).inflate(
                    if (t == 0) R.layout.item_tmsg_ai else R.layout.item_tmsg_user, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = rows[pos]
                val time = Api.fmtTime(o.optString("created_at", ""))
                if (getItemViewType(pos) == 0) {
                    h.itemView.findViewById<TextView>(R.id.msgContent).text = o.optString("summary", "(无摘要)")
                    h.itemView.findViewById<TextView>(R.id.msgTime).text = time
                } else {
                    h.itemView.findViewById<TextView>(R.id.msgContent).text =
                        "你选择了：${resolutionLabel(o.optString("resolution"))}"
                    h.itemView.findViewById<TextView>(R.id.msgTime).text = time
                }
            }
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        load()
    }

    private fun resolutionLabel(r: String) = when (r) {
        "actioned" -> "已办"
        "dismissed" -> "已忽略"
        "snooze" -> "稍后"
        else -> r
    }

    private fun load() {
        if (tid < 0) { finish(); return }
        Api.bg(this) {
            val r = Api.notifyThreadItems(tid)
            val got = Api.arr(r, "items")
            val list = mutableListOf<JSONObject>()
            for (k in 0 until got.length()) list.add(got.getJSONObject(k))
            Api.ui {
                titleText = "事项线程 #$tid"
                rows.clear(); rows.addAll(list)
                adapter.notifyDataSetChanged()
                renderOptions()
            }
        }
    }

    /** 底部 options 按钮组：仅当当前最后一条仍 awaiting_feedback（未决议）。 */
    private fun renderOptions() {
        optBar.removeAllViews()
        val last = rows.lastOrNull()
        if (last == null || last.optString("state") != "awaiting_feedback" ||
            !last.optString("resolution").isNullOrBlank()) { optBar.visibility = View.GONE; return }
        val iid = last.getInt("id")
        val opts = Api.arr(last, "options")
        val n = minOf(opts.length(), 3)
        if (n == 0) { optBar.visibility = View.GONE; return }
        optBar.visibility = View.VISIBLE
        for (k in 0 until n) {
            val label = opts.optString(k, "选项${k + 1}")
            val action = when {
                k == n - 1 && label.contains("稍后") -> "snooze"
                k == 0 -> "actioned"
                else -> "dismissed"
            }
            val btn = MaterialButton(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = if (k == 0) 0 else 8; marginEnd = if (k == n - 1) 0 else 8 }
                setOnClickListener {
                    Api.bg(this@ThreadDetailActivity) {
                        Api.notifyFeedback(iid, action, minutes = if (action == "snooze") 30 else null)
                        Api.ui { load() }
                    }
                }
            }
            optBar.addView(btn)
        }
    }
}
