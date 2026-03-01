package com.scriptgod.fireos.avod.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.facebook.shimmer.ShimmerFrameLayout
import com.scriptgod.fireos.avod.R

class ShimmerAdapter : RecyclerView.Adapter<ShimmerAdapter.ShimmerViewHolder>() {

    class ShimmerViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_shimmer_card, parent, false)
    ) {
        val shimmerLayout: ShimmerFrameLayout = itemView.findViewById(R.id.shimmer_layout)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShimmerViewHolder {
        return ShimmerViewHolder(parent)
    }

    override fun onBindViewHolder(holder: ShimmerViewHolder, position: Int) = Unit

    override fun getItemCount(): Int = 6

    override fun onViewAttachedToWindow(holder: ShimmerViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.shimmerLayout.startShimmer()
    }

    override fun onViewDetachedFromWindow(holder: ShimmerViewHolder) {
        holder.shimmerLayout.stopShimmer()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: ShimmerViewHolder) {
        holder.shimmerLayout.stopShimmer()
        super.onViewRecycled(holder)
    }
}
