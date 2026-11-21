package com.murdhi.foldlink

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.TextView

/** Shared liquid-glass keyboard layout used by the Bluetooth keyboard and the system keyboard. */
class GlassKeyboardView(
    ctx: Context,
    private val allowArabic: Boolean,
    private val listener: Listener
) : LinearLayout(ctx) {

    enum class Special { BACKSPACE, ENTER, LEFT, RIGHT }
    interface Listener { fun onChar(c: Char); fun onSpecial(k: Special) }

    private var shift = false
    private var symbols = false
    private var arabic = false

    private val letters = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val syms = listOf("1234567890", "!@#$%^&*()", "-_=+[]{};:", "'\",.<>/?\\|")
    private val arabicRows = listOf("١٢٣٤٥٦٧٨٩٠", "ضصثقفغعهخحج", "شسيبلاتنمكط", "ئءؤرىةوزظدذ")

    init {
        orientation = VERTICAL
        setPadding(10, 14, 10, 14)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(235, 28, 32, 44), Color.argb(245, 12, 14, 20)))
        build()
    }

    private fun build() {
        removeAllViews()
        val rows = when { symbols -> syms; arabic -> arabicRows; else -> letters }
        rows.forEachIndexed { i, r ->
            val row = newRow()
            val shiftable = !symbols && !arabic
            if (i == 3 && shiftable) row.addView(key(if (shift) "⇧" else "⇪", 1.4f) { shift = !shift; build() })
            r.forEach { ch ->
                val label = if (shift && shiftable) ch.uppercaseChar() else ch
                row.addView(key(label.toString()) {
                    listener.onChar(label)
                    if (shift) { shift = false; build() }
                })
            }
            if (i == 3) row.addView(key("⌫", 1.4f) { listener.onSpecial(Special.BACKSPACE) })
            addView(row)
        }
        val bottom = newRow()
        bottom.addView(key(if (symbols) "ABC" else "?123", 1.4f) { symbols = !symbols; build() })
        if (allowArabic) bottom.addView(key(if (arabic) "EN" else "ع") { arabic = !arabic; symbols = false; build() })
        bottom.addView(key("←") { listener.onSpecial(Special.LEFT) })
        bottom.addView(key(if (arabic) "مسافة" else "space", 4f) { listener.onChar(' ') })
        bottom.addView(key("→") { listener.onSpecial(Special.RIGHT) })
        bottom.addView(key("⏎", 1.4f) { listener.onSpecial(Special.ENTER) })
        addView(bottom)
    }

    private fun newRow() = LinearLayout(context).apply { orientation = HORIZONTAL }

    private fun key(label: String, weight: Float = 1f, onTap: () -> Unit) = TextView(context).apply {
        text = label
        textSize = if (label.length > 2) 15f else 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(Color.argb(55, 255, 255, 255))
            setStroke(2, Color.argb(80, 255, 255, 255))
        }
        layoutParams = LayoutParams(0, 140, weight).apply { setMargins(5, 5, 5, 5) }
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onTap() }
    }
}
