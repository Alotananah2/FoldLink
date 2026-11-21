package com.murdhi.foldlink

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/** "FoldLink Keyboard" — a normal Android keyboard (English + Arabic) with the glass look. */
class FoldImeService : InputMethodService() {
    override fun onCreateInputView(): View =
        GlassKeyboardView(this, allowArabic = true, listener = object : GlassKeyboardView.Listener {
            override fun onChar(c: Char) { currentInputConnection?.commitText(c.toString(), 1) }
            override fun onSpecial(k: GlassKeyboardView.Special) {
                when (k) {
                    GlassKeyboardView.Special.BACKSPACE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    GlassKeyboardView.Special.LEFT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                    GlassKeyboardView.Special.RIGHT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                    GlassKeyboardView.Special.ENTER -> enter()
                }
            }
        })

    private fun enter() {
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = ((info?.inputType ?: 0) and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            currentInputConnection?.performEditorAction(action)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }
}
