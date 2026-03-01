package com.scriptgod.fireos.avod.ui

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.model.ContentItem
import com.scriptgod.fireos.avod.model.isFullyWatched
import kotlin.math.roundToInt

class ContentAdapter(
    private val onItemClick: (ContentItem) -> Unit,
    private val onMenuKey: ((ContentItem) -> Unit)? = null,
    var nextFocusUpId: Int = View.NO_ID,
    private val onVerticalFocusMove: ((position: Int, direction: Int) -> Boolean)? = null,
    private val presentation: CardPresentation = CardPresentation.POSTER
) : ListAdapter<ContentItem, ContentAdapter.ViewHolder>(DIFF_CALLBACK) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val poster: ImageView = itemView.findViewById(R.id.iv_poster)
        val overline: TextView = itemView.findViewById(R.id.tv_overline)
        val title: TextView = itemView.findViewById(R.id.tv_title)
        val subtitle: TextView = itemView.findViewById(R.id.tv_subtitle)
        val watchlistIcon: ImageView = itemView.findViewById(R.id.iv_watchlist)
        val badges: LinearLayout = itemView.findViewById(R.id.ll_badges)
        val watchProgress: ProgressBar = itemView.findViewById(R.id.pb_watch_progress)
        val focusGlow: View? = itemView.findViewById(R.id.v_focus_glow)
        val focusRing: View? = itemView.findViewById(R.id.v_focus_ring)
        val surface: View? = itemView.findViewById(R.id.card_surface)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(
                when (presentation) {
                    CardPresentation.SEASON -> R.layout.item_content_season
                    CardPresentation.LANDSCAPE -> R.layout.item_content_landscape
                    CardPresentation.EPISODE -> R.layout.item_content_episode
                    CardPresentation.PROGRESS -> R.layout.item_content_progress
                    CardPresentation.POSTER -> R.layout.item_content
                },
                parent,
                false
            )
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val metadata = UiMetadataFormatter.cardMetadata(item, presentation)
        holder.overline.text = metadata.overline
        holder.overline.visibility = if (metadata.overline.isBlank()) View.GONE else View.VISIBLE
        holder.title.text = metadata.title
        val displaySubtitle = metadata.subtitle
        holder.subtitle.text = displaySubtitle
        holder.subtitle.visibility = if (displaySubtitle.isBlank()) View.GONE else View.VISIBLE
        holder.subtitle.maxLines = when (presentation) {
            CardPresentation.POSTER -> 2
            CardPresentation.PROGRESS -> 2
            else -> 1
        }
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

        holder.badges.removeAllViews()
        val badgeValues = UiMetadataFormatter.badgeLabels(item)
        holder.badges.visibility = if (presentation == CardPresentation.SEASON && badgeValues.isEmpty()) {
            View.GONE
        } else {
            View.VISIBLE
        }
        badgeValues.forEach { badge ->
            val density = holder.itemView.resources.displayMetrics.density
            val chip = TextView(holder.itemView.context).apply {
                text = badge
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setBackgroundResource(R.drawable.badge_bg)
                setPadding(
                    (4 * density).roundToInt(),
                    (2 * density).roundToInt(),
                    (4 * density).roundToInt(),
                    (2 * density).roundToInt()
                )
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.marginStart = 6
            holder.badges.addView(chip, params)
        }

        // Watch progress bar
        when {
            item.isFullyWatched() -> {
                holder.watchProgress.visibility = View.VISIBLE
                holder.watchProgress.isIndeterminate = false
                holder.watchProgress.max = 100
                holder.watchProgress.progress = 100
                holder.watchProgress.progressTintList = ColorStateList.valueOf(0xFF2EBD59.toInt())
                holder.watchProgress.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
                holder.title.setTextColor(0xFFFFFFFF.toInt())
            }
            item.watchProgressMs > 0 && item.runtimeMs > 0 -> {
                holder.watchProgress.visibility = View.VISIBLE
                holder.watchProgress.isIndeterminate = false
                holder.watchProgress.max = 100
                holder.watchProgress.progress = (item.watchProgressMs * 100 / item.runtimeMs).toInt().coerceIn(1, 99)
                holder.watchProgress.progressTintList = ColorStateList.valueOf(0xFFF5A623.toInt())
                holder.watchProgress.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
                holder.title.setTextColor(0xFFFFFFFF.toInt())
            }
            item.watchProgressMs > 0 -> {
                holder.watchProgress.visibility = View.VISIBLE
                holder.watchProgress.isIndeterminate = false
                holder.watchProgress.max = 100
                holder.watchProgress.progress = 35
                holder.watchProgress.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
                holder.watchProgress.progressTintList = ColorStateList.valueOf(0xFFF5A623.toInt())
                holder.title.setTextColor(0xFFFFFFFF.toInt())
            }
            else -> {
                holder.watchProgress.visibility = View.GONE
                holder.watchProgress.isIndeterminate = false
                holder.watchProgress.progress = 0
                holder.title.setTextColor(0xFFFFFFFF.toInt())
            }
        }

        // Tag the item so Activity.onKeyDown can retrieve it from the focused view
        holder.itemView.tag = item
        if (nextFocusUpId != View.NO_ID) {
            holder.itemView.nextFocusUpId = nextFocusUpId
        }
        val isCurrentlyFocused = holder.itemView.isFocused
        holder.focusGlow?.alpha = if (isCurrentlyFocused) 1f else 0f
        holder.focusRing?.alpha = if (isCurrentlyFocused) 1f else 0f
        holder.surface?.alpha = if (isCurrentlyFocused) 1f else 0.96f
        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            val scale = if (hasFocus) {
                when (presentation) {
                    CardPresentation.POSTER -> 1.055f
                    CardPresentation.SEASON -> 1.025f
                    CardPresentation.EPISODE -> 1.02f
                    else -> 1.035f
                }
            } else {
                1.0f
            }
            val duration = view.resources.getInteger(R.integer.focus_motion_duration).toLong()
            view.animate().cancel()
            view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(duration)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            holder.focusGlow?.animate()?.alpha(if (hasFocus) 1f else 0f)?.setDuration(duration)?.start()
            holder.focusRing?.animate()?.alpha(if (hasFocus) 1f else 0f)?.setDuration(duration)?.start()
            holder.surface?.animate()?.alpha(if (hasFocus) 1f else 0.96f)?.setDuration(duration)?.start()
            view.elevation = if (hasFocus) view.resources.displayMetrics.density * 18f else 0f
        }
        holder.itemView.setOnClickListener { onItemClick(item) }
        // Long-press SELECT (standard Fire TV context-menu gesture on remotes without a Menu button)
        holder.itemView.setOnLongClickListener {
            onMenuKey?.invoke(item)
            true
        }
        // KEYCODE_MENU for remotes that have a physical Menu button
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    onMenuKey?.invoke(item)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onVerticalFocusMove?.invoke(
                        holder.bindingAdapterPosition,
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    ) ?: false
                }
                else -> false
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ContentItem>() {
            override fun areItemsTheSame(old: ContentItem, new: ContentItem) = old.asin == new.asin
            override fun areContentsTheSame(old: ContentItem, new: ContentItem) = old == new
        }
    }
}
