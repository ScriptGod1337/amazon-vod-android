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
import com.scriptgod.fireos.avod.model.isIncludedWithPrime
import com.scriptgod.fireos.avod.model.primaryAvailabilityBadge
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
        val progressSubtitle = progressSubtitle(item)
        val metadata = metadataForPresentation(item, progressSubtitle)
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
        val badgeValues = buildList {
            item.primaryAvailabilityBadge()?.let(::add)
            if (item.watchProgressMs == -1L) add("Watched")
        }.take(3)
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
            item.watchProgressMs == -1L -> {
                holder.watchProgress.visibility = View.VISIBLE
                holder.watchProgress.max = 100
                holder.watchProgress.progress = 100
                holder.watchProgress.progressTintList = ColorStateList.valueOf(0xFFF5A623.toInt())
                holder.watchProgress.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
                holder.title.setTextColor(0xFFFFFFFF.toInt())
            }
            item.watchProgressMs > 0 && item.runtimeMs > 0 -> {
                holder.watchProgress.visibility = View.VISIBLE
                holder.watchProgress.max = 100
                holder.watchProgress.progress = (item.watchProgressMs * 100 / item.runtimeMs).toInt().coerceIn(1, 99)
                holder.watchProgress.progressTintList = ColorStateList.valueOf(0xFFF5A623.toInt())
                holder.watchProgress.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
                holder.title.setTextColor(0xFFFFFFFF.toInt())
            }
            else -> {
                holder.watchProgress.visibility = View.GONE
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

    private fun progressSubtitle(item: ContentItem): String? {
        if (item.watchProgressMs == 0L || item.runtimeMs <= 0L) return null
        return when (item.watchProgressMs) {
            -1L -> "Finished recently"
            else -> {
                val progressPercent = ((item.watchProgressMs * 100) / item.runtimeMs).toInt().coerceIn(1, 99)
                val remainingMinutes = ((item.runtimeMs - item.watchProgressMs).coerceAtLeast(0L) / 60000L).toInt()
                if (remainingMinutes > 0) {
                    "$progressPercent% watched · ${remainingMinutes} min left"
                } else {
                    "$progressPercent% watched"
                }
            }
        }
    }

    private data class CardMetadata(
        val overline: String,
        val title: String,
        val subtitle: String
    )

    private fun metadataForPresentation(item: ContentItem, progressSubtitle: String?): CardMetadata {
        return when (presentation) {
            CardPresentation.SEASON -> CardMetadata(
                overline = "Season",
                title = item.title,
                subtitle = sanitizeSubtitle(item.subtitle).ifBlank { "Open episode list" }
            )
            CardPresentation.PROGRESS -> CardMetadata(
                overline = "Continue Watching",
                title = item.title,
                subtitle = progressSubtitle ?: "Resume playback"
            )
            CardPresentation.EPISODE -> {
                val (episodeOverline, episodeTitle) = episodeLabelParts(item.title)
                CardMetadata(
                    overline = episodeOverline.ifBlank { overlineText(item, progressSubtitle != null) },
                    title = episodeTitle,
                    subtitle = sanitizeSubtitle(item.subtitle).ifBlank { "Start playback" }
                )
            }
            CardPresentation.LANDSCAPE -> CardMetadata(
                overline = overlineText(item, progressSubtitle != null),
                title = item.title,
                subtitle = progressSubtitle ?: landscapeSubtitle(item)
            )
            CardPresentation.POSTER -> CardMetadata(
                overline = overlineText(item, progressSubtitle != null),
                title = item.title,
                subtitle = progressSubtitle ?: secondaryLine(item)
            )
        }
    }

    private fun overlineText(item: ContentItem, isProgress: Boolean): String {
        if (isProgress) return "Continue Watching"
        return when {
            item.isLive -> "Live"
            ContentTypeMatcher.isEpisode(item.contentType) -> "Episode"
            ContentTypeMatcher.isSeries(item.contentType) -> "Series"
            ContentTypeMatcher.isMovie(item.contentType) -> "Movie"
            else -> "Featured"
        }
    }

    private fun secondaryLine(item: ContentItem): String {
        val cleanedSubtitle = sanitizeSubtitle(item.subtitle)
        if (cleanedSubtitle.isNotBlank()) {
            return cleanedSubtitle
        }
        val parts = mutableListOf<String>()
        if (ContentTypeMatcher.isEpisode(item.contentType)) parts += "Playable episode"
        else if (ContentTypeMatcher.isSeries(item.contentType)) parts += "Series overview"
        else if (ContentTypeMatcher.isMovie(item.contentType)) parts += "Feature film"
        item.primaryAvailabilityBadge()?.let { badge ->
            if (badge == "Freevee") parts += "Ad-supported"
            if (badge == "Live") parts += "Live channel"
        }
        return parts.joinToString("  ·  ")
    }

    private fun landscapeSubtitle(item: ContentItem): String {
        return when {
            ContentTypeMatcher.isSeries(item.contentType) -> "Open season overview"
            else -> secondaryLine(item)
        }
    }

    private fun sanitizeSubtitle(subtitle: String): String {
        return subtitle
            .replace("Included with Prime", "", ignoreCase = true)
            .replace("Prime Video", "", ignoreCase = true)
            .replace("  ·   ·  ", "  ·  ")
            .replace(" • ", "  ·  ")
            .replace(Regex("\\s+·\\s*$"), "")
            .replace(Regex("^\\s*·\\s+"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun episodeLabelParts(rawTitle: String): Pair<String, String> {
        val match = Regex("^E(\\d+):\\s*(.+)$", RegexOption.IGNORE_CASE).matchEntire(rawTitle.trim())
        if (match != null) {
            val episodeNumber = match.groupValues[1]
            val episodeTitle = match.groupValues[2]
            return "Episode $episodeNumber" to episodeTitle
        }
        return "" to rawTitle
    }

    private object ContentTypeMatcher {
        fun isMovie(contentType: String) =
            contentType.equals("Feature", true) || contentType.equals("Movie", true)
        fun isSeries(contentType: String) =
            contentType.equals("Series", true) || contentType.equals("Season", true) ||
                contentType.equals("Show", true) || contentType.equals("TVSeason", true) ||
                contentType.equals("TVSeries", true)
        fun isEpisode(contentType: String) =
            contentType.equals("Episode", true) || contentType.equals("TVEpisode", true)
    }
}
