package com.firetv.player.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatEditText

/**
 * EditText that intercepts BACK key before the IME processes it.
 * On Fire TV, SHOW_FORCED keyboard doesn't dismiss on back press —
 * onKeyPreIme lets us handle it manually.
 */
class DpadEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var onBackPressedWhileFocused: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP) {
            onBackPressedWhileFocused?.invoke()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
