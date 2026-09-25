package com.top.hiderecent

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * UI 侧桥接：模块 UI 进程不在 LSPosed scope 内，XposedModule 类不在 classpath，
 * 不能访问 [Main]（继承 XposedModule）。此处提供 UI 所需的常量与广播发送，
 * 不依赖任何 Xposed API。
 */
object PrefsBridge {
    const val TAG = "HideRecentTiles"
    const val MODULE_PKG = "com.top.hiderecent"
    const val PREFS_NAME = "hide_recent"
    const val ACTION_PREFS_CHANGED = "com.top.hiderecent.PREFS_CHANGED"
    const val EXTRA_HIDE = "hide_list"

    fun notifyPrefsChanged(ctx: Context, hidden: Set<String>) {
        runCatching {
            val intent = Intent(ACTION_PREFS_CHANGED)
                .putExtra(EXTRA_HIDE, hidden.joinToString(","))
            ctx.sendBroadcast(intent)
        }.onFailure { Log.w(TAG, "notify prefs changed failed", it) }
    }
}
