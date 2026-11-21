package com.murdhi.foldlink

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * FoldLink Keyboard. When the phones are linked, this phone shows its half and the
 * other phone pops up the other half; keys typed there come back here.
 */
class FoldImeService : InputMethodService() {
    private var holder: FrameLayout? = null

    private val keys = object : GlassKeyboardView.Listener {
        override fun onChar(c: Char) { currentInputConnection?.commitText(c.toString(), 1) }
        override fun onSpecial(k: GlassKeyboardView.Special) { special(k) }
    }

    private val onMsg: (JSONObject) -> Unit = { o ->
        when (o.optString("t")) {
            "key" -> currentInputConnection?.commitText(o.optString("c"), 1)
            "special" -> try { special(GlassKeyboardView.Special.valueOf(o.optString("k"))) } catch (_: Exception) {}
            "_connected", "_disconnected" -> if (isInputViewShown) rebuild()
        }
    }

    override fun onCreate() { super.onCreate(); Link.listeners.add(onMsg) }
    override fun onDestroy() { Link.listeners.remove(onMsg); super.onDestroy() }

    override fun onCreateInputView(): View = FrameLayout(this).also { holder = it }

    override fun onEvaluateFullscreenMode() = false

    private var split = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        rebuild()
        if (split) Link.send(Link.msg("kb_show"))
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (split) Link.send(Link.msg("kb_hide"))
    }

    /** On the wide stretched screen the keyboard is already as wide as both phones. */
    private fun onWideScreen(): Boolean = try {
        (window?.window?.decorView?.display?.displayId ?: 0) != 0
    } catch (_: Throwable) { false }

    private fun rebuild() {
        val h = holder ?: return
        h.removeAllViews()
        split = Link.connected && Prefs.get(this).getBoolean(Prefs.SPLIT_KB, true) && !onWideScreen()
        val mode = when {
            !split -> GlassKeyboardView.Mode.FULL
            Prefs.isLeft(this) -> GlassKeyboardView.Mode.LEFT
            else -> GlassKeyboardView.Mode.RIGHT
        }
        h.addView(GlassKeyboardView(this, mode, keys),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun special(k: GlassKeyboardView.Special) {
        when (k) {
            GlassKeyboardView.Special.BACKSPACE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            GlassKeyboardView.Special.LEFT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            GlassKeyboardView.Special.RIGHT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            GlassKeyboardView.Special.ENTER -> enter()
        }
    }

    private fun enter() {
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = ((info?.inputType ?: 0) and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiLine && action != EditorInfo.IME_ACTION_NONE)
            currentInputConnection?.performEditorAction(if (action == EditorInfo.IME_ACTION_UNSPECIFIED) EditorInfo.IME_ACTION_DONE else action)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }
}
