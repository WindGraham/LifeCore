package art.windgraham.lifecore

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * 线程详情页：单议题时间线（GET /v2/notify/threads/{tid}/items，旧→新阅读顺序）。
 *
 * - AI 条目左气泡、用户决议右气泡（IM 样式）
 * - 底部 awaiting 时显示 options 按钮组
 * - 长按决议项 → 撤销（设计原则 9；POST /v2/notify/items/{id}/feedback action=undo）
 * - 多 kind：normal / resume（[续报]）/ system
 */
class ThreadDetailActivity : AppCompatActivity() {
    private val tid: Int get() = intent.getIntExtra("thread_id", -1)
    private val main = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<JSONObject>()
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private lateinit var optBar: LinearLayout
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PairStore.init(this)
        if (!PairStore.isPaired()) { finish(); return }
        setContentView(R.layout.activity_thread)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        optBar = findViewById(R.id.optBar)
        val rv = findViewById<RecyclerView>(R.id.threadRv)
        adapter = buildAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        // P1-S3：header 增加"查看 timeline"按钮 —— 复用 thread items（resolved + resume 全包含）
        findViewById<MaterialButton>(R.id.btnTimeline)?.setOnClickListener { loadTimeline() }
        load()
    }

    /** P1-S3：链路全景——展示 thread 全部 items（包含 resolved + resume）。 */
    private fun loadTimeline() {
        if (tid < 0) return
        Thread {
            try {
                val r = Api.threadTimeline(tid.toLong())
                val got = Api.arr(r, "items")
                main.post {
                    val list = mutableListOf<JSONObject>()
                    for (k in 0 until got.length()) list.add(got.getJSONObject(k))
                    // 排序按 id（旧→新阅读顺序）
                    list.sortBy { it.optInt("id", 0) }
                    rows.clear(); rows.addAll(list)
                    adapter.notifyDataSetChanged()
                    renderOptions()
                    toolbar.title = getString(R.string.title_thread_timeline) + " #$tid（${list.size}条）"
                }
            } catch (e: Exception) {
                main.post { Toast.makeText(this, Api.errText(e), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun buildAdapter(): RecyclerView.Adapter<RecyclerView.ViewHolder> {
        return object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            // view type: 0 = ai / 1 = user / 2 = system
            override fun getItemViewType(pos: Int): Int {
                val o = rows[pos]
                val res = o.optString("resolution")
                return when {
                    !res.isNullOrBlank() -> 1
                    o.optString("kind") == "system" -> 2
                    else -> 0
                }
            }
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(
                LayoutInflater.from(p.context).inflate(
                    when (t) {
                        0 -> R.layout.item_tmsg_ai
                        1 -> R.layout.item_tmsg_user
                        else -> R.layout.item_tmsg_sys
                    }, p, false)) {}

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = rows[pos]
                val time = Api.fmtTime(o.optString("created_at", ""))
                val content = h.itemView.findViewById<TextView>(R.id.msgContent)
                val tv = h.itemView.findViewById<TextView>(R.id.msgTime)
                when (getItemViewType(pos)) {
                    0 -> {
                        content.text = o.optString("summary", "(无摘要)")
                        tv.text = time
                    }
                    1 -> {
                        content.text = "你选择了：${resolutionLabel(o.optString("resolution"))}"
                        tv.text = time
                        // 设计原则 9：长按决议项 → 撤销
                        h.itemView.setOnLongClickListener { showUndo(o); true }
                    }
                    2 -> {
                        content.text = o.optString("summary", "(系统消息)")
                        tv.text = time
                    }
                }
            }
        }
    }

    private fun resolutionLabel(r: String) = when (r) {
        "actioned" -> "✓ 已办"
        "dismissed" -> "已忽略"
        "snooze" -> "⏰ 稍后"
        "undo" -> "↶ 已撤销"
        else -> r
    }

    private fun showUndo(o: JSONObject) {
        val id = o.optInt("id", -1)
        if (id < 0) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("撤销这条决策？")
            .setMessage("通知「${o.optString("summary", "").take(60)}」会被恢复为待处理状态。")
            .setPositiveButton("撤销") { _, _ ->
                Thread {
                    try {
                        Api.notifyFeedback(id, "undo")
                        main.post { load() }
                    } catch (e: Exception) {
                        main.post { Toast.makeText(this, Api.errText(e), Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun load() {
        if (tid < 0) { finish(); return }
        Thread {
            try {
                val r = Api.notifyThreadItems(tid)
                val got = Api.arr(r, "items")
                val list = mutableListOf<JSONObject>()
                for (k in 0 until got.length()) list.add(got.getJSONObject(k))
                main.post {
                    toolbar.title = "事项线程 #$tid"
                    rows.clear(); rows.addAll(list)
                    adapter.notifyDataSetChanged()
                    renderOptions()
                }
            } catch (e: Exception) {
                main.post { Toast.makeText(this, Api.errText(e), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** 底部 options 按钮组：仅当当前最后一条仍 awaiting_feedback。 */
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
                    .apply {
                        marginStart = if (k == 0) 0 else 8
                        marginEnd = if (k == n - 1) 0 else 8
                    }
                setOnClickListener {
                    Thread {
                        try {
                            Api.notifyFeedback(iid, action,
                                minutes = if (action == "snooze") 30 else null)
                            main.post { load() }
                        } catch (e: Exception) {
                            main.post { Toast.makeText(this@ThreadDetailActivity, Api.errText(e), Toast.LENGTH_LONG).show() }
                        }
                    }.start()
                }
            }
            optBar.addView(btn)
        }
    }
}