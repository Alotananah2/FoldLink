package com.murdhi.foldlink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Samsung-style keyboard.
 * FULL  = whole keyboard on one phone.
 * LEFT / RIGHT = Galaxy Fold–style split: left half on the left phone, right half on the right phone.
 */
class GlassKeyboardView(
    ctx: Context,
    private val mode: Mode,
    private val listener: Listener
) : LinearLayout(ctx) {

    enum class Mode { FULL, LEFT, RIGHT }
    enum class Special { BACKSPACE, ENTER, LEFT, RIGHT }
    interface Listener { fun onChar(c: Char); fun onSpecial(k: Special) }

    /** One key: text to show, width in key-units, and what it does. */
    private class K(val label: String, val w: Float = 1f, val kind: Int = CHAR, val ch: Char = ' ')

    companion object {
        private const val CHAR = 0; private const val SHIFT = 1; private const val BACK = 2
        private const val ENTER = 3; private const val SYM = 4; private const val LANG = 5
        private const val SPACE = 6; private const val GAP = 7

        var shift = false
        var caps = false
        var symbols = false
        var arabic = false
        private val live = mutableSetOf<GlassKeyboardView>()
        private var hooked = false

        private fun hook() {
            if (hooked) return
            hooked = true
            Link.listeners.add { o ->
                if (o.optString("t") == "kb_mode") {
                    val layerChanged = symbols != o.optBoolean("symbols") || arabic != o.optBoolean("arabic")
                    shift = o.optBoolean("shift"); caps = o.optBoolean("caps")
                    symbols = o.optBoolean("symbols"); arabic = o.optBoolean("arabic")
                    live.toList().forEach { if (layerChanged) it.build() else it.refreshShift() }
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val charViews = mutableListOf<Pair<TextView, K>>()
    private var shiftView: TextView? = null
    private var shiftBg: GradientDrawable? = null

    init {
        hook()
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(24, 25, 28))
        setPadding(dp(3), dp(6), dp(3), dp(8))
        build()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); live.add(this) }
    override fun onDetachedFromWindow() { live.remove(this); handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun chars(s: String) = s.map { K(it.toString(), 1f, CHAR, it) }
    private fun gap(w: Float) = K("", w, GAP)

    /** Rows for one half (left or right). FULL is built by joining both halves. */
    private fun half(left: Boolean): List<List<K>> = when {
        symbols -> if (left) listOf(
            chars("12345"), chars("@#\$_&"), chars("%=*\"'"),
            listOf(K("ABC", 1.4f, SYM), K(if (arabic) "EN" else "ع", 1f, LANG), K(",", 0.9f, CHAR, ','), K("", 1.7f, SPACE))
        ) else listOf(
            chars("67890"), chars("-+()/"), chars(":;!?") + K("⌫", 1f, BACK),
            listOf(K("", 2.5f, SPACE), K(".", 1f, CHAR, '.'), K("↵", 1.5f, ENTER))
        )
        arabic -> if (left) listOf(
            chars("ضصثقف"), chars("شسيبل"), chars("ءظطذد"),
            listOf(K("!#1", 1.4f, SYM), K("EN", 1f, LANG), K("،", 0.9f, CHAR, '،'), K("عربي", 1.7f, SPACE))
        ) else listOf(
            chars("غعهخحج"), chars("اتنمكة"), chars("زروىئ") + K("⌫", 1.2f, BACK),
            listOf(K("", 2.5f, SPACE), K(".", 1f, CHAR, '.'), K("↵", 1.5f, ENTER))
        )
        else -> if (left) listOf(
            chars("qwert"), chars("asdfg"), listOf(K(if (caps) "⇪" else "⇧", 1f, SHIFT)) + chars("zxcv"),
            listOf(K("!#1", 1.4f, SYM), K("ع", 1f, LANG), K(",", 0.9f, CHAR, ','), K("English", 1.7f, SPACE))
        ) else listOf(
            chars("yuiop"), listOf(gap(0.5f)) + chars("hjkl") + gap(0.5f), chars("bnm") + K("⌫", 2f, BACK),
            listOf(K("", 2.5f, SPACE), K(".", 1f, CHAR, '.'), K("↵", 1.5f, ENTER))
        )
    }

    private fun rows(): List<List<K>> = when (mode) {
        Mode.LEFT -> half(true)
        Mode.RIGHT -> half(false)
        Mode.FULL -> half(true).zip(half(false)) { a, b ->
            // merge the two halves; join the two space bars into one
            val l = a.toMutableList(); val r = b.toMutableList()
            val ls = l.lastOrNull(); val rs = r.firstOrNull()
            if (ls?.kind == SPACE && rs?.kind == SPACE) { l.removeAt(l.size - 1); r[0] = K(ls.label, ls.w + rs.w, SPACE) }
            l + r
        }
    }


    /** Shift changes only relabel keys; no rebuild, so typing stays instant. */
    fun refreshShift() {
        val upper = (shift || caps) && !symbols && !arabic
        charViews.forEach { (tv, k) -> tv.text = if (upper) k.label.uppercase() else k.label }
        shiftView?.text = if (caps) "⇪" else "⇧"
        shiftBg?.setColor(if (shift || caps) Color.rgb(62, 145, 255) else Color.rgb(46, 47, 52))
    }

    fun build() {
        removeAllViews()
        charViews.clear(); shiftView = null; shiftBg = null
        rows().forEach { row ->
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            row.forEach { k -> line.addView(keyView(k)) }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        }
    }

    private fun broadcastMode(layer: Boolean = true) {
        if (layer) { build(); live.filter { it !== this }.forEach { it.build() } }
        else live.forEach { it.refreshShift() }
        if (mode != Mode.FULL)
            Link.send(Link.msg("kb_mode", "shift" to shift, "caps" to caps, "symbols" to symbols, "arabic" to arabic))
    }

    private var lastShiftTap = 0L

    @SuppressLint("ClickableViewAccessibility")
    private fun keyView(k: K): View {
        if (k.kind == GAP) return View(context).apply { layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, k.w) }
        val upper = (shift || caps) && !symbols && !arabic
        val text = when (k.kind) { CHAR -> if (upper) k.label.uppercase() else k.label; else -> k.label }
        val functional = k.kind != CHAR && k.kind != SPACE
        val accent = (k.kind == SHIFT && (shift || caps)) || k.kind == ENTER
        val bg = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(when { accent -> Color.rgb(62, 145, 255); functional -> Color.rgb(46, 47, 52); else -> Color.rgb(64, 65, 71) })
        }
        val view = TextView(context).apply {
            this.text = text
            textSize = when { k.kind == SPACE -> 13f; text.length > 2 -> 14f; else -> 21f }
            gravity = Gravity.CENTER
            setTextColor(if (k.kind == SPACE) Color.argb(150, 255, 255, 255) else Color.WHITE)
            background = bg
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, k.w).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
        }
        when (k.kind) {
            CHAR -> charViews += view to k
            SHIFT -> { shiftView = view; shiftBg = bg }
        }
        // Fire the moment the finger lands (not on release) so typing feels instant.
        val repeat = object : Runnable {
            override fun run() { listener.onSpecial(Special.BACKSPACE); handler.postDelayed(this, 50) }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.55f
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (k.kind == BACK) { listener.onSpecial(Special.BACKSPACE); handler.postDelayed(repeat, 380) }
                    else tap(k, (shift || caps) && !symbols && !arabic)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; handler.removeCallbacks(repeat) }
            }
            true
        }
        return view
    }

    private fun tap(k: K, upper: Boolean) {
        when (k.kind) {
            CHAR -> {
                listener.onChar(if (upper) k.ch.uppercaseChar() else k.ch)
                if (shift && !caps) { shift = false; broadcastMode(layer = false) }
            }
            SPACE -> listener.onChar(' ')
            ENTER -> listener.onSpecial(Special.ENTER)
            SHIFT -> {
                val now = System.currentTimeMillis()
                when {
                    caps -> { caps = false; shift = false }
                    shift && now - lastShiftTap < 350 -> { caps = true; shift = false }
                    else -> shift = !shift
                }
                lastShiftTap = now
                broadcastMode(layer = false)
            }
            SYM -> { symbols = !symbols; shift = false; caps = false; broadcastMode() }
            LANG -> { arabic = !arabic; symbols = false; shift = false; caps = false; broadcastMode() }
        }
    }
}
