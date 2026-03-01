package com.scriptgod.fireos.avod.ui

import android.app.Dialog
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.model.ContentItem

object WatchlistActionOverlay {
    fun show(
        activity: AppCompatActivity,
        item: ContentItem,
        isInWatchlist: Boolean,
        onConfirm: () -> Unit
    ): Dialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_watchlist_action, null, false)
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        val title = view.findViewById<TextView>(R.id.tv_watchlist_dialog_title)
        val message = view.findViewById<TextView>(R.id.tv_watchlist_dialog_message)
        val primary = view.findViewById<Button>(R.id.btn_watchlist_dialog_primary)
        val secondary = view.findViewById<Button>(R.id.btn_watchlist_dialog_cancel)

        title.text = item.title
        message.text = if (isInWatchlist) {
            "Remove this title from your watchlist?"
        } else {
            "Add this title to your watchlist?"
        }
        primary.text = if (isInWatchlist) "Remove" else "Add"

        primary.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        secondary.setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.48f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            primary.requestFocus()
        }
        dialog.show()
        return dialog
    }
}
