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

/** 会话对话：选中的 agent 会话；slash 命令（/model /personality /new）原生透传；
 *  语音输入（MiniMax STT 代理）+ 播报（MiniMax TTS 代理）。 */
class ChatActivity : AppCompatActivity() {
    private lateinit var sid: String
    private val msgs = mutableListOf<Pair<String, String>>() // role to content
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
        adapter = MsgAdapter(msgs)
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
            val loaded = mutableListOf<Pair<String, String>>()
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                val role = m.optString("role", m.optString("author", "?"))
                val content = m.optString("content", m.optString("text", m.toString()))
                if (content.isNotBlank()) loaded.add(role to content)
            }
            Api.ui {
                msgs.clear(); msgs.addAll(loaded.takeLast(100))
                adapter.notifyDataSetChanged()
                findViewById<RecyclerView>(R.id.chatRv).scrollToPosition(maxOf(0, msgs.size - 1))
            }
        }
    }

    private fun send(text: String) {
        msgs.add("user" to text); adapter.notifyItemInserted(msgs.size - 1)
        scroll()
        Api.bg(this) {
            val r = Api.postJson("/v2/sessions/$sid/chat", JSONObject().put("message", text))
            val reply = r.optString("response", r.optString("content",
                r.optString("message", r.optString("text", r.toString().take(500)))))
            Api.ui { msgs.add("assistant" to reply); adapter.notifyItemInserted(msgs.size - 1); scroll() }
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
        toast("录音中…再点一次结束")
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

    inner class MsgAdapter(private val data: List<Pair<String, String>>) :
        RecyclerView.Adapter<MsgAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val role: TextView = v.findViewById(R.id.msgRole)
            val content: TextView = v.findViewById(R.id.msgContent)
            val speakBtn: ImageButton = v.findViewById(R.id.btnSpeak)
        }
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) = VH(
            LayoutInflater.from(p.context).inflate(R.layout.item_message, p, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val (role, content) = data[pos]
            h.role.text = if (role == "user") "你" else "agent"
            h.content.text = content
            h.speakBtn.visibility = if (role == "user") View.GONE else View.VISIBLE
            h.speakBtn.setOnClickListener { speak(content) }
        }
    }
}
