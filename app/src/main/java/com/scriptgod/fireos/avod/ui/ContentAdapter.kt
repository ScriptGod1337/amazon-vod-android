package com.scriptgod.fireos.avod.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.model.ContentItem

class ContentAdapter(
    private val onItemClick: (ContentItem) -> Unit,
    private val onItemLongClick: ((ContentItem) -> Unit)? = null
) : ListAdapter<ContentItem, ContentAdapter.ViewHolder>(DIFF_CALLBACK) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val poster: ImageView = itemView.findViewById(R.id.iv_poster)
        val title: TextView = itemView.findViewById(R.id.tv_title)
        val subtitle: TextView = itemView.findViewById(R.id.tv_subtitle)
        val watchlistIcon: ImageView = itemView.findViewById(R.id.iv_watchlist)
        val watchProgress: ProgressBar = itemView.findViewById(R.id.pb_watch_progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_content, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        if (item.imageUrl.isNotEmpty()) {
            holder.poster.load(item.imageUrl) {
                crossfade(true)
            }
        } else {
            holder.poster.setImageDrawable(null)
            holder.poster.setBackgroundColor(0xFF222222.toInt())
        }

        // Watchlist indicator
        holder.watchlistIcon.visibility = View.VISIBLE
        holder.watchlistIcon.setImageResource(
            if (item.isInWatchlist) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )

        // Watch progress bar
        when {
            item.watchProgressMs == -1L -> {
                // Fully watched — green bar at 100%
                holder.watchProgress.visibility = View.VISIBLE
                holder.watchProgress.progress = 100
                holder.watchProgress.progressTintList = ColorStateList.valueOf(0xFF4CAF50.toInt())
            }
            item.watchProgressMs > 0 && item.runtimeMs > 0 -> {
                // Partially watched — amber bar
                holder.watchProgress.visibility = View.VISIBLE
                holder.watchProgress.progress = (item.watchProgressMs * 100 / item.runtimeMs).toInt().coerceIn(1, 99)
                holder.watchProgress.progressTintList = ColorStateList.valueOf(0xFFFFAB00.toInt())
            }
            else -> {
                holder.watchProgress.visibility = View.GONE
                holder.watchProgress.progress = 0
            }
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(item)
            true
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ContentItem>() {
            override fun areItemsTheSame(old: ContentItem, new: ContentItem) = old.asin == new.asin
            override fun areContentsTheSame(old: ContentItem, new: ContentItem) = old == new
        }
    }
}
