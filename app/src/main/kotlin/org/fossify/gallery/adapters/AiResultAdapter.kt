package org.fossify.gallery.adapters

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import org.fossify.gallery.databases.AiIndex

class AiResultAdapter(
    private val context: Context,
    private val textColor: Int,
    private val onClick: (AiIndex) -> Unit,
    private val onLongClick: (AiIndex) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private class RowHeader(val title: String)

    private class RowItem(val item: AiIndex, val score: Float)

    private val rows = ArrayList<Any>()
    private val selected = LinkedHashSet<String>()
    private val byUri = HashMap<String, AiIndex>()
    private var listMode = false

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_GRID = 1
        const val TYPE_LIST = 2
    }

    fun isSelectionMode(): Boolean = selected.isNotEmpty()

    fun selectionSize(): Int = selected.size

    fun selectedUris(): List<String> = selected.toList()

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

    fun setListMode(value: Boolean) {
        listMode = value
        notifyDataSetChanged()
    }

    fun submitList(items: List<Pair<AiIndex, Float>>) {
        rows.clear()
        byUri.clear()
        selected.clear()
        if (listMode) {
            for (pair in items) {
                rows.add(RowItem(pair.first, pair.second))
                byUri[pair.first.uri] = pair.first
            }
        } else {
            val high = items.filter { it.second >= 0.9f }
            val mid = items.filter { it.second >= 0.7f && it.second < 0.9f }
            val low = items.filter { it.second < 0.7f }
            addGroup("高相关（90% 以上）", high)
            addGroup("较相关（70~90%）", mid)
            addGroup("其他结果", low)
        }
        notifyDataSetChanged()
    }

    private fun addGroup(title: String, items: List<Pair<AiIndex, Float>>) {
        if (items.isEmpty()) {
            return
        }
        rows.add(RowHeader(title + " · " + items.size + " 张"))
        for (pair in items) {
            rows.add(RowItem(pair.first, pair.second))
            byUri[pair.first.uri] = pair.first
        }
    }

    override fun getItemViewType(position: Int): Int {
        val row = rows[position]
        if (row is RowHeader) {
            return TYPE_HEADER
        }
        return if (listMode) TYPE_LIST else TYPE_GRID
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val density = context.resources.displayMetrics.density
        return when (viewType) {
            TYPE_HEADER -> {
                val title = TextView(context)
                title.textSize = 14f
                title.setTextColor(textColor)
                title.setPadding((6 * density).toInt(), (14 * density).toInt(), 0, (6 * density).toInt())
                title.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                HeaderHolder(title)
            }
            TYPE_LIST -> {
                val row = LinearLayout(context)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                val thumb = ImageView(context)
                val size = (72 * density).toInt()
                thumb.layoutParams = LinearLayout.LayoutParams(size, size)
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                row.addView(thumb)
                val column = LinearLayout(context)
                column.orientation = LinearLayout.VERTICAL
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                params.marginStart = (10 * density).toInt()
                column.layoutParams = params
                val title = TextView(context)
                title.textSize = 14f
                title.maxLines = 1
                title.setTextColor(textColor)
                val desc = TextView(context)
                desc.textSize = 12f
                desc.maxLines = 4
                desc.setTextColor(Color.argb(170, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
                column.addView(title)
                column.addView(desc)
                row.addView(column)
                ListHolder(row, thumb, title, desc)
            }
            else -> {
                val cell = (context.resources.displayMetrics.widthPixels - (40 * density).toInt()) / 3
                val root = FrameLayout(context)
                root.layoutParams = RecyclerView.LayoutParams(cell, cell)
                val thumb = ImageView(context)
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                root.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val badge = TextView(context)
                badge.textSize = 10f
                badge.setTextColor(Color.WHITE)
                badge.setBackgroundColor(Color.parseColor("#99000000"))
                badge.setPadding((4 * density).toInt(), (1 * density).toInt(), (4 * density).toInt(), (1 * density).toInt())
                val badgeParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                badgeParams.gravity = Gravity.BOTTOM or Gravity.END
                root.addView(badge, badgeParams)
                GridHolder(root, thumb, badge)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (row is RowHeader) {
            (holder as HeaderHolder).title.text = row.title
            return
        }
        val item = (row as RowItem).item
        val score = row.score
        val isSelected = selected.contains(item.uri)

        when (holder) {
            is GridHolder -> {
                holder.badge.text = (score * 100).toInt().toString() + "%"
                holder.thumb.alpha = if (isSelected) 0.35f else 1f
                Picasso.get().load(Uri.parse(item.uri)).resize(320, 320).centerCrop().into(holder.thumb)
                holder.root.setOnClickListener {
                    if (isSelectionMode()) {
                        toggle(item.uri)
                    } else {
                        onClick(item)
                    }
                }
                holder.root.setOnLongClickListener {
                    toggle(item.uri)
                    onLongClick(item)
                    true
                }
            }
            is ListHolder -> {
                holder.title.text = item.path.substringAfterLast('/')
                holder.desc.text = item.description
                holder.thumb.alpha = if (isSelected) 0.35f else 1f
                Picasso.get().load(Uri.parse(item.uri)).resize(240, 240).centerCrop().into(holder.thumb)
                holder.root.setOnClickListener {
                    if (isSelectionMode()) {
                        toggle(item.uri)
                    } else {
                        onClick(item)
                    }
                }
                holder.root.setOnLongClickListener {
                    toggle(item.uri)
                    onLongClick(item)
                    true
                }
            }
        }
    }

    private fun toggle(uri: String) {
        if (selected.contains(uri)) {
            selected.remove(uri)
        } else {
            selected.add(uri)
        }
        notifyDataSetChanged()
    }

    class HeaderHolder(val title: TextView) : RecyclerView.ViewHolder(title)

    class GridHolder(val root: FrameLayout, val thumb: ImageView, val badge: TextView) : RecyclerView.ViewHolder(root)

    class ListHolder(val root: LinearLayout, val thumb: ImageView, val title: TextView, val desc: TextView) :
        RecyclerView.ViewHolder(root)
}
