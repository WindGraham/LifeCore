package art.windgraham.lifecore

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

/** 配对：扫二维码（{v,url,fp,code}）或手输 code → 换长期设备凭证（docs/02 §5）。 */
class PairActivity : AppCompatActivity() {
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) onQr(result.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        if (Api.token != null) { goMain(); return }
        setContentView(R.layout.activity_pair)

        val baseInput = findViewById<EditText>(R.id.baseInput)
        baseInput.setText(Api.baseUrl)
        findViewById<MaterialButton>(R.id.btnScan).setOnClickListener {
            Api.baseUrl = baseInput.text.toString()
            scanner.launch(ScanOptions().apply { setPrompt("扫描 LifeCore 配对二维码"); setBeepEnabled(false) })
        }
        findViewById<Button>(R.id.btnPairManual).setOnClickListener {
            Api.baseUrl = baseInput.text.toString()
            val code = findViewById<EditText>(R.id.codeInput).text.toString().trim()
            if (code.length >= 4) pair(code, null) else toast("请输入配对码")
        }
    }

    private fun onQr(json: String) {
        try {
            val o = JSONObject(json)
            val code = o.optString("code"); val fp = o.optString("fp"); val url = o.optString("url")
            if (url.isNotBlank()) {
                Api.baseUrl = url
                findViewById<EditText>(R.id.baseInput).setText(url)
            }
            findViewById<TextView>(R.id.fpVerify).text = "服务器指纹：${fp.take(16)}…（请与网页核对）"
            if (Api.fingerprint != null && Api.fingerprint != fp) {
                toast("⚠️ 指纹与上次配对不一致，警惕中间人")
            }
            if (code.isNotBlank()) pair(code, fp) else toast("二维码缺少 code")
        } catch (e: Exception) { toast("二维码解析失败：${e.message}") }
    }

    private fun pair(code: String, fp: String?) {
        Api.bg(this) {
            val r = Api.postJson("/v1/pair", JSONObject()
                .put("code", code).put("device_name", android.os.Build.MODEL ?: "android"))
            Api.savePair(r.getString("device_token"), r.getString("fingerprint"))
            if (fp != null && r.getString("fingerprint") != fp)
                throw Exception("服务器指纹与二维码不符，已中止")
            Api.ui { toast("配对成功"); goMain() }
        }
    }

    private fun goMain() { startActivity(Intent(this, MainActivity::class.java)); finish() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
