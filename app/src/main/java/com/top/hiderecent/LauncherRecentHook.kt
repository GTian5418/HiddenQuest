package com.top.hiderecent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * 桌面进程 hook（ColorOS OplusLauncher，进程名 com.android.launcher）。
 *
 * 反编译 OplusLauncher.apk 定位的真正过滤点：
 *   com.oplus.quickstep.data.OplusRecentTasksFilter.filterTask(GroupTask): boolean
 * 调用方 com.android.quickstep.OplusRecentTasksListImpl.loadTasksInBackground():
 *   if (getFilter().filterTask(map)) { map = null; }   // true = 隐藏, false = 显示
 *
 * 旧 AOSP 路径 RecentTasks.isVisibleRecentTask 在 ColorOS 上是死代码（system hook 装上但从不触发），
 * 真正过滤在 launcher 侧的 OplusRecentTasksFilter。
 */
object LauncherRecentHook {

    private const val TAG = "${Main.TAG}/launch"
    private val hiddenSnapshot = HiddenPackagesSnapshot()
    private val refreshExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HideRecentTiles-launch-refresh").apply { isDaemon = true }
    }

    /** 诊断计数器：限制前 N 次调用打日志，避免刷屏 */
    private var diagCount = 0

    fun hook(module: Main, param: PackageLoadedParam) {
        val loader = param.defaultClassLoader
        var count = 0

        // 主 hook：OplusRecentTasksFilter.filterTask(GroupTask): boolean
        count += hookFilterTask(module, loader)

        // 诊断 hook：RecentsActivity.onCreate（确认 launcher 注入成功，不影响显示）
        count += hookDiag(module, loader)

        // 注册配置变更广播接收器：模块进程每次保存配置都会广播，桌面进程即时更新缓存，
        // 不再依赖轮询 / 不再依赖模块进程长期存活
        registerPrefsReceiver(module)
        refreshSnapshotAsync(module, "init")
        module.log(Log.INFO, TAG, "launcher hooks=$count")
    }

    /** 广播接收器强引用，避免被 GC 回收导致收不到广播 */
    private var prefsReceiver: BroadcastReceiver? = null

    /**
     * 动态注册配置变更接收器。
     *
     * 安全性：使用 signature 级权限 [Main.PREFS_PERMISSION] 作为 receiverPermission，
     * 只有同签名（即模块自身）的应用才能发送该广播。
     */
    private fun registerPrefsReceiver(module: Main) {
        if (prefsReceiver != null) return
        val ctx = currentLauncherContext() ?: run {
            module.log(Log.WARN, TAG, "no Context, prefs receiver not registered")
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val raw = i?.getStringExtra(Main.EXTRA_HIDE)
                if (raw != null) {
                    hiddenSnapshot.replace(parse(raw))
                } else {
                    refreshSnapshotAsync(module, "broadcast-no-extra")
                }
            }
        }
        val ok = try {
            ContextCompat.registerReceiver(
                ctx,
                receiver,
                IntentFilter(Main.ACTION_PREFS_CHANGED),
                Main.PREFS_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
            true
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "registerReceiver failed: $t")
            false
        }
        if (ok) {
            prefsReceiver = receiver
            module.log(Log.INFO, TAG, "prefs receiver registered")
        }
    }

    /** 取桌面进程可用的 Context（ActivityThread.currentApplication） */
    private fun currentLauncherContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val app = at.getMethod("currentApplication").invoke(null)
        app as? Context
    } catch (_: Throwable) {
        null
    }

    /**
     * hook OplusRecentTasksFilter.filterTask(GroupTask): boolean
     * 语义：返回 true = 隐藏该任务，false = 显示。
     * 我们在原方法执行后判断包名，若在隐藏集合则强制返回 true。
     */
    private fun hookFilterTask(module: Main, cl: ClassLoader): Int {
        val filterCls = try {
            cl.loadClass("com.oplus.quickstep.data.OplusRecentTasksFilter")
        } catch (_: Throwable) {
            module.log(Log.WARN, TAG, "OplusRecentTasksFilter not found (non-ColorOS?)")
            return 0
        }
        val groupTaskCls = try {
            cl.loadClass("com.android.quickstep.util.GroupTask")
        } catch (_: Throwable) { return 0 }
        val taskCls = try {
            cl.loadClass("com.android.systemui.shared.recents.model.Task")
        } catch (_: Throwable) { return 0 }

        val task1Field: Field = try {
            groupTaskCls.getDeclaredField("task1").apply { isAccessible = true }
        } catch (_: Throwable) { return 0 }
        val getPackageName: Method = try {
            taskCls.getMethod("getPackageName")
        } catch (_: Throwable) { return 0 }

        val filterTask: Method = try {
            filterCls.getDeclaredMethod("filterTask", groupTaskCls).apply { isAccessible = true }
        } catch (_: Throwable) {
            module.log(Log.ERROR, TAG, "filterTask(GroupTask) method not found")
            return 0
        }

        module.hook(filterTask).setId("filterTask").setExceptionMode(
            XposedInterface.ExceptionMode.PROTECTIVE
        ).intercept { chain ->
            HookDecision.evaluateOrProceed(
                proceed = { chain.proceed() },
                onError = { module.log(Log.WARN, TAG, "hook fallback: ${it.message}") }
            ) {
                val hidden = hiddenSnapshot.get()
                val groupTask = chain.args.firstOrNull()
                val pkg = groupTask?.let { packageNameOf(it, task1Field, getPackageName) }

                // 诊断：前 5 次调用打印状态，避免刷屏
                val n = diagCount
                if (n < 5) {
                    diagCount = n + 1
                    module.log(Log.INFO, TAG, "filterTask#$n pkg=$pkg hidden=${hidden.size}")
                }

                if (pkg != null && pkg in hidden) {
                    return@evaluateOrProceed true   // true = 隐藏
                }
                null
            }
        }
        module.log(Log.INFO, TAG, "hooked OplusRecentTasksFilter.filterTask")
        return 1
    }

    /** 从 GroupTask.task1 取包名 */
    private fun packageNameOf(groupTask: Any, task1Field: Field, getPackageName: Method): String? {
        return try {
            val task = task1Field.get(groupTask) ?: return null
            getPackageName.invoke(task) as? String
        } catch (_: Throwable) {
            null
        }
    }

    /** 诊断 hook：RecentsActivity.onCreate，仅日志，确认注入 */
    private fun hookDiag(module: Main, cl: ClassLoader): Int {
        val cls = try {
            cl.loadClass("com.android.quickstep.RecentsActivity")
        } catch (_: Throwable) { return 0 }
        val onCreate = try {
            cls.getDeclaredMethod("onCreate", Bundle::class.java).apply { isAccessible = true }
        } catch (_: Throwable) {
            return 0
        }
        module.hook(onCreate).setId("diag/RecentsActivity/onCreate").setExceptionMode(
            XposedInterface.ExceptionMode.PROTECTIVE
        ).intercept { chain ->
            HookDecision.evaluateOrProceed(
                proceed = { chain.proceed() },
                onError = { module.log(Log.WARN, TAG, "diag fallback: ${it.message}") }
            ) {
                // 这里 Application 一定已创建，是补注册广播接收器的可靠时机：
                // onPackageLoaded 阶段 ActivityThread.currentApplication() 常常还是 null，
                // 那样接收器就永远注册不上，推送通道形同虚设。
                registerPrefsReceiver(module)
                refreshSnapshotAsync(module, "recents-onCreate")
                null
            }
        }
        return 1
    }

    private fun refreshSnapshotAsync(module: Main, reason: String) {
        refreshExecutor.execute {
            runCatching {
                hiddenSnapshot.replace(module.hiddenPackages)
            }.onFailure {
                module.log(Log.WARN, TAG, "snapshot refresh failed($reason): ${it.message}")
            }
        }
    }

    private fun parse(raw: String): Set<String> =
        if (raw.isEmpty()) emptySet() else raw.split(',').filter { it.isNotEmpty() }.toSet()
}
