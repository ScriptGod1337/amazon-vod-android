package com.scriptgod.fireos.avod.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.model.ContentItem
import com.scriptgod.fireos.avod.model.ContentRail

class RailsAdapter(
    private val onItemClick: (ContentItem) -> Unit,
    private val onItemLongClick: ((ContentItem) -> Unit)? = null
) : ListAdapter<ContentRail, RailsAdapter.RailViewHolder>(DIFF_CALLBACK) {

    private val sharedPool = RecyclerView.RecycledViewPool()

    class RailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val header: TextView = itemView.findViewById(R.id.tv_rail_header)
        val innerRecycler: RecyclerView = itemView.findViewById(R.id.rv_rail_items)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rail, parent, false)
        val holder = RailViewHolder(view)
        holder.innerRecycler.setRecycledViewPool(sharedPool)
        holder.innerRecycler.layoutManager = LinearLayoutManager(
            parent.context, LinearLayoutManager.HORIZONTAL, false
        )
        return holder
    }

    override fun onBindViewHolder(holder: RailViewHolder, position: Int) {
        val rail = getItem(position)
        holder.header.text = rail.headerText

        val innerAdapter = ContentAdapter(
            onItemClick = onItemClick,
            onItemLongClick = onItemLongClick
        )
        holder.innerRecycler.adapter = innerAdapter
        innerAdapter.submitList(rail.items)
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
