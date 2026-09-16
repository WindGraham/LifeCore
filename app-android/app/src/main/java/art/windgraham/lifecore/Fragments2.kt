package art.windgraham.lifecore

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject

/** 通道页：webhook 通道的注册/查看/删除（registrar，agent.md §2.1）。 */
class ChannelsFragment : Fragment() {
    private val rows = mutableListOf<JSONObject>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_list, c, false)
        val rv = v.findViewById<RecyclerView>(R.id.listRv)
        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipe)
        lateinit var loadFn: () -> Unit
        val fab = v.findViewById<FloatingActionButton>(R.id.fab)

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = rows[pos]
                h.itemView.findViewById<TextView>(R.id.chName).text = o.getString("name")
                h.itemView.findViewById<TextView>(R.id.chMeta).text =
                    "${o.getString("archetype")} · ${o.getString("uplink_level")} · ${o.getString("channel_id")}"
                h.itemView.setOnLongClickListener {
                    AlertDialog.Builder(context).setTitle("删除通道 ${o.getString("name")}？")
                        .setPositiveButton("删除") { _, _ ->
                            Api.bg(requireContext()) {
                                Api.call("DELETE", "/v1/channels/" + o.getString("channel_id"))
                                Api.ui { loadFn() }
                            }
                        }.setNegativeButton("取消", null).show()
                    true
                }
            }
        }
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter

        fab.setOnClickListener {
            val ctx = requireContext()
            val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
            val name = EditText(ctx).apply { hint = "通道名（如 wechat-monitor）" }
            val arch = Spinner(ctx)
            arch.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("message","metric","file","task","calendar","alert","result"))
            val uplink = Spinner(ctx)
            uplink.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("AB","A","ABC"))
            val mode = Spinner(ctx)
            mode.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("notify","digest","alert","silent"))
            layout.addView(name); layout.addView(arch); layout.addView(uplink); layout.addView(mode)
            AlertDialog.Builder(ctx).setTitle("注册通道").setView(layout)
                .setPositiveButton("注册") { _, _ ->
                    Api.bg(ctx) {
                        val body = JSONObject().put("name", name.text.toString().trim())
                            .put("archetype", arch.selectedItem.toString())
                            .put("uplink_level", uplink.selectedItem.toString())
                            .put("report_policy", JSONObject().put("mode", mode.selectedItem.toString()))
                        val r = Api.postJson("/v1/channels", body)
                        val info = "ingest_url:\n${r.getString("ingest_url")}\n\nsecret:\n${r.getString("secret")}"
                        Api.ui {
                            AlertDialog.Builder(ctx).setTitle("通道已创建（点击外部关闭，请立即保存）")
                                .setMessage(info)
                                .setPositiveButton("复制") { _, _ ->
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("channel", info))
                                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                                }.show()
                            loadFn()
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }

        loadFn = { Api.bg(requireContext()) {
            val r = Api.getJson("/v1/channels")
            val got = Api.arr(r, "channels")
            Api.ui {
                rows.clear(); for (k in 0 until got.length()) rows.add(got.getJSONObject(k))
                adapter.notifyDataSetChanged(); swipe.isRefreshing = false
            }
        } }
        swipe.setOnRefreshListener { loadFn() }; loadFn()
        return v
    }
}

/** 任务页：cron/巡查任务（/api/jobs 透传）。 */
class JobsFragment : Fragment() {
    private val rows = mutableListOf<JSONObject>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_list, c, false)
        val rv = v.findViewById<RecyclerView>(R.id.listRv)
        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipe)
        lateinit var loadFn: () -> Unit
        val fab = v.findViewById<FloatingActionButton>(R.id.fab)

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object :
                RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_job, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val o = rows[pos]
                val jid = o.optString("id", o.optString("job_id", ""))
                h.itemView.findViewById<TextView>(R.id.jobName).text =
                    o.optString("name", o.optString("prompt", jid).take(60))
                h.itemView.findViewById<TextView>(R.id.jobMeta).text =
                    "${o.optString("schedule", o.optString("cron", "?"))} · ${if (o.optBoolean("paused", false)) "已暂停" else "运行中"}"
                fun act(a: String) = Api.bg(requireContext()) {
                    if (a == "delete") Api.call("DELETE", "/v2/jobs/$jid")
                    else Api.postJson("/v2/jobs/$jid/$a")
                    Api.ui { loadFn() }
                }
                h.itemView.findViewById<View>(R.id.btnRun).setOnClickListener { act("run") }
                h.itemView.findViewById<View>(R.id.btnPause).setOnClickListener { act(if (o.optBoolean("paused", false)) "resume" else "pause") }
                h.itemView.findViewById<View>(R.id.btnDel).setOnClickListener {
                    AlertDialog.Builder(context).setTitle("删除任务？").setPositiveButton("删除") { _, _ -> act("delete") }
                        .setNegativeButton("取消", null).show()
                }
            }
        }
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter

        fab.setOnClickListener {
            val ctx = requireContext()
            val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
            val name = EditText(ctx).apply { hint = "任务名" }
            val sched = EditText(ctx).apply { hint = "调度，如 every 2h / 0 9 * * * / 30m" }
            val prompt = EditText(ctx).apply { hint = "任务指令（巡查什么、汇报什么）" }
            layout.addView(name); layout.addView(sched); layout.addView(prompt)
            AlertDialog.Builder(ctx).setTitle("新建定时/巡查任务").setView(layout)
                .setPositiveButton("创建") { _, _ ->
                    Api.bg(ctx) {
                        Api.postJson("/v2/jobs", JSONObject()
                            .put("name", name.text.toString())
                            .put("schedule", sched.text.toString())
                            .put("prompt", prompt.text.toString()))
                        Api.ui { loadFn() }
                    }
                }.setNegativeButton("取消", null).show()
        }

        loadFn = { Api.bg(requireContext()) {
            val r = Api.getJson("/v2/jobs")
            val got = Api.arr(r, "data")
            Api.ui {
                rows.clear(); for (k in 0 until got.length()) rows.add(got.getJSONObject(k))
                adapter.notifyDataSetChanged(); swipe.isRefreshing = false
            }
        } }
        swipe.setOnRefreshListener { loadFn() }; loadFn()
        return v
    }
}
