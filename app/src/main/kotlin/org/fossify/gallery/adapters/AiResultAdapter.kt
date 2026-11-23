package org.fossify.gallery.adapters

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import org.fossify.gallery.databases.AiIndex

class AiResultAdapter(private val context: Context) : RecyclerView.Adapter<AiResultAdapter.ViewHolder>() {

    private val items = ArrayList<AiIndex>()

    fun submitList(list: List<AiIndex>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val row: LinearLayout, val thumb: ImageView, val title: TextView, val subtitle: TextView) :
        RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        val verticalPadding = (8 * density).toInt()
        row.setPadding(0, verticalPadding, 0, verticalPadding)

        val thumb = ImageView(context)
        val thumbSize = (64 * density).toInt()
        thumb.layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        row.addView(thumb)

        val textColumn = LinearLayout(context)
        textColumn.orientation = LinearLayout.VERTICAL
        val textParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        textParams.marginStart = (10 * density).toInt()
        textColumn.layoutParams = textParams

        val title = TextView(context)
        title.maxLines = 1
        title.textSize = 15f
        val subtitle = TextView(context)
        subtitle.maxLines = 3
        subtitle.textSize = 12f
        textColumn.addView(title)
        textColumn.addView(subtitle)
        row.addView(textColumn)

        return ViewHolder(row, thumb, title, subtitle)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.path.substringAfterLast('/')
        holder.subtitle.text = item.description
        Picasso.get()
            .load(Uri.parse(item.uri))
            .resize(160, 160)
            .centerCrop()
            .into(holder.thumb)
    }

    override fun getItemCount(): Int = items.size
}
