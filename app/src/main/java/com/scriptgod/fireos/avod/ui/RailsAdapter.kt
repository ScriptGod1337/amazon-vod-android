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
        holder.header.text = rail.headerText
        holder.seeAll.visibility = View.GONE
        holder.seeAll.setOnClickListener(null)

        val innerAdapter = ContentAdapter(
            onItemClick = onItemClick,
            onMenuKey = onMenuKey,
            nextFocusUpId = itemNextFocusUpId,
            onVerticalFocusMove = { itemPosition, direction ->
                moveFocusBetweenRails(holder.bindingAdapterPosition, itemPosition, direction)
            }
        )
        holder.innerRecycler.adapter = innerAdapter
        innerAdapter.submitList(rail.items)
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
