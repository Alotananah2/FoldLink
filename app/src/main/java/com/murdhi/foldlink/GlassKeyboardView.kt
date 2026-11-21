package com.murdhi.foldlink

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Samsung-style keyboard. FULL = whole keyboard on one phone.
 * LEFT / RIGHT = one half each, so together the two phones make one wide keyboard.
 */
class GlassKeyboardView(
    ctx: Context,
    private val mode: Mode,
    private val listener: Listener
) : LinearLayout(ctx) {

    enum class Mode { FULL, LEFT, RIGHT }
    enum class Special { BACKSPACE, ENTER, LEFT, RIGHT }
    interface Listener { fun onChar(c: Char); fun onSpecial(k: Special) }

    companion object {
        // Shared state so both halves always show the same layer (shift / symbols / Arabic).
        var shift = false
        var symbols = false
        var arabic = false
        private val live = mutableSetOf<GlassKeyboardView>()
        private var hooked = false

        private fun hook() {
            if (hooked) return
            hooked = true
            Link.listeners.add { o ->
                if (o.optString("t") == "kb_mode") {
                    shift = o.optBoolean("shift"); symbols = o.optBoolean("symbols"); arabic = o.optBoolean("arabic")
                    live.toList().forEach { it.build() }
                }
            }
        }

        val LETTERS = listOf("12345" to "67890", "qwert" to "yuiop", "asdfg" to "hjkl", "zxcv" to "bnm")
        val SYMS = listOf("12345" to "67890", "!@#$%" to "^&*()", "-_=+[" to "]{};:", "'\",.<" to ">/?\\|")
        val ARABIC = listOf("١٢٣٤٥" to "٦٧٨٩٠", "ضصثقف" to "غعهخحج", "شسيبل" to "اتنمكط", "ئءؤرى" to "ةوزظدذ")
    }

    init {
        hook()
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(18, 19, 22))
        setPadding(dp(4), dp(6), dp(4), dp(10))
        build()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); live.add(this) }
    override fun onDetachedFromWindow() { live.remove(this); super.onDetachedFromWindow() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun changeMode() {
        build()
        live.filter { it !== this }.forEach { it.build() }
        if (mode != Mode.FULL)
            Link.send(Link.msg("kb_mode", "shift" to shift, "symbols" to symbols, "arabic" to arabic))
    }

    fun build() {
        removeAllViews()
        val rows = when { symbols -> SYMS; arabic -> ARABIC; else -> LETTERS }
        val canShift = !symbols && !arabic
        rows.forEachIndexed { i, (l, r) ->
            val items = mutableListOf<View>()
            if (i == 3 && canShift && mode != Mode.RIGHT)
                items += key(if (shift) "⬆" else "⇧", 1.5f, special = true, accent = shift) { shift = !shift; changeMode() }
            val chars = when (mode) { Mode.FULL -> l + r; Mode.LEFT -> l; Mode.RIGHT -> r }
            chars.forEach { ch ->
                val label = if (shift && canShift) ch.uppercaseChar() else ch
                items += key(label.toString()) {
                    listener.onChar(label)
                    if (shift) { shift = false; changeMode() }
                }
            }
            if (i == 3 && mode != Mode.LEFT)
                items += key("⌫", 1.5f, special = true) { listener.onSpecial(Special.BACKSPACE) }
            addRow(items)
        }
        val bottom = mutableListOf<View>()
        if (mode != Mode.RIGHT) {
            bottom += key(if (symbols) "ABC" else "!#1", 1.5f, special = true) { symbols = !symbols; changeMode() }
            bottom += key(if (arabic) "EN" else "ع", 1f, special = true) { arabic = !arabic; symbols = false; shift = false; changeMode() }
        }
        val spaceW = when (mode) { Mode.FULL -> 5f; Mode.LEFT -> 3f; Mode.RIGHT -> 2f }
        bottom += key(if (arabic) "عربي" else if (mode == Mode.RIGHT) "" else "English", spaceW) { listener.onChar(' ') }
        if (mode != Mode.LEFT) {
            bottom += key("‹", 1f, special = true) { listener.onSpecial(Special.LEFT) }
            bottom += key("›", 1f, special = true) { listener.onSpecial(Special.RIGHT) }
            bottom += key("↵", 1.5f, special = true, accent = true) { listener.onSpecial(Special.ENTER) }
        }
        addRow(bottom)
    }

    private fun addRow(items: List<View>) {
        val total = if (mode == Mode.FULL) 11f else 5.5f
        val used = items.sumOf { ((it.layoutParams as LayoutParams).weight).toDouble() }.toFloat()
        val spare = (total - used).coerceAtLeast(0f)
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun spacer(w: Float) { if (w > 0f) row.addView(View(context), LayoutParams(0, 1, w)) }
        when (mode) { Mode.LEFT -> spacer(spare); Mode.FULL -> spacer(spare / 2); else -> {} }
        items.forEach { row.addView(it) }
        when (mode) { Mode.RIGHT -> spacer(spare); Mode.FULL -> spacer(spare / 2); else -> {} }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(54)))
    }

    private fun key(label: String, weight: Float = 1f, special: Boolean = false, accent: Boolean = false, onTap: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = if (label.length > 2) 14f else 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(when { accent -> Color.rgb(56, 132, 255); special -> Color.rgb(36, 37, 42); else -> Color.rgb(52, 53, 59) })
            }
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onTap() }
        }
}
