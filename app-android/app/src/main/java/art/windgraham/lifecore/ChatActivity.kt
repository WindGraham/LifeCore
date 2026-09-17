package art.windgraham.lifecore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

/**
 * 会话对话：流式对话（/v2/sessions/{sid}/chat/stream）+ 气泡渲染 + STT + 单条 TTS。
 *
 * - SSE 流：`event: chunk` / `data: {"text":"..."}` / `event: done` / `event: error`
 * - 工具角色 → 折叠成一行小字
 * - STT：录音 → /v2/asr → 填入输入框（用户在文件选择器手动上传走 Share intent）
 * - TTS：单条 AI 气泡播报
 * - 如果 intent 没传 sid → 列出历史 session 选一个进入（兜底）
 */
class ChatActivity : AppCompatActivity() {

    data class Msg(val kind: Int, val text: String)

    companion object {
        const val K_USER = 0
        const val K_AI = 1
        const val K_TOOL = 2
        const val K_AI_STREAM = 3   // 临时占位（流式中）

        /** 把 hermes 消息折叠成渲染条目。 */
        fun fold(role: String, content: String): Msg? {
            val c = content.trim()
            if (c.isBlank()) return null
            return when (role) {
                "user", "human" -> Msg(K_USER, c)
                "assistant", "ai" -> Msg(K_AI, c)
                else -> {
                    val label = runCatching {
                        val o = JSONObject(c)
                        when {
                            o.has("tools") -> "⚙ 工具清单已加载（${o.getJSONObject("tools").length()} 个）"
                            o.has("name") -> "⚙ 调用工具 · ${o.getString("name")}"
                            o.has("error") -> "⚙ 工具错误（已折叠）"
                            else -> "⚙ 工具消息（已折叠）"
                        }
                    }.getOrElse { "⚙ 工具消息（已折叠）" }
                    Msg(K_TOOL, label)
                }
            }
        }
    }

    private var sid: String = ""
    private val msgs = mutableListOf<Msg>()
    private lateinit var adapter: MsgAdapter
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private var streamAiIndex = -1    // 流式中 AI 气泡的下标

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PairStore.init(this)
        setContentView(R.layout.activity_chat)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        sid = intent.getStringExtra("session_id") ?: ""

        val rv = findViewById<RecyclerView>(R.id.chatRv)
        adapter = MsgAdapter()
        rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rv.adapter = adapter

        val input = findViewById<EditText>(R.id.chatInput)
        findViewById<MaterialButton>(R.id.btnSend).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.setText("")
            send(text)
        }
        findViewById<ImageButton>(R.id.btnMic).setOnClickListener { toggleRecord() }

        if (sid.isBlank()) {
            // 兜底：列出 session 选择（设计原则 6：未配对/无 sid 仍可用）
            listSessionsAndPick(toolbar)
        } else {
            toolbar.title = intent.getStringExtra("title") ?: sid
            loadHistory()
        }
    }

    private fun listSessionsAndPick(toolbar: MaterialToolbar) {
        toolbar.title = "选择会话"
        Api.bg(this) {
            try {
                val r = Api.sessions()
                val data = Api.arr(r, "data")
                val rows = mutableListOf<Pair<String, String>>()
                for (k in 0 until data.length()) {
                    val o = data.getJSONObject(k)
                    val id = o.optString("session_id", o.optString("id", ""))
                    val title = o.optString("title", o.optString("name", id.take(24)))
                    if (id.isNotBlank()) rows.add(id to title)
                }
                main.post {
                    if (rows.isEmpty()) {
                        Toast.makeText(this, "暂无会话，从抽屉进入通道/任务触发第一个会话", Toast.LENGTH_LONG).show()
                    } else {
                        val titles = rows.map { it.second }.toTypedArray()
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("选一个会话")
                            .setItems(titles) { _, which ->
                                sid = rows[which].first
                                toolbar.title = rows[which].second
                                msgs.clear(); adapter.notifyDataSetChanged()
                                loadHistory()
                            }
                            .setNeutralButton("新建") { _, _ -> createSession(toolbar) }
                            .show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, Api.errText(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createSession(toolbar: MaterialToolbar) {
        Api.bg(this) {
            try {
                val r = Api.sessionCreate("新会话 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())}")
                val newId = r.optString("session_id", r.optString("id", ""))
                main.post {
                    if (newId.isNotBlank()) {
                        sid = newId
                        toolbar.title = "新会话"
                        msgs.clear(); adapter.notifyDataSetChanged()
                        loadHistory()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, Api.errText(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadHistory() {
        if (sid.isBlank()) return
        Api.bg(this) {
            try {
                val r = Api.sessionMessages(sid)
                val data = Api.arr(r, "data")
                val loaded = mutableListOf<Msg>()
                for (i in 0 until data.length()) {
                    val m = data.getJSONObject(i)
                    val role = m.optString("role", m.optString("author", "?"))
                    val content = m.optString("content", m.optString("text", ""))
                    fold(role, content)?.let { loaded.add(it) }
                }
                main.post {
                    msgs.clear(); msgs.addAll(loaded.takeLast(150))
                    adapter.notifyDataSetChanged()
                    scroll()
                }
            } catch (e: Exception) {
                Toast.makeText(this, Api.errText(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun send(text: String) {
        msgs.add(Msg(K_USER, text)); adapter.notifyItemInserted(msgs.size - 1); scroll()
        // 占位流式 AI 气泡
        msgs.add(Msg(K_AI_STREAM, ""))
        streamAiIndex = msgs.size - 1
        adapter.notifyItemInserted(streamAiIndex)
        scroll()

        if (sid.isBlank()) {
            // 无 sid：先创建再发
            Api.bg(this) {
                try {
                    val r = Api.sessionCreate(text.take(20))
                    sid = r.optString("session_id", r.optString("id", ""))
                    title = sid
                    streamChat(text)
                } catch (e: Exception) {
                    main.post {
                        if (streamAiIndex >= 0) msgs[streamAiIndex] = Msg(K_AI, "⚠ " + Api.errText(e))
                        adapter.notifyItemChanged(streamAiIndex)
                    }
                }
            }
        } else {
            streamChat(text)
        }
    }

    /** SSE 流式对话（设计原则 4：IO 后台线程）。 */
    private fun streamChat(text: String) {
        Thread {
            try {
                val resp: Response = Api.callStream("POST", "/v2/sessions/$sid/chat/stream",
                    JSONObject().put("message", text))
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: "HTTP ${resp.code}"
                    main.post {
                        if (streamAiIndex >= 0) msgs[streamAiIndex] = Msg(K_AI, "⚠ HTTP ${resp.code}: ${err.take(200)}")
                        adapter.notifyItemChanged(streamAiIndex)
                    }
                    resp.close(); return@Thread
                }
                val src = resp.body?.byteStream() ?: run { resp.close(); return@Thread }
                src.bufferedReader().use { br: BufferedReader ->
                    var ev = ""
                    var data = StringBuilder()
                    while (true) {
                        val line = br.readLine() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) handleStreamEvent(ev, data.toString())
                                ev = ""; data = StringBuilder()
                            }
                            line.startsWith("event:") -> ev = line.substring(6).trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).trim())
                            }
                        }
                    }
                }
                resp.close()
                // 收尾：把流式占位转 K_AI
                main.post {
                    if (streamAiIndex >= 0 && msgs[streamAiIndex].kind == K_AI_STREAM) {
                        val final = msgs[streamAiIndex].text.ifBlank { "(无响应)" }
                        msgs[streamAiIndex] = Msg(K_AI, final)
                        adapter.notifyItemChanged(streamAiIndex)
                    }
                    streamAiIndex = -1
                }
            } catch (e: Exception) {
                main.post {
                    if (streamAiIndex >= 0) {
                        msgs[streamAiIndex] = Msg(K_AI, "⚠ " + Api.errText(e))
                        adapter.notifyItemChanged(streamAiIndex)
                    }
                }
            }
        }.start()
    }

    private fun handleStreamEvent(event: String, raw: String) {
        when (event) {
            "chunk" -> {
                val o = runCatching { JSONObject(raw) }.getOrNull()
                val piece = o?.optString("text", raw) ?: raw
                main.post {
                    if (streamAiIndex >= 0) {
                        val cur = msgs[streamAiIndex]
                        msgs[streamAiIndex] = Msg(K_AI_STREAM, cur.text + piece)
                        adapter.notifyItemChanged(streamAiIndex)
                        scroll()
                    }
                }
            }
            "error" -> {
                val o = runCatching { JSONObject(raw) }.getOrNull()
                val msg = o?.optString("message", raw) ?: raw
                main.post {
                    if (streamAiIndex >= 0) {
                        msgs[streamAiIndex] = Msg(K_AI_STREAM, "⚠ " + msg)
                        adapter.notifyItemChanged(streamAiIndex)
                    }
                }
            }
            // done/end/delta 等忽略
            else -> {}
        }
    }

    // ── STT ──

    private fun toggleRecord() {
        if (recorder != null) { stopRecord(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        recFile = File.createTempFile("lc_rec", ".m4a", cacheDir)
        recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder!!.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64000); setAudioSamplingRate(16000)
            setOutputFile(recFile!!.absolutePath)
            prepare(); start()
        }
        Toast.makeText(this, "● 录音中…再点一次结束", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecord() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        val f = recFile ?: return
        Toast.makeText(this, "识别中…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val (code, text) = Api.call("POST", "/v2/asr", raw = f.readBytes(), contentType = "audio/mp4")
                if (code !in 200..299) throw Api.ApiException(code, text)
                val stt = JSONObject(text).optString("text")
                main.post { findViewById<EditText>(R.id.chatInput).setText(stt) }
            } catch (e: Exception) {
                main.post { Toast.makeText(this, Api.errText(e), Toast.LENGTH_LONG).show() }
            } finally {
                f.delete()
            }
        }.start()
    }

    // ── TTS（设计原则 4：后台线程 IO）──

    private fun speak(text: String) {
        Thread {
            try {
                val (code, bytes) = Api.callBytes("POST", "/v2/tts",
                    body = JSONObject().put("text", text.take(500)))
                if (code != 200) throw Api.ApiException(code, String(bytes, Charsets.UTF_8))
                val f = File.createTempFile("lc_tts", ".mp3", cacheDir)
                f.writeBytes(bytes)
                main.post { play(f) }
            } catch (e: Exception) {
                main.post { Toast.makeText(this, Api.errText(e), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun play(f: File) {
        try {
            player?.release()
            player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scroll() {
        findViewById<RecyclerView>(R.id.chatRv).post {
            findViewById<RecyclerView>(R.id.chatRv).scrollToPosition(maxOf(0, msgs.size - 1))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { recorder?.release() }
        runCatching { player?.release() }
    }

    inner class MsgAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class UserVH(v: View) : RecyclerView.ViewHolder(v) {
            val content: TextView = v.findViewById(R.id.msgContent)
        }
        inner class AiVH(v: View) : RecyclerView.ViewHolder(v) {
            val content: TextView = v.findViewById(R.id.msgContent)
            val speakBtn: ImageButton = v.findViewById(R.id.btnSpeak)
        }
        inner class ToolVH(v: View) : RecyclerView.ViewHolder(v) {
            val content: TextView = v.findViewById(R.id.msgContent)
        }
        override fun getItemCount() = msgs.size
        override fun getItemViewType(pos: Int) = msgs[pos].kind
        override fun onCreateViewHolder(p: ViewGroup, vt: Int): RecyclerView.ViewHolder {
            val li = LayoutInflater.from(p.context)
            return when (vt) {
                K_USER -> UserVH(li.inflate(R.layout.item_msg_user, p, false))
                K_AI, K_AI_STREAM -> AiVH(li.inflate(R.layout.item_msg_ai, p, false))
                else -> ToolVH(li.inflate(R.layout.item_msg_tool, p, false))
            }
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val m = msgs[pos]
            when (h) {
                is UserVH -> h.content.text = m.text
                is AiVH -> {
                    if (m.kind == K_AI_STREAM) {
                        h.content.text = m.text.ifBlank { "…" }
                    } else {
                        Md.apply(h.content, m.text)
                    }
                    h.speakBtn.setOnClickListener { speak(m.text) }
                }
                is ToolVH -> h.content.text = m.text
            }
        }
    }
}