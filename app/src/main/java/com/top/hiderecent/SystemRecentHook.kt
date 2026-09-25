package com.top.hiderecent

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * 注入 system_server（android 作用域）。
 * 参考 hideRecent / RocGwei：拦截 com.android.server.wm.RecentTasks.isVisibleRecentTask
 * 使指定包名的任务不出现在最近任务列表。
 */
object SystemRecentHook {

    private const val TAG = "${Main.TAG}/sys"

    fun hook(module: Main, cl: ClassLoader) {
        // 目标类在部分 ROM 上是 RecentTasks 内部类，回退 Task 基类
        val recentTasks = loadClass(cl,
            "com.android.server.wm.RecentTasks",
            "com.android.server.wm.RecentTasks\$1"
        ) ?: run {
            module.log(Log.ERROR, TAG, "RecentTasks not found")
            return
        }

        var count = 0
        // 单参重载（AOSP）
        count += hookBool(module, recentTasks, "isVisibleRecentTask", 1)
        // 双参重载（Vivo / 部分 OEM 直接调用）
        count += hookBool(module, recentTasks, "isVisibleRecentTask", 2)
        module.log(Log.INFO, TAG, "isVisibleRecentTask hooks = $count")
    }

    /** 拦截返回 boolean 的方法：若目标任务包名在隐藏集合内则强制返回 false */
    private fun hookBool(module: Main, cls: Class<*>, name: String, argCount: Int): Int {
        val method = findMethod(cls, name, argCount) ?: return 0
        module.hook(method).setId("$name/$argCount").setExceptionMode(
            XposedInterface.ExceptionMode.PROTECTIVE
        ).intercept { chain ->
            val hidden = module.hiddenPackages
            if (hidden.isNotEmpty()) {
                val pkg = packageNameOf(chain.args)
                if (pkg != null && pkg in hidden) {
                    module.log(Log.INFO, TAG, "hide task of $pkg")
                    return@intercept false
                }
            }
            chain.proceed()
        }
        return 1
    }

    /** 从方法入参里的 Task 对象取基础 Intent 的包名 */
    private fun packageNameOf(args: List<Any?>): String? {
        val task = args.firstOrNull() ?: return null
        return try {
            val m = task.javaClass.getMethod("getBaseIntent")
            val intent = m.invoke(task) as? android.content.Intent ?: return null
            intent.component?.packageName ?: intent.`package`
        } catch (t: Throwable) {
            try {
                // 部分 ROM 用 mBaseIntent 字段
                val f = task.javaClass.getDeclaredField("mBaseIntent").apply { isAccessible = true }
                (f.get(task) as? android.content.Intent)?.component?.packageName
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun findMethod(cls: Class<*>, name: String, argCount: Int): Method? =
        cls.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == argCount }
            ?.apply { isAccessible = true }

    private fun loadClass(cl: ClassLoader, vararg names: String): Class<*>? {
        for (n in names) {
            try {
                return cl.loadClass(n)
            } catch (_: Throwable) {
            }
        }
        return null
    }
}