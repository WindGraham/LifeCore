package art.windgraham.lifecore

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/** 主界面：底部导航五个页（谷歌 Material 基本组件）。 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        if (Api.token == null) { startActivity(Intent(this, PairActivity::class.java)); finish(); return }
        setContentView(R.layout.activity_main)
        ensurePlayService()   // 打开 App 即自愈播报服务（服务通知被划掉/进程回收后恢复）
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_sessions -> show(SessionsFragment())
                R.id.nav_notify -> show(NotifyFragment())
                R.id.nav_channels -> show(ChannelsFragment())
                R.id.nav_jobs -> show(JobsFragment())
                R.id.nav_settings -> show(SettingsFragment())
                else -> false
            }
        }
        if (savedInstanceState == null) { nav.selectedItemId = R.id.nav_sessions }
    }

    private fun show(f: Fragment): Boolean {
        supportFragmentManager.beginTransaction().replace(R.id.container, f).commit()
        return true
    }

    private fun ensurePlayService() {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26)
                startForegroundService(Intent(this, PlayService::class.java))
            else startService(Intent(this, PlayService::class.java))
        }
    }
}
