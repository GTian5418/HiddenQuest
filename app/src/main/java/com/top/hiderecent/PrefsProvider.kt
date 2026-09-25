package com.top.hiderecent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 暴露模块配置给模块自身读取。
 * Hook 进程不再依赖 Provider 同步，避免在高频路径出现跨进程读导致的阻塞风险。
 *
 * query content://com.top.hiderecent.prefs/hide → 返回单行单列 cursor，值为逗号分隔的包名
 */
class PrefsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.top.hiderecent.prefs"
        const val PATH_HIDE = "hide"
        val URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_HIDE")
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(Main.PREFS_NAME, Context.MODE_PRIVATE)

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf("value"))
        val raw = prefs(ctx).getString(Main.KEY_HIDE, null) ?: ""
        val cursor = MatrixCursor(arrayOf("value"))
        cursor.addRow(arrayOf(raw))
        return cursor
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/vnd.com.top.hiderecent.prefs"
    override fun onCreate(): Boolean = true
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
