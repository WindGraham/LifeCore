package art.windgraham.lifecore

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import art.windgraham.lifecore.ui.inbox.InboxFragment
import art.windgraham.lifecore.ui.owner.OwnerOnlyFragment
import art.windgraham.lifecore.ui.channels.ChannelsFragment
import art.windgraham.lifecore.ui.jobs.JobsFragment
import art.windgraham.lifecore.ui.settings.SettingsFragment

/**
 * v0.3 单 Activity + 多 Fragment 主入口。
 *
 * - 默认页 = InboxFragment（合并首页：会话 + 通知合一，按时间排序）
 * - 抽屉精简到 5 项（收件箱 / 我的专属 / 通道 / 任务 / 设置）
 * - 顶栏：☰ 抽屉 + 标题
 * - 删除：nav_today（合并到 Inbox 的 Hero 卡片）、nav_notify（合并到 Inbox）、nav_events（合并到 Inbox）、nav_sessions（合并到 Inbox）
 * - 配对状态：未配对 → 跳 PairActivity（PairStore 全局可查）
 * - 打开 App 自愈 PlayService（设计原则 1：保证 WS 在线）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: MaterialToolbar

    @IdRes private var currentId: Int = R.id.nav_inbox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PairStore.init(this)
        // 设计原则 6：未配对引导
        if (!PairStore.isPaired()) {
            startActivity(Intent(this, PairActivity::class.java))
            finish(); return
        }
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        drawer = findViewById(R.id.drawer)
        navView = findViewById(R.id.navView)

        setSupportActionBar(toolbar)
        val toggle = ActionBarDrawerToggle(this, drawer, toolbar,
            R.string.nav_open_drawer, R.string.nav_close_drawer)
        drawer.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener { item ->
            if (handleNav(item.itemId)) {
                drawer.closeDrawer(GravityCompat.START)
                true
            } else false
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_settings -> { switchTo(R.id.nav_settings, getString(R.string.title_settings)); true }
                else -> false
            }
        }

        if (savedInstanceState == null) switchTo(R.id.nav_inbox, getString(R.string.title_inbox))
        ensurePlayService()
    }

    /** 抽屉 + 工具栏菜单通用 nav handler；false = 不该切。 */
    private fun handleNav(id: Int): Boolean {
        return when (id) {
            R.id.nav_inbox -> switchTo(R.id.nav_inbox, getString(R.string.title_inbox))
            R.id.nav_owner -> switchTo(R.id.nav_owner, getString(R.string.title_owner))
            R.id.nav_channels -> switchTo(R.id.nav_channels, getString(R.string.title_channels))
            R.id.nav_jobs -> switchTo(R.id.nav_jobs, getString(R.string.title_jobs))
            R.id.nav_settings -> switchTo(R.id.nav_settings, getString(R.string.title_settings))
            else -> false
        }
    }

    private fun switchTo(id: Int, title: String): Boolean {
        val frag: Fragment = when (id) {
            R.id.nav_inbox -> InboxFragment()
            R.id.nav_owner -> OwnerOnlyFragment()
            R.id.nav_channels -> ChannelsFragment()
            R.id.nav_jobs -> JobsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> return false
        }
        toolbar.title = title
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, frag, id.toString())
            .commit()
        currentId = id
        // 同步 nav drawer 高亮
        navView.setCheckedItem(id)
        return true
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.top_bar, menu)
        return true
    }

    /** 设计原则 4：保证 WS 在线；用户从 Recent 划掉 / OOM 后自愈。 */
    private fun ensurePlayService() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(Intent(this, PlayService::class.java))
            } else {
                startService(Intent(this, PlayService::class.java))
            }
        }.onFailure {
            Toast.makeText(this, "后台服务启动失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
