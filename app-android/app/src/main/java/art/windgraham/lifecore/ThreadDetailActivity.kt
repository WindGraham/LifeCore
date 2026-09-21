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
 * v0.3 线程详情页：多源整合时间线（IM 样式）。
 *
 * 数据源：/v2/threads/{tid}/conversation 返回 entries[]，每条含 kind 字段：
 *  - **channel_raw** → 左侧 + 中性灰 + 通道 chip（原始通道消息，未 AI 处理）
 *  - **ai_reply**    → 左侧 + primary 蓝绿气泡（VPS 核心 agent 回复）
 *  - **user_decision** → 右侧 + primaryContainer 强调（你的决议）
 *  - **resume**      → 左侧 + 黄色警告（VPS 续报）
 *  - **system**      → 居中灰色小字
 *
 * 每条带时间戳 + 来源标签 + 优先级色点。
 */
class ThreadDetailActivity : AppCompatActivity() {
    private val tid: Int get() = intent.getIntExtra("thread_id", -1)
    private val main = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<JSONObject>()
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private lateinit var optBar: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var emptyTv: TextView

    private val V_AI = 0
    private val V_USER = 1
    private val V_RESUME = 2
    private val V_SYS = 3
    private val V_CHANNEL = 4
    private val V_CHANNEL_RAW = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PairStore.init(this)
        if (!PairStore.isPaired()) { finish(); return }
        setContentView(R.layout.activity_thread)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        optBar = findViewById(R.id.optBar)
        emptyTv = findViewById(R.id.threadEmpty)
        val rv = findViewById<RecyclerView>(R.id.threadRv)
        adapter = buildAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        findViewById<MaterialButton>(R.id.btnTimeline)?.setOnClickListener { load() }
        load()
    }

    private fun viewType(o: JSONObject): Int {
        return when (o.optString("kind", "")) {
            "ai_reply" -> V_AI
            "user_decision" -> V_USER
            "resume" -> V_RESUME
            "system" -> V_SYS
            "channel_raw" -> V_CHANNEL_RAW
            else -> V_CHANNEL_RAW   // v0.3 老数据回退：所有 channel 类型都视为原始消息（gray bubble）
        }
    }

    private fun buildAdapter(): RecyclerView.Adapter<RecyclerView.ViewHolder> {
        return object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun getItemViewType(pos: Int) = viewType(rows[pos])

            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val layout = when (t) {
                    V_AI -> R.layout.item_tmsg_ai
                    V_USER -> R.layout.item_tmsg_user
                    V_RESUME -> R.layout.item_tmsg_resume
                    V_SYS -> R.layout.item_tmsg_sys
                    V_CHANNEL_RAW -> R.layout.item_tmsg_channel_raw
                    else -> R.layout.item_tmsg_channel
                }
                return object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(layout, p, false)) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = rows[pos]
                val time = Api.fmtTime(o.optString("ts", ""))
                val content = h.itemView.findViewById<TextView>(R.id.msgContent)
                val tv = h.itemView.findViewById<TextView>(R.id.msgTime)
                val body = o.optString("body", "").ifBlank { "(无内容)" }
                val source = o.optString("source", "")
                val pri = o.optString("priority", "normal").uppercase()
                when (getItemViewType(pos)) {
                    V_AI -> {
                        content.text = body
                        tv.text = "🤖 VPS 核心 · ${pri} · $time"
                    }
                    V_USER -> {
                        val res = if (o.isNull("resolution")) null else o.optString("resolution", "")
                        content.text = "${resolutionLabel(res ?: "")}\n${body.take(200)}"
                        tv.text = "你 · $time"
                        h.itemView.setOnLongClickListener {
                            val id = o.optInt("id", -1)
                            if (id > 0) showUndo(id, body); true
                        }
                    }
                    V_RESUME -> {
                        content.text = body
                        tv.text = "⤴ 续报 · ${pri} · $time"
                    }
                    V_SYS -> {
                        content.text = body
                        tv.text = "系统 · $time"
                    }
                    V_CHANNEL_RAW -> {
                        content.text = body
                        val chip = h.itemView.findViewById<TextView>(R.id.msgChannel)
                        val chName = if (o.isNull("channel_name")) null else o.optString("channel_name", "")
                        chip.text = "📡 ${chName ?: source.take(8)}"
                        tv.text = "通道原始消息 · $time"
                    }
                    V_CHANNEL -> {
                        content.text = body
                        val chip = h.itemView.findViewById<TextView>(R.id.msgChannel)
                        chip.text = "📡 $source"
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
        else -> "✓ 已处理"
    }

    private fun showUndo(itemId: Int, body: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("撤销这条决策？")
            .setMessage("通知「${body.take(60)}」会被恢复为待处理状态。")
            .setPositiveButton("撤销") { _, _ ->
                Thread {
                    try {
                        Api.notifyFeedback(itemId, "undo")
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
                // 优先尝试新版多源端点；VPS 还没部署时回退到老端点
                val r = try {
                    Api.threadConversation(tid)
                } catch (convEx: Exception) {
                    // 回退：把 /v2/notify/threads/{tid}/items 转成 entries[] 形态
                    val items = Api.notifyThreadItems(tid)
                    val got = Api.arr(items, "items")
                    val entries = org.json.JSONArray()
                    for (k in 0 until got.length()) {
                        val o = got.getJSONObject(k)
                        val res = if (o.isNull("resolution")) null else o.optString("resolution")
                        val kindLatest = o.optString("kind", "normal")
                        val ch = o.optString("channel_id", "")
                        val kind = when {
                            kindLatest == "system" -> "system"
                            !res.isNullOrBlank() -> "user_decision"
                            kindLatest == "resume" -> "resume"
                            ch == "api_server" || ch == "vps" -> "ai_reply"
                            else -> "channel_raw"
                        }
                        val entry = org.json.JSONObject()
                        entry.put("kind", kind)
                        entry.put("ts", o.optString("created_at", ""))
                        entry.put("body", o.optString("summary", ""))
                        entry.put("source", ch)
                        entry.put("priority", o.optString("priority", "normal"))
                        entry.put("event_seq", o.optInt("event_seq", 0))
                        entry.put("id", o.optInt("id", 0))
                        entry.put("channel_id", ch)
                        entry.put("channel_name", if (o.isNull("channel_name")) "" else o.optString("channel_name", ""))
                        entry.put("channel_archetype", if (o.isNull("channel_archetype")) "" else o.optString("channel_archetype", ""))
                        entries.put(entry)
                    }
                    org.json.JSONObject().put("entries", entries)
                }
                val got = Api.arr(r, "entries")
                val list = mutableListOf<JSONObject>()
                for (k in 0 until got.length()) {
                    val o = got.getJSONObject(k)
                    val body = o.optString("body", "")
                    if (o.optString("kind") == "system" && body.isBlank()) continue
                    list.add(o)
                }
                main.post {
                    toolbar.title = "事项线程 #$tid"
                    rows.clear(); rows.addAll(list)
                    adapter.notifyDataSetChanged()
                    renderOptions()
                    emptyTv.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                main.post {
                    Toast.makeText(this, "加载失败：${Api.errText(e)}", Toast.LENGTH_LONG).show()
                    emptyTv.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }.start()
    }

    private fun renderOptions() {
        optBar.removeAllViews()
        val lastAi = rows.lastOrNull { o ->
            o.optString("kind") == "ai_reply" &&
            (o.isNull("resolution") || o.optString("resolution").isBlank())
        } ?: run { optBar.visibility = View.GONE; return }
        val iid = lastAi.optInt("id", -1)
        if (iid <= 0) { optBar.visibility = View.GONE; return }
        val opts = Api.arr(lastAi, "options")
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
