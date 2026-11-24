package org.fossify.gallery.adapters

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
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

    sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val data: AiIndex, val score: Float) : Row()
    }

    private val rows = ArrayList<Row>()
    private val selected = LinkedHashSet<String>()

    var listMode = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var selectionMode = false
        private set

    fun submitList(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        if (newRows.none { it is Row.Item }) {
            selected.clear()
            selectionMode = false
        }
        notifyDataSetChanged()
    }

    fun selectedItems(): List<AiIndex> {
        val result = ArrayList<AiIndex>()
        for (row in rows) {
            if (row is Row.Item && selected.contains(row.data.uri)) {
                result.add(row.data)
            }
        }
        return result
    }

    fun clearSelection() {
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    fun toggleSelection(item: AiIndex) {
        if (selected.contains(item.uri)) {
            selected.remove(item.uri)
        } else {
            selected.add(item.uri)
        }
        selectionMode = selected.isNotEmpty()
        notifyDataSetChanged()
    }

    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is Row.Header

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    override fun getItemViewType(position: Int): Int = when {
        rows[position] is Row.Header -> TYPE_HEADER
        listMode -> TYPE_LIST
        else -> TYPE_GRID
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val text = TextView(context)
                text.textSize = 13f
                text.setTextColor(textColor.adjustAlpha(0.65f))
                text.setPadding(dp(6), dp(14), dp(6), dp(6))
                text.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                HeaderHolder(text)
            }
            TYPE_LIST -> {
                val row = LinearLayout(context)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, dp(7), 0, dp(7))
                row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                val thumb = ImageView(context)
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                val thumbSize = dp(72)
                row.addView(thumb, LinearLayout.LayoutParams(thumbSize, thumbSize))

                val column = LinearLayout(context)
                column.orientation = LinearLayout.VERTICAL
                val columnParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                columnParams.marginStart = dp(10)
                row.addView(column, columnParams)

                val title = TextView(context)
                title.textSize = 14f
                title.maxLines = 1
                title.setTextColor(textColor)
                column.addView(title)

                val description = TextView(context)
                description.textSize = 12.5f
                description.setTextColor(textColor.adjustAlpha(0.7f))
                description.setPadding(0, dp(3), 0, 0)
                column.addView(description)

                val check = TextView(context)
                check.text = "✓"
                check.textSize = 16f
                check.setTextColor(Color.WHITE)
                check.gravity = Gravity.CENTER
                check.visibility = android.view.View.GONE
                row.addView(check, LinearLayout.LayoutParams(dp(28), dp(28)))

                ListHolder(row, thumb, title, description, check)
            }
            else -> {
                val density = context.resources.displayMetrics.density
                val cell = (context.resources.displayMetrics.widthPixels - (36 * density).toInt()) / 3
                val root = FrameLayout(context)
                val params = RecyclerView.LayoutParams(cell, cell)
                val margin = dp(2)
                params.setMargins(margin, margin, margin, margin)
                root.layoutParams = params

                val thumb = ImageView(context)
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                root.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

                val badge = TextView(context)
                badge.textSize = 10f
                badge.setTextColor(Color.WHITE)
                badge.setBackgroundColor(Color.parseColor("#99000000"))
                badge.setPadding(dp(4), dp(1), dp(4), dp(1))
                val badgeParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                badgeParams.gravity = Gravity.BOTTOM or Gravity.END
                root.addView(badge, badgeParams)

                val check = TextView(context)
                check.textSize = 13f
                check.setTextColor(Color.WHITE)
                check.gravity = Gravity.CENTER
                check.visibility = android.view.View.GONE
                val checkCircle = GradientDrawable()
                checkCircle.shape = GradientDrawable.OVAL
                checkCircle.setColor(Color.parseColor("#CC4C8DFF"))
                check.background = checkCircle
                val checkParams = FrameLayout.LayoutParams(dp(24), dp(24))
                checkParams.gravity = Gravity.TOP or Gravity.END
                checkParams.setMargins(dp(6), dp(6), dp(6), dp(6))
                root.addView(check, checkParams)

                GridHolder(root, thumb, badge, check)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (row is Row.Header) {
            (holder as HeaderHolder).text.text = row.title
            return
        }
        val item = (row as Row.Item).data
        val score = row.score
        val isSelected = selected.contains(item.uri)

        if (holder is GridHolder) {
            holder.badge.text = (score * 100).toInt().toString() + "%"
            holder.check.visibility = if (selectionMode) {
                if (isSelected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            } else {
                android.view.View.GONE
            }
            Picasso.get().load(Uri.parse(item.uri)).resize(320, 320).centerCrop().into(holder.thumb)
            holder.root.setOnClickListener { if (selectionMode) onLongClick(item) else onClick(item) }
            holder.root.setOnLongClickListener { onLongClick(item); true }
        } else if (holder is ListHolder) {
            holder.title.text = item.path.substringAfterLast('/') + "　" + (score * 100).toInt() + "%"
            holder.description.text = item.description
            holder.check.visibility = if (selectionMode) {
                if (isSelected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            } else {
                android.view.View.GONE
            }
            Picasso.get().load(Uri.parse(item.uri)).resize(240, 240).centerCrop().into(holder.thumb)
            holder.root.setOnClickListener { if (selectionMode) onLongClick(item) else onClick(item) }
            holder.root.setOnLongClickListener { onLongClick(item); true }
        }
    }

    class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)
    class GridHolder(val root: FrameLayout, val thumb: ImageView, val badge: TextView, val check: TextView) : RecyclerView.ViewHolder(root)
    class ListHolder(val root: LinearLayout, val thumb: ImageView, val title: TextView, val description: TextView, val check: TextView) : RecyclerView.ViewHolder(root)
}

private fun Int.adjustAlpha(factor: Float): Int {
    val alpha = Math.round(255 * factor)
    return Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
}

private const val TYPE_HEADER = 0
private const val TYPE_GRID = 1
private const val TYPE_LIST = 2
