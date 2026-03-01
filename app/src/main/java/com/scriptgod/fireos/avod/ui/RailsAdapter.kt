package com.scriptgod.fireos.avod.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.model.ContentItem
import com.scriptgod.fireos.avod.model.ContentRail

class RailsAdapter(
    private val onItemClick: (ContentItem) -> Unit,
    private val onMenuKey: ((ContentItem) -> Unit)? = null
) : ListAdapter<ContentRail, RailsAdapter.RailViewHolder>(DIFF_CALLBACK) {

    private val sharedPool = RecyclerView.RecycledViewPool()
    var itemNextFocusUpId: Int = View.NO_ID
    private var outerRecyclerView: RecyclerView? = null

    class RailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val eyebrow: TextView = itemView.findViewById(R.id.tv_rail_eyebrow)
        val header: TextView = itemView.findViewById(R.id.tv_rail_header)
        val seeAll: TextView = itemView.findViewById(R.id.tv_see_all)
        val innerRecycler: RecyclerView = itemView.findViewById(R.id.rv_rail_items)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        outerRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (outerRecyclerView === recyclerView) {
            outerRecyclerView = null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rail, parent, false)
        val holder = RailViewHolder(view)
        holder.innerRecycler.setRecycledViewPool(sharedPool)
        holder.innerRecycler.layoutManager = LinearLayoutManager(
            parent.context, LinearLayoutManager.HORIZONTAL, false
        )
        holder.innerRecycler.itemAnimator = null
        return holder
    }

    override fun onBindViewHolder(holder: RailViewHolder, position: Int) {
        val rail = getItem(position)
        val hasProgressItems = rail.items.any { it.watchProgressMs > 0 || it.watchProgressMs == -1L }
        val eyebrowLabel = railEyebrow(rail, position, hasProgressItems)
        holder.eyebrow.visibility = if (eyebrowLabel != null) View.VISIBLE else View.GONE
        holder.eyebrow.text = eyebrowLabel ?: ""
        holder.header.text = rail.headerText
        holder.seeAll.visibility = View.GONE
        holder.seeAll.setOnClickListener(null)

        val presentation = resolvePresentation(rail, position, hasProgressItems)
        val innerAdapter = ContentAdapter(
            onItemClick = onItemClick,
            onMenuKey = onMenuKey,
            nextFocusUpId = itemNextFocusUpId,
            presentation = presentation,
            onVerticalFocusMove = { itemPosition, direction ->
                moveFocusBetweenRails(holder.bindingAdapterPosition, itemPosition, direction)
            }
        )
        holder.innerRecycler.adapter = innerAdapter
        innerAdapter.submitList(rail.items)
    }

    private fun resolvePresentation(rail: ContentRail, position: Int, hasProgressItems: Boolean): CardPresentation {
        val header = rail.headerText.lowercase()
        return when {
            hasProgressItems -> CardPresentation.LANDSCAPE
            position == 0 -> CardPresentation.LANDSCAPE
            header.contains("top 10") -> CardPresentation.LANDSCAPE
            header.contains("continue") -> CardPresentation.LANDSCAPE
            header.contains("watch next") -> CardPresentation.LANDSCAPE
            header.contains("because") -> CardPresentation.LANDSCAPE
            header.contains("award") || header.contains("preis") -> CardPresentation.LANDSCAPE
            header.contains("episode") -> CardPresentation.LANDSCAPE
            header.contains("season") -> CardPresentation.LANDSCAPE
            header.contains("live") -> CardPresentation.LANDSCAPE
            else -> CardPresentation.POSTER
        }
    }

    private fun railEyebrow(rail: ContentRail, position: Int, hasProgressItems: Boolean): String? {
        if (hasProgressItems) return "Continue Watching"
        val header = rail.headerText.lowercase()
        return when {
            position == 0 -> "Featured Now"
            header.contains("top 10") -> "Top 10"
            header.contains("award") || header.contains("preis") -> "Award Picks"
            header.contains("kürzlich") || header.contains("new") || header.contains("added") -> "Just Added"
            header.contains("because") || header.contains("empfehl") -> "Recommended"
            else -> null
        }
    }

    private fun moveFocusBetweenRails(currentRailPosition: Int, itemPosition: Int, direction: Int): Boolean {
        val targetRailPosition = currentRailPosition + direction
        if (targetRailPosition !in 0 until itemCount) return false

        val outerRecycler = outerRecyclerView ?: return false
        outerRecycler.scrollToPosition(targetRailPosition)
        outerRecycler.doOnNextLayout {
            val targetHolder = outerRecycler.findViewHolderForAdapterPosition(targetRailPosition) as? RailViewHolder
                ?: return@doOnNextLayout
            val targetIndex = itemPosition.coerceAtMost(getItem(targetRailPosition).items.lastIndex).coerceAtLeast(0)
            val innerRecycler = targetHolder.innerRecycler
            innerRecycler.scrollToPosition(targetIndex)
            innerRecycler.doOnNextLayout {
                val targetView = innerRecycler.layoutManager?.findViewByPosition(targetIndex)
                targetView?.requestFocus()
            }
        }
        return true
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ContentRail>() {
            override fun areItemsTheSame(old: ContentRail, new: ContentRail): Boolean {
                return if (old.collectionId.isNotEmpty() && new.collectionId.isNotEmpty())
                    old.collectionId == new.collectionId
                else old.headerText == new.headerText
            }

            override fun areContentsTheSame(old: ContentRail, new: ContentRail) = old == new
        }
    }
}
