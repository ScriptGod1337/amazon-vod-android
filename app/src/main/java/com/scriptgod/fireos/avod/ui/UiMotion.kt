package com.scriptgod.fireos.avod.ui

import android.view.View
import androidx.core.view.isVisible
import com.scriptgod.fireos.avod.R

object UiMotion {
    fun reveal(vararg views: View?) {
        val visibleViews = views.filterNotNull().filter { it.isVisible }
        if (visibleViews.isEmpty()) return

        val revealDuration = visibleViews.first().resources.getInteger(R.integer.page_reveal_duration).toLong()
        val revealStagger = visibleViews.first().resources.getInteger(R.integer.page_reveal_stagger).toLong()
        val revealMaxStagger = visibleViews.first().resources.getInteger(R.integer.page_reveal_max_stagger).toLong()
        visibleViews.forEachIndexed { index, view ->
            if (view.alpha > 0.98f && kotlin.math.abs(view.translationY) < 0.5f) return@forEachIndexed

            val offset = view.resources.getDimensionPixelSize(R.dimen.page_motion_offset).toFloat()
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = offset
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * revealStagger).coerceAtMost(revealMaxStagger))
                .setDuration(revealDuration)
                .start()
        }
    }

    fun revealFresh(vararg views: View?) {
        views.filterNotNull().forEach { view ->
            // Only reset views that are not already fully visible and settled.
            // Hard-resetting an already-visible view causes a flash (alpha 1→0→1).
            if (!view.isVisible || view.alpha < 0.98f) {
                view.alpha = 0f
                view.translationY = 0f
            }
        }
        reveal(*views)
    }
}
