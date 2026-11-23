package org.fossify.gallery.adapters

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import org.fossify.gallery.databases.AiIndex

class AiResultAdapter(
    private val context: Context,
    private val textColor: Int,
    private val onItemClick: (AiIndex) -> Unit
) : RecyclerView.Adapter<AiResultAdapter.ViewHolder>() {

    private val items = ArrayList<Pair<AiIndex, Float>>()

    fun submitList(list: List<Pair<AiIndex, Float>>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val root: FrameLayout, val thumb: ImageView, val badge: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val density = context.resources.displayMetrics.density
        val cell = (context.resources.displayMetrics.widthPixels - (36 * density).toInt()) / 3

        val root = FrameLayout(context)
        root.layoutParams = RecyclerView.LayoutParams(cell, cell)
        val margin = (2 * density).toInt()
        (root.layoutParams as RecyclerView.LayoutParams).setMargins(margin, margin, margin, margin)

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

        return ViewHolder(root, thumb, badge)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pair = items[position]
        val item = pair.first
        holder.badge.text = (pair.second * 100).toInt().toString() + "%"
        Picasso.get()
            .load(Uri.parse(item.uri))
            .resize(320, 320)
            .centerCrop()
            .into(holder.thumb)
        holder.root.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
