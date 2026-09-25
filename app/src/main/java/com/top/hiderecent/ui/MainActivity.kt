package com.top.hiderecent.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.top.hiderecent.PrefsBridge
import com.top.hiderecent.R
import java.util.concurrent.CountDownLatch

/**
 * 模块 UI：列出所有已安装应用，勾选 = 从最近任务隐藏。
 *
 * 顶部结构（与 PackageViewer 视觉一致，但不依赖主题 ActionBar）：
 *   statusBarSpacer  → 运行时按真实状态栏高度撑开，背景 colorPrimaryDark #3700B3
 *   toolbar          → 显式 Toolbar，背景 colorPrimary #6200EE，标题白色
 * 这样在「系统尊重 statusBarColor」和「ROM 强制 edge-to-edge」两种情况下都不会出现
 * 「状态栏紫了、标题栏被盖住不见了」。
 *
 * 性能：并行加载 label（4 线程）、图标懒加载、列表缓存（二次打开秒开）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val KEY_HIDE = "hide_list"

        /** 「关于」跳转的项目仓库地址 */
        const val REPO_URL = "https://github.com/GTian5418/HiddenQuest"

        /** 轻进程/跨实例缓存：二次打开直接用，无需重新枚举包 */
        private data class CachedApp(val pkg: String, val label: String, val isSystem: Boolean, val installTime: Long)
        private var cache: List<CachedApp>? = null
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var loadingText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter

    private var allItems: List<AppAdapter.AppItem> = emptyList()
    private var hidden: HashSet<String> = HashSet()

    // 排序/筛选状态
    private var orderByName = true
    private var showSystem = true
    private var showUser = true
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 顶部：显式 Toolbar + 动态状态栏占位（两种情况都不翻车）
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        prefs = getSharedPreferences(PrefsBridge.PREFS_NAME, MODE_PRIVATE)
        loadingText = findViewById(R.id.loadingText)
        recyclerView = findViewById(R.id.appList)
        // loadingText 已就绪后再装 insets 监听
        applyStatusBarSpacer()

        // 旧数据迁移：StringSet → String
        try {
            val oldSet = prefs.getStringSet(KEY_HIDE, null)
            if (oldSet != null && oldSet.isNotEmpty()) {
                prefs.edit().putString(KEY_HIDE, oldSet.joinToString(","))
                    .putStringSet(KEY_HIDE, null).apply()
            }
        } catch (_: ClassCastException) {
            // 已经是 String 格式
        }

        hidden = prefs.getString(KEY_HIDE, null)?.split(',')?.filter { it.isNotEmpty() }?.toHashSet()
            ?: HashSet()

        // RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter(packageManager) { item, checked ->
            setHidden(item.pkg, checked)
        }
        recyclerView.adapter = adapter

        // 缓存命中：立即显示，后台再刷新
        cache?.let { c ->
            allItems = c.map {
                AppAdapter.AppItem(it.pkg, it.label, it.isSystem, it.installTime, hidden.contains(it.pkg))
            }
            applyFilter()
        }

        // 并行加载应用列表（4 线程跑 label，不加载图标）
        Thread { loadApps() }.start()
    }

    /**
     * 状态栏占位：把 statusBarSpacer 的高度设成真实状态栏高度。
     *
     * - 正常情况（系统尊重 statusBarColor，decorFitsSystemWindows=true）：insets.top = 0，
     *   占位高度 0，状态栏就是主题里的 colorPrimaryDark 深紫。
     * - ROM 强制 edge-to-edge（ColorOS / Android 15）：内容会顶到状态栏下面，
     *   此时 insets.top = 真实高度，占位撑开，标题栏被挤到状态栏下方，不会「不见了」。
     */
    private fun applyStatusBarSpacer() {
        val spacer: View = findViewById(R.id.statusBarSpacer)
        val lp = spacer.layoutParams
        ViewCompat.setOnApplyWindowInsetsListener(spacer) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (lp.height != top) {
                lp.height = top
                v.layoutParams = lp
            }
            // 手势导航时避免底部状态文字被导航条压住
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (loadingText.paddingBottom != bottom) {
                loadingText.setPadding(0, 0, 0, bottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(spacer)
    }

    /** 菜单挂在显式 Toolbar 上（已 setSupportActionBar） */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        // 搜索
        val searchView = menu.findItem(R.id.app_bar_search)?.actionView as? SearchView
        if (searchView != null) {
            searchView.queryHint = getString(R.string.search)
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(newText: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    query = newText ?: ""
                    applyFilter()
                    return true
                }
            })
        }
        return true
    }

    /** 勾选态每次弹出前同步一次：名单可能在列表勾选里被改过 */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.hide_self)?.isChecked = hidden.contains(PrefsBridge.MODULE_PKG)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.order_by_name -> {
                orderByName = true
                item.isChecked = true
                applyFilter()
                return true
            }
            R.id.order_by_time -> {
                orderByName = false
                item.isChecked = true
                applyFilter()
                return true
            }
            R.id.show_system_app -> {
                // 显式反转并回写，避免与框架的自动勾选叠加导致状态错乱
                showSystem = !showSystem
                item.isChecked = showSystem
                applyFilter()
                return true
            }
            R.id.show_user_app -> {
                showUser = !showUser
                item.isChecked = showUser
                applyFilter()
                return true
            }
            R.id.hide_self -> {
                // 隐藏自身：把本模块包名塞进同一份名单，桌面侧 filterTask 会同样滤掉自己。
                // 以「名单里有没有自己」为唯一真值，显式回写，避免与框架自动勾选叠加。
                val next = !hidden.contains(PrefsBridge.MODULE_PKG)
                setHidden(PrefsBridge.MODULE_PKG, next)
                item.isChecked = next
                return true
            }
            R.id.about -> {
                openRepo()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * 统一的隐藏名单写入口：内存 → 落盘 → 广播推送 → 列表/计数同步。
     *
     * 落盘时**写空串而不是删键**：键被删掉后 remote preferences 通道读不到值会返回 null
     * （含义是「这一级没有应答」），链式查找就会继续落到磁盘缓存上，把上一次的旧名单又翻出来，
     * 于是「取消全部隐藏」反而失效。空串是「合法的空名单」，语义明确。
     *
     * 广播必须发：桌面进程靠它零 IPC 直接拿到完整名单，模块进程随后被最近任务划掉也不受影响。
     */
    private fun setHidden(pkg: String, checked: Boolean) {
        if (checked) hidden.add(pkg) else hidden.remove(pkg)
        prefs.edit().putString(KEY_HIDE, hidden.joinToString(",")).apply()
        PrefsBridge.notifyPrefsChanged(this, hidden)
        adapter.setChecked(pkg, checked)
        updateStatusText()
    }

    /** 「关于」：用系统浏览器打开项目仓库；设备上没有浏览器时给个提示而不是崩溃 */
    private fun openRepo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.open_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 并行枚举包 + 加载 label，图标留给适配器懒加载 */
    private fun loadApps() {
        val pm = packageManager
        val packages = pm.getInstalledPackages(0)
        val n = packages.size
        val threads = 4
        val chunk = (n + threads - 1) / threads
        val results = arrayOfNulls<MutableList<AppAdapter.AppItem>>(threads)
        val latch = CountDownLatch(threads)

        for (t in 0 until threads) {
            Thread {
                val list = mutableListOf<AppAdapter.AppItem>()
                val start = t * chunk
                val end = minOf(start + chunk, n)
                for (i in start until end) {
                    val p = packages[i]
                    val ai = p.applicationInfo ?: continue
                    val pkg = ai.packageName
                    // 只滤掉 android.* 这类伪包；模块自身要留在列表里，
                    // 否则「隐藏自身」勾了以后没法在主页取消。
                    if (pkg.startsWith("android.")) continue
                    val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Throwable) { continue }
                    list.add(
                        AppAdapter.AppItem(
                            pkg = pkg,
                            label = label,
                            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            installTime = p.firstInstallTime,
                            checked = hidden.contains(pkg)
                        )
                    )
                }
                results[t] = list
                latch.countDown()
            }.start()
        }

        latch.await()
        val items = results.filterNotNull().flatten()

        // 更新缓存
        cache = items.map { CachedApp(it.pkg, it.label, it.isSystem, it.installTime) }

        Handler(Looper.getMainLooper()).post {
            allItems = items
            applyFilter()
        }
    }

    /** 按当前排序/筛选/搜索条件刷新列表 */
    private fun applyFilter() {
        var list = allItems.filter { item ->
            (showSystem || !item.isSystem) && (showUser || item.isSystem)
        }
        if (query.isNotBlank()) {
            val q = query.lowercase()
            list = list.filter {
                it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
            }
        }
        list = if (orderByName) {
            list.sortedBy { it.label.lowercase() }
        } else {
            list.sortedByDescending { it.installTime }
        }
        adapter.submit(list)
        updateStatusText()
    }

    private fun updateStatusText() {
        loadingText.text = "共 ${allItems.size} 个应用，已隐藏 ${hidden.size} 个"
    }
}
