package com.scriptgod.fireos.avod.ui

import android.app.Activity
import android.content.Intent
import com.scriptgod.fireos.avod.R

object UiTransitions {
    fun open(activity: Activity, intent: Intent) {
        activity.startActivity(intent)
        activity.overridePendingTransition(R.anim.page_enter, R.anim.page_exit)
    }

    fun close(activity: Activity) {
        activity.finish()
        activity.overridePendingTransition(R.anim.page_pop_enter, R.anim.page_pop_exit)
    }
}
