package org.fossify.gallery.adapters

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import org.fossify.gallery.databases.AiIndex

class AiGridAdapter(
    private val context: Context,
    private val textColor: Int,
    private val onItemClick: (AiIndex) -> Unit
) : RecyclerView.Adapter<AiGridAdapter.ViewHolder>() {

    private val items = ArrayList<AiIndex>()

    fun submitList(list: List<AiIndex>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val root: LinearLayout, val thumb: ImageView, val caption: TextView) :
        RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val thumb = ImageView(context)
        thumb.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (150 * density).toInt())
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(Color.argb(20, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
        root.addView(thumb)

        val caption = TextView(context)
        caption.textSize = 11.5f
        caption.maxLines = 2
        caption.setPadding((4 * density).toInt(), (3 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
        caption.setTextColor(Color.argb(170, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
        root.addView(caption)

        return ViewHolder(root, thumb, caption)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.caption.text = item.description.ifEmpty { item.path.substringAfterLast('/') }
        Picasso.get()
            .load(Uri.parse(item.uri))
            .resize(400, 400)
            .centerCrop()
            .into(holder.thumb)
        holder.root.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
