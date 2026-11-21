package com.murdhi.foldlink

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen keyboard on the 2nd phone that types into the main phone over Bluetooth. */
class KeyboardActivity : Activity() {
    private var kb: HidKeyboard? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val statusView = TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 14f; setPadding(24, 24, 24, 16)
            text = "Starting Bluetooth keyboard…"
        }
        val keys = GlassKeyboardView(this, allowArabic = false, listener = object : GlassKeyboardView.Listener {
            override fun onChar(c: Char) { kb?.type(c) }
            override fun onSpecial(k: GlassKeyboardView.Special) {
                kb?.press(when (k) {
                    GlassKeyboardView.Special.BACKSPACE -> HidKeyboard.BACKSPACE
                    GlassKeyboardView.Special.ENTER -> HidKeyboard.ENTER
                    GlassKeyboardView.Special.LEFT -> HidKeyboard.LEFT
                    GlassKeyboardView.Special.RIGHT -> HidKeyboard.RIGHT
                })
            }
        })
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Color.rgb(10, 12, 18))
            addView(statusView)
            addView(keys)
        })
        val host = Prefs.get(this).getString(Prefs.PARTNER, null)
        if (host == null) statusView.text = "Pick the main phone in FoldLink settings first"
        else kb = HidKeyboard(this, host) { s -> runOnUiThread { statusView.text = s } }.also { it.start() }
    }

    override fun onDestroy() { kb?.stop(); super.onDestroy() }
}
