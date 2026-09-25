package app.gamenative.linux

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.gamenative.data.TouchGestureConfig
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerView
import com.winlator.xserver.Pointer
import com.winlator.xserver.Window
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import com.winlator.xserver.events.Event
import timber.log.Timber
import kotlin.math.abs

/**
 * Freeform presenter for a Linux [XServer]: Vulkan surface plus [TouchpadView].
 *
 * Input policy matches the RFB path: hardware keys go to X without claiming soft-IME
 * chrome; soft keyboard is opt-in via the show-keyboard gesture (or on soft-only devices,
 * a tap in a KeyPress-capable guest window). Pointer overlay never takes Android focus.
 *
 * Two-finger scroll matches RFB: touchscreen pan → wheel clicks, precision touchpad
 * TWO_FINGER_SWIPE / GESTURE_SCROLL axes → wheel clicks, and fractional ACTION_SCROLL
 * accumulation inside [TouchpadView].
 */
@SuppressLint("ViewConstructor")
class LinuxXServerView(
    context: Context,
    private val xServer: XServer,
) : FrameLayout(context) {

    private val xServerView = XServerView(context, xServer, "vulkan")
    private val touchpadView: TouchpadView

    private var scrollAccumV = 0f
    private var scrollAccumH = 0f
    private var padGestureScrollActive = false

    /** Soft IME is opt-in; physical keys still reach X while this view has focus. */
    @Volatile
    private var imeWanted = false

    init {
        xServer.renderer = xServerView.renderer
        touchpadView = TouchpadView(context, xServer, /* capturePointerOnExternalMouse = */ true).apply {
            setTouchscreenMode(true)
            setMoveCursorToTouchpoint(true)
            // Pointer overlay must not steal Android focus — otherwise a tap moves focus here
            // and hardware keys never reach [LinuxXServerView.dispatchKeyEvent].
            isFocusable = false
            isFocusableInTouchMode = false
            // Linux content scroll: two-finger drag = wheel, not arrow-key pan (game default).
            setGestureConfig(
                TouchGestureConfig(
                    twoFingerDragAction = TouchGestureConfig.PAN_SCROLL_WHEEL,
                    pinchAction = TouchGestureConfig.ZOOM_SCROLL_WHEEL,
                ),
            )
            setShowKeyboardCallback { showIme() }
        }

        addView(xServerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(touchpadView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isFocusable = true
        isFocusableInTouchMode = true
        // When the user dismisses the soft keyboard, drop the text-editor claim so the
        // floating input-options chrome does not linger for the rest of the session.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (!imeVisible && imeWanted) {
                imeWanted = false
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.restartInput(this)
            }
            insets
        }
        Timber.i(
            "[LinuxXServerView]: attached %dx%d (touchscreen+ime-on-demand+scroll)",
            xServer.screenInfo.width.toInt(),
            xServer.screenInfo.height.toInt(),
        )
    }

    override fun onCheckIsTextEditor(): Boolean = imeWanted

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!imeWanted) return null
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        // NONE so Enter is a key, not an editor action that never reaches X.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.forEach { sendCharacter(it) }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        xServer.injectKeyPress(XKeycode.KEY_BKSP, 0)
                        xServer.injectKeyRelease(XKeycode.KEY_BKSP)
                    }
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (injectAndroidKey(event)) return true
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                // Some IMEs still fire an editor action for Enter despite IME_ACTION_NONE.
                injectEnter()
                return true
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        xServerView.onResume()
        // Focus for physical keys without raising soft IME chrome.
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        hideIme()
        xServerView.onPause()
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Precision pads: InputReader sends TWO_FINGER_SWIPE + GESTURE_SCROLL_* (not ACTION_SCROLL).
        if (consumeGesturePadScroll(ev)) return true

        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            // Touchscreen click paths can leave BUTTON_LEFT latched; xterm ignores KeyPress
            // while a button is down (selection). Always clear pointer buttons on lift.
            releasePointerButtons()
            if (ev.pointerCount <= 1 && !padGestureScrollActive) {
                ensureTypingFocus()
            }
        }
        return handled
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (injectAndroidKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Soft IME often eats Enter and other keys before [dispatchKeyEvent]. Forward hardware
     * (and adb) keys to X here so a tap that raised the IME does not brick typing.
     * Soft-keyboard glyphs still arrive via [InputConnection.commitText].
     */
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (imeWanted) {
                hideIme()
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }
        if (injectAndroidKey(event)) return true
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) hideIme()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
            event.getActionButton() == MotionEvent.BUTTON_PRIMARY
        ) {
            post { ensureTypingFocus() }
        }
        if (touchpadView.onGenericMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun releasePointerButtons() {
        for (button in Pointer.Button.values()) {
            if (xServer.pointer.isButtonPressed(button)) {
                xServer.injectPointerButtonRelease(button)
            }
        }
    }

    /** Keep the view key-focused; raise soft IME only when there is no hardware keyboard. */
    private fun ensureTypingFocus() {
        if (!guestWantsKeyboard()) {
            hideIme()
            return
        }
        requestFocus()
        // A docked/USB keyboard makes Android soft-IME chrome steal keys from X. Soft-only
        // devices still need showSoftInput on tap so on-screen typing works.
        if (!hasHardwareAlphaKeyboard()) {
            showIme()
        }
    }

    private fun hasHardwareAlphaKeyboard(): Boolean {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.isVirtual) continue
            if (device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true
        }
        return false
    }

    private fun guestWantsKeyboard(): Boolean {
        val focused = xServer.windowManager.focusedWindow
        if (windowAcceptsKeys(focused)) return true
        val atPointer = xServer.windowManager.findPointWindow(
            xServer.pointer.clampedX,
            xServer.pointer.clampedY,
        )
        return windowAcceptsKeys(atPointer)
    }

    private fun windowAcceptsKeys(window: Window?): Boolean {
        if (window == null || window == xServer.windowManager.rootWindow) return false
        if (window.hasEventListenerFor(Event.KEY_PRESS)) return true
        var childKeys = false
        for (child in window.children) {
            if (!child.attributes.isMapped) continue
            if (windowAcceptsKeys(child)) {
                childKeys = true
                break
            }
        }
        if (childKeys) return true
        val ancestor = window.getAncestorWithEventId(Event.KEY_PRESS)
        return ancestor != null && ancestor != xServer.windowManager.rootWindow
    }

    fun showIme() {
        post {
            if (!imeWanted) {
                imeWanted = true
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.restartInput(this)
            }
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideIme() {
        if (!imeWanted && !hasWindowFocus()) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
            return
        }
        imeWanted = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        imm?.restartInput(this)
        // Keep focus so a physical keyboard still reaches X without soft IME chrome.
        requestFocus()
    }

    fun release() {
        runCatching { hideIme() }
        runCatching { xServerView.onPause() }
    }

    /**
     * True when InputReader is delivering a precision-touchpad two-finger scroll as a fake
     * single-finger gesture (classification + GESTURE_SCROLL axes), not raw multi-touch.
     */
    private fun isGesturePadScroll(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (event.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) return true
        if (Build.VERSION.SDK_INT < 34) return false
        return event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE) != 0f ||
            event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE) != 0f
    }

    private fun isTouchpadDevice(event: MotionEvent): Boolean {
        val sources = event.source
        if (sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD) return true
        val name = event.device?.name?.lowercase() ?: return false
        return name.contains("touchpad") || name.contains("trackpad")
    }

    /** Emit X wheel clicks from GESTURE_SCROLL_* without warping the guest cursor. */
    private fun consumeGesturePadScroll(event: MotionEvent): Boolean {
        if (!isTouchpadDevice(event) && !isGesturePadScroll(event)) return false
        if (!isGesturePadScroll(event)) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                padGestureScrollActive = true
                scrollAccumV = 0f
                scrollAccumH = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                padGestureScrollActive = true
                if (Build.VERSION.SDK_INT >= 34) {
                    for (h in 0 until event.historySize) {
                        emitGestureScrollSample(
                            event.getHistoricalAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, h),
                            event.getHistoricalAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE, h),
                        )
                    }
                    emitGestureScrollSample(
                        event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE),
                        event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE),
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                padGestureScrollActive = false
            }
            else -> Unit
        }
        return true
    }

    private fun emitGestureScrollSample(gestureScrollY: Float, gestureScrollX: Float) {
        if (gestureScrollY == 0f && gestureScrollX == 0f) return
        // Axes are finger-delta with InputReader sign; negate so finger-down → wheel up.
        emitScrollFromDelta(-gestureScrollY, -gestureScrollX)
    }

    private fun emitScrollFromDelta(deltaViewY: Float, deltaViewX: Float = 0f) {
        scrollAccumV += deltaViewY
        scrollAccumH += deltaViewX
        val step = SCROLL_STEP_PX
        while (scrollAccumV <= -step) {
            injectWheel(Pointer.Button.BUTTON_SCROLL_DOWN)
            scrollAccumV += step
        }
        while (scrollAccumV >= step) {
            injectWheel(Pointer.Button.BUTTON_SCROLL_UP)
            scrollAccumV -= step
        }
        // Horizontal wheel not in core X buttons — absorb so accum does not grow forever.
        while (abs(scrollAccumH) >= step) {
            scrollAccumH -= if (scrollAccumH > 0) step else -step
        }
    }

    private fun injectWheel(button: Pointer.Button) {
        xServer.injectPointerButtonPress(button)
        xServer.injectPointerButtonRelease(button)
    }

    private fun injectAndroidKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) injectEnterDown()
                KeyEvent.ACTION_UP -> injectEnterUp()
            }
            return true
        }
        return xServer.keyboard.onKeyEvent(event)
    }

    private fun injectEnter() {
        injectEnterDown()
        injectEnterUp()
    }

    private fun injectEnterDown() {
        // Pass XK_Return explicitly; Keyboard.onKeyEvent uses unicode 0 for ENTER which is fine
        // for the keymap, but IME paths sometimes skip onKeyEvent entirely.
        xServer.injectKeyPress(XKeycode.KEY_ENTER, XK_RETURN)
    }

    private fun injectEnterUp() {
        xServer.injectKeyRelease(XKeycode.KEY_ENTER)
    }

    private fun sendCharacter(char: Char) {
        if (char == '\n' || char == '\r') {
            injectEnter()
            return
        }
        val keyCode = charToKeyCode(char) ?: return
        val shift = char.isUpperCase() || char in SHIFTED_SYMBOLS
        val meta = if (shift) KeyEvent.META_SHIFT_ON else 0
        xServer.keyboard.onKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        xServer.keyboard.onKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun charToKeyCode(char: Char): Int? {
        val c = if (char in 'A'..'Z') char.lowercaseChar() else char
        return when (c) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
            '0', ')' -> KeyEvent.KEYCODE_0
            '1', '!' -> KeyEvent.KEYCODE_1
            '2', '@' -> KeyEvent.KEYCODE_2
            '3', '#' -> KeyEvent.KEYCODE_3
            '4', '$' -> KeyEvent.KEYCODE_4
            '5', '%' -> KeyEvent.KEYCODE_5
            '6', '^' -> KeyEvent.KEYCODE_6
            '7', '&' -> KeyEvent.KEYCODE_7
            '8', '*' -> KeyEvent.KEYCODE_8
            '9', '(' -> KeyEvent.KEYCODE_9
            ' ' -> KeyEvent.KEYCODE_SPACE
            '-', '_' -> KeyEvent.KEYCODE_MINUS
            '=', '+' -> KeyEvent.KEYCODE_EQUALS
            '[', '{' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']', '}' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\', '|' -> KeyEvent.KEYCODE_BACKSLASH
            ';', ':' -> KeyEvent.KEYCODE_SEMICOLON
            '\'', '"' -> KeyEvent.KEYCODE_APOSTROPHE
            ',', '<' -> KeyEvent.KEYCODE_COMMA
            '.', '>' -> KeyEvent.KEYCODE_PERIOD
            '/', '?' -> KeyEvent.KEYCODE_SLASH
            '`', '~' -> KeyEvent.KEYCODE_GRAVE
            else -> null
        }
    }

    companion object {
        private const val SCROLL_STEP_PX = 48f
        /** X11 XK_Return */
        private const val XK_RETURN = 65293
        private val SHIFTED_SYMBOLS = setOf(
            ')', '!', '@', '#', '$', '%', '^', '&', '*', '(',
            '_', '+', '{', '}', '|', ':', '"', '<', '>', '?', '~',
        )
    }
}
