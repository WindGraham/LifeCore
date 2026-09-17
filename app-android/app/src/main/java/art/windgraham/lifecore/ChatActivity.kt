package art.windgraham.lifecore

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
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
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.File

/** 会话对话：气泡渲染（用户右/AI 左/工具折叠成小字）；
 *  slash 命令原生透传；语音输入（STT 代理）+ 单条播报（TTS 代理）。 */
class ChatActivity : AppCompatActivity() {
    data class Msg(val kind: Int, val text: String)
    companion object {
        const val K_USER = 0
        const val K_AI = 1
        const val K_TOOL = 2
        /** 把 hermes 消息折叠成渲染条目：tool 角色 → 一行小字，超长 JSON 不展开。 */
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

    private lateinit var sid: String
    private val msgs = mutableListOf<Msg>()
    private lateinit var adapter: MsgAdapter
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        sid = intent.getStringExtra("session_id") ?: return finish()
        title = intent.getStringExtra("title") ?: sid

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
        loadHistory()
    }

    private fun loadHistory() {
        Api.bg(this) {
            val r = Api.getJson("/v2/sessions/$sid/messages")
            val data = Api.arr(r, "data")
            val loaded = mutableListOf<Msg>()
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                val role = m.optString("role", m.optString("author", "?"))
                val content = m.optString("content", m.optString("text", ""))
                fold(role, content)?.let { loaded.add(it) }
            }
            Api.ui {
                msgs.clear(); msgs.addAll(loaded.takeLast(150))
                adapter.notifyDataSetChanged()
                findViewById<RecyclerView>(R.id.chatRv).scrollToPosition(maxOf(0, msgs.size - 1))
            }
        }
    }

    private fun send(text: String) {
        msgs.add(Msg(K_USER, text)); adapter.notifyItemInserted(msgs.size - 1)
        scroll()
        Api.bg(this) {
            val r = Api.postJson("/v2/sessions/$sid/chat", JSONObject().put("message", text))
            val reply = r.optString("response", r.optString("content",
                r.optString("message", r.optString("text", ""))))
            Api.ui {
                if (reply.isNotBlank()) { msgs.add(Msg(K_AI, reply)); adapter.notifyItemInserted(msgs.size - 1) }
                scroll()
            }
        }
    }

    // ── 语音输入：录音 → /v2/stt → 填入输入框 ──
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
        toast("● 录音中…再点一次结束")
    }

    private fun stopRecord() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        val f = recFile ?: return
        toast("识别中…")
        Api.bg(this) {
            val (code, text) = Api.call("POST", "/v2/stt", raw = f.readBytes(), contentType = "audio/mp4")
            if (code !in 200..299) throw Api.ApiException(code, text)
            val stt = JSONObject(text).optString("text")
            Api.ui { findViewById<EditText>(R.id.chatInput).setText(stt) }
            f.delete()
        }
    }

    // ── 播报：/v2/tts → MediaPlayer ──
    private fun speak(text: String) {
        Api.bg(this) {
            val (code, bytes) = Api.callBytes("POST", "/v2/tts",
                body = JSONObject().put("text", text.take(500)))
            if (code != 200) throw Api.ApiException(code, String(bytes, Charsets.UTF_8))
            val f = File.createTempFile("lc_tts", ".mp3", cacheDir)
            f.writeBytes(bytes)
            Api.ui { play(f) }
        }
    }

    private fun play(f: File) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath); prepare(); start()
        }
    }

    private fun scroll() {
        findViewById<RecyclerView>(R.id.chatRv).post {
            findViewById<RecyclerView>(R.id.chatRv).scrollToPosition(maxOf(0, msgs.size - 1))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy(); recorder?.release(); player?.release()
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
                K_AI -> AiVH(li.inflate(R.layout.item_msg_ai, p, false))
                else -> ToolVH(li.inflate(R.layout.item_msg_tool, p, false))
            }
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val m = msgs[pos]
            when (h) {
                is UserVH -> h.content.text = m.text
                is AiVH -> {
                    h.content.text = m.text
                    h.speakBtn.setOnClickListener { speak(m.text) }
                }
                is ToolVH -> h.content.text = m.text
            }
        }
    }
}
