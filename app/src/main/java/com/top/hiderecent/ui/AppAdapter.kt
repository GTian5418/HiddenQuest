package com.top.hiderecent.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.top.hiderecent.R
import java.util.concurrent.Executors

/**
 * 应用列表适配器。
 * 图标懒加载：仅在 onBindViewHolder 时按需加载可见项图标，带内存缓存 + 线程池。
 */
class AppAdapter(
    private val pm: PackageManager,
    private val onClick: (AppItem, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    data class AppItem(
        val pkg: String,
        val label: String,
        val isSystem: Boolean,
        val installTime: Long,
        var checked: Boolean
    )

    private val items = mutableListOf<AppItem>()
    private val iconCache = HashMap<String, Drawable>()
    private val iconExecutor = Executors.newFixedThreadPool(4)

    /** 全量数据替换 */
    fun submit(newItems: List<AppItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /**
     * 外部（竖点菜单「隐藏自身」等）改动勾选态后同步列表数据。
     *
     * 必须同步：submit() 传进来的是 MainActivity.allItems 里的同一批对象，
     * 不回写的话列表复用时会显示旧勾选态，和菜单/状态栏计数对不上。
     * 这里只改数据不回调 onClick —— 状态由调用方自己维护，避免来回写盘。
     */
    fun setChecked(pkg: String, checked: Boolean) {
        var changed = false
        for (item in items) {
            if (item.pkg == pkg && item.checked != checked) {
                item.checked = checked
                changed = true
            }
        }
        if (changed) notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.label
        holder.pkg.text = item.pkg
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = item.checked
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            item.checked = checked
            onClick(item, checked)
        }
        holder.itemView.setOnClickListener {
            holder.checkbox.isChecked = !holder.checkbox.isChecked
        }

        // 图标懒加载：命中缓存直接设，未命中异步加载
        holder.icon.tag = item.pkg
        val cached = iconCache[item.pkg]
        if (cached != null) {
            holder.icon.setImageDrawable(cached)
        } else {
            holder.icon.setImageDrawable(null)
            iconExecutor.execute {
                val icon = try { pm.getApplicationIcon(item.pkg) } catch (_: Throwable) { null }
                if (icon != null) {
                    synchronized(iconCache) { iconCache[item.pkg] = icon }
                    holder.icon.post {
                        if (holder.icon.tag == item.pkg) {
                            holder.icon.setImageDrawable(icon)
                        }
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val pkg: TextView = view.findViewById(R.id.appPackage)
        val checkbox: CheckBox = view.findViewById(R.id.appCheck)
    }
}
