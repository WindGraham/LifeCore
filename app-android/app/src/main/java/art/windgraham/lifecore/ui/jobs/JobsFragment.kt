package art.windgraham.lifecore.ui.jobs

import android.app.AlertDialog
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
import art.windgraham.lifecore.PairStore
import art.windgraham.lifecore.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject

/** 任务页：cron/巡查任务（/api/jobs 透传）。 */
class JobsFragment : Fragment() {

    private val rows = mutableListOf<JSONObject>()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_list, c, false)
        val rv = v.findViewById<RecyclerView>(R.id.listRv)
        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.swipe)
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
                fun act(a: String) = Thread {
                    try {
                        when (a) {
                            "delete" -> Api.jobDelete(jid)
                            else -> Api.jobAction(jid, a)
                        }
                        main.post { load() }
                    } catch (e: Exception) {
                        main.post { Toast.makeText(context, Api.errText(e), Toast.LENGTH_LONG).show() }
                    }
                }.start()
                h.itemView.findViewById<View>(R.id.btnRun).setOnClickListener { act("run") }
                h.itemView.findViewById<View>(R.id.btnPause).setOnClickListener {
                    act(if (o.optBoolean("paused", false)) "resume" else "pause")
                }
                h.itemView.findViewById<View>(R.id.btnDel).setOnClickListener {
                    AlertDialog.Builder(context).setTitle("删除任务？")
                        .setPositiveButton("删除") { _, _ -> act("delete") }
                        .setNegativeButton("取消", null).show()
                }
            }
        }
        rv.layoutManager = LinearLayoutManager(context); rv.adapter = adapter

        fab.setOnClickListener { showCreate() }

        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    private fun showCreate() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val name = EditText(ctx).apply { hint = "任务名" }
        val sched = EditText(ctx).apply { hint = "调度，如 every 2h / 0 9 * * * / 30m" }
        val prompt = EditText(ctx).apply { hint = "任务指令（巡查什么、汇报什么）" }
        layout.addView(name); layout.addView(sched); layout.addView(prompt)
        AlertDialog.Builder(ctx).setTitle("新建定时/巡查任务").setView(layout)
            .setPositiveButton("创建") { _, _ ->
                Thread {
                    try {
                        Api.jobCreate(
                            name = name.text.toString(),
                            schedule = sched.text.toString(),
                            prompt = prompt.text.toString()
                        )
                        main.post { load() }
                    } catch (e: Exception) {
                        main.post { Toast.makeText(ctx, Api.errText(e), Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }.setNegativeButton("取消", null).show()
    }

    private fun load() {
        if (!PairStore.isPaired()) return
        Thread {
            try {
                val r = Api.jobs()
                val got = Api.arr(r, "data")
                main.post {
                    rows.clear()
                    for (k in 0 until got.length()) rows.add(got.getJSONObject(k))
                    val rv = view?.findViewById<RecyclerView>(R.id.listRv)
                    rv?.adapter?.notifyDataSetChanged()
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

    override fun onResume() { super.onResume(); load() }
}
