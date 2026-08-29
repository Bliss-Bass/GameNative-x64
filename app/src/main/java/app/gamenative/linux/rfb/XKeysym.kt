package app.gamenative.linux.rfb

import android.view.KeyEvent

/**
 * Android key events to X keysyms.
 *
 * Printable characters come from the event's own unicode value, so the device's keyboard
 * layout decides them rather than a table here; only the keys with no character -- and
 * the modifiers, which have a character on some layouts but must never be typed -- are
 * mapped explicitly.
 */
object XKeysym {

    private const val NONE = 0

    private val SPECIAL = mapOf(
        KeyEvent.KEYCODE_ENTER to 0xFF0D,
        KeyEvent.KEYCODE_NUMPAD_ENTER to 0xFF8D,
        KeyEvent.KEYCODE_DEL to 0xFF08,
        KeyEvent.KEYCODE_FORWARD_DEL to 0xFFFF,
        KeyEvent.KEYCODE_TAB to 0xFF09,
        KeyEvent.KEYCODE_ESCAPE to 0xFF1B,
        KeyEvent.KEYCODE_BACK to 0xFF1B,
        KeyEvent.KEYCODE_DPAD_LEFT to 0xFF51,
        KeyEvent.KEYCODE_DPAD_UP to 0xFF52,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0xFF53,
        KeyEvent.KEYCODE_DPAD_DOWN to 0xFF54,
        KeyEvent.KEYCODE_MOVE_HOME to 0xFF50,
        KeyEvent.KEYCODE_MOVE_END to 0xFF57,
        KeyEvent.KEYCODE_PAGE_UP to 0xFF55,
        KeyEvent.KEYCODE_PAGE_DOWN to 0xFF56,
        KeyEvent.KEYCODE_INSERT to 0xFF63,
        KeyEvent.KEYCODE_SPACE to 0x0020,
        KeyEvent.KEYCODE_SHIFT_LEFT to 0xFFE1,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 0xFFE2,
        KeyEvent.KEYCODE_CTRL_LEFT to 0xFFE3,
        KeyEvent.KEYCODE_CTRL_RIGHT to 0xFFE4,
        KeyEvent.KEYCODE_CAPS_LOCK to 0xFFE5,
        KeyEvent.KEYCODE_ALT_LEFT to 0xFFE9,
        KeyEvent.KEYCODE_ALT_RIGHT to 0xFFEA,
        KeyEvent.KEYCODE_META_LEFT to 0xFFEB,
        KeyEvent.KEYCODE_META_RIGHT to 0xFFEC,
    )

    /** Returns the keysym for [event], or 0 when the key has no X equivalent. */
    fun of(event: KeyEvent): Int {
        SPECIAL[event.keyCode]?.let { return it }

        if (event.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            return 0xFFBE + (event.keyCode - KeyEvent.KEYCODE_F1)
        }

        // Control combinations arrive with no unicode character, so the base character is
        // recovered from the key alone and the modifier is sent as its own keysym.
        val unicode = event.unicodeChar and KeyEvent.KEYCODE_UNKNOWN.inv()
        if (unicode != 0) return latin1OrUnicode(unicode)

        val unmodified = event.getUnicodeChar(0)
        if (unmodified != 0) return latin1OrUnicode(unmodified)

        return NONE
    }

    /**
     * Latin-1 keysyms are their own code points; everything else uses the Unicode range
     * that X reserved for exactly this.
     */
    private fun latin1OrUnicode(codePoint: Int): Int =
        if (codePoint in 0x20..0xFF) codePoint else codePoint or 0x01000000
}
