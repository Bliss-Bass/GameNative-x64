package app.gamenative.linux.rfb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * Presents an RFB desktop and forwards input back to it.
 *
 * The network runs on its own thread and draws straight to the surface: routing frames
 * through Compose state would copy the framebuffer again and tie the frame rate to
 * recomposition, neither of which this needs.
 */
@SuppressLint("ViewConstructor")
class RfbView(
    context: Context,
    private val port: Int,
    private val onDisconnected: (Throwable?) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback, RfbClient.Observer {

    private val client = RfbClient(port = port)
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var stopping = false

    private var framesPresented = 0L

    private var bitmap: Bitmap? = null

    /** Union of the rectangles changed since the last frame was drawn. */
    private val dirty = Rect()
    private val dirtyLock = Any()

    /** Set when the desktop size changed and the whole screen must be re-fetched. */
    @Volatile
    private var needsFullUpdate = false

    /** The size the surface last reported, which the desktop is asked to match. */
    @Volatile
    private var requestedWidth = 0

    @Volatile
    private var requestedHeight = 0

    @Volatile
    private var sizeRequestDueAt = 0L

    private var buttonMask = 0

    /**
     * Input waiting to be written to the socket. Writes cannot happen on the thread that
     * delivers the events, which is the UI thread, and buffering them also keeps a slow
     * write from stuttering the UI.
     */
    private val inputQueue = LinkedBlockingQueue<InputEvent>(INPUT_QUEUE_DEPTH)
    private var inputThread: Thread? = null

    private sealed interface InputEvent {
        data class Pointer(val x: Int, val y: Int, val mask: Int, val coalescable: Boolean) : InputEvent
        data class Key(val keysym: Int, val down: Boolean) : InputEvent
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (running) return
        running = true
        thread = Thread({ run() }, "rfb-$port").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        requestedWidth = width
        requestedHeight = height
        // Debounced: a drag of a freeform window produces a size on every frame, and each
        // resize makes the X server reallocate the screen and every client relayout.
        sizeRequestDueAt = System.currentTimeMillis() + RESIZE_DEBOUNCE_MS
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = disconnect()

    fun disconnect() {
        // Tells the reader that the end of the connection is expected, so leaving the
        // screen -- or merely backgrounding the app, which destroys the surface -- is not
        // reported as the session failing.
        stopping = true
        running = false
        client.close()
        inputQueue.clear()
        thread?.join(1_000)
        thread = null
        inputThread?.join(500)
        inputThread = null
    }

    /**
     * Hands an event to the sender thread.
     *
     * A pointer move may be dropped when the queue is full, since the next one supersedes
     * it; a button or key change may not, because a lost release leaves the guest holding
     * a button down forever.
     */
    private fun submit(event: InputEvent) {
        if (!client.isConnected) return
        val coalescable = (event as? InputEvent.Pointer)?.coalescable == true
        if (coalescable) {
            if (!inputQueue.offer(event)) Timber.d("[RfbView]: dropped a pointer move")
        } else {
            runCatching { inputQueue.put(event) }
        }
    }

    private fun sendQueuedInput() {
        while (running) {
            val event = inputQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            runCatching {
                when (event) {
                    is InputEvent.Pointer -> client.sendPointer(event.x, event.y, event.mask)
                    is InputEvent.Key -> client.sendKey(event.keysym, event.down)
                }
            }.onFailure { if (!stopping) Timber.w(it, "[RfbView]: input write failed") }
        }
    }

    private fun run() {
        var failure: Throwable? = null
        try {
            client.connect(this)
            inputThread = Thread({ sendQueuedInput() }, "rfb-input-$port").apply {
                isDaemon = true
                start()
            }
            resizeBuffers(client.width, client.height)
            client.requestUpdate(false)

            while (running) {
                if (client.pumpUpdate()) {
                    present()
                    val full = needsFullUpdate
                    needsFullUpdate = false
                    client.requestUpdate(incremental = !full)
                }
                applyPendingResize()
            }
        } catch (e: Throwable) {
            if (!stopping) {
                failure = e
                Timber.e(e, "[RfbView]: session on port %d ended", port)
            }
        } finally {
            running = false
            if (!stopping) post { onDisconnected(failure) }
        }
    }

    private fun applyPendingResize() {
        val due = sizeRequestDueAt
        if (due == 0L || System.currentTimeMillis() < due) return
        sizeRequestDueAt = 0L

        val width = requestedWidth
        val height = requestedHeight
        if (width <= 0 || height <= 0) return
        if (width == client.width && height == client.height) return

        Timber.i("[RfbView]: requesting desktop %dx%d", width, height)
        client.requestDesktopSize(width, height)
    }

    override fun onFramebufferUpdate(x: Int, y: Int, width: Int, height: Int) {
        synchronized(dirtyLock) { dirty.union(x, y, x + width, y + height) }
    }

    override fun onResize(width: Int, height: Int) {
        resizeBuffers(width, height)
        // The update carrying a new size carries no pixels, so the screen is stale until
        // a full one is asked for.
        needsFullUpdate = true
    }

    private fun resizeBuffers(width: Int, height: Int) {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        synchronized(dirtyLock) { dirty.set(0, 0, width, height) }
    }

    private fun present() {
        val bitmap = bitmap ?: return
        val region = synchronized(dirtyLock) {
            if (dirty.isEmpty) return
            Rect(dirty).also { dirty.setEmpty() }
        }
        if (bitmap.width != client.width || bitmap.height != client.height) return

        region.intersect(0, 0, bitmap.width, bitmap.height)
        if (region.isEmpty) return

        bitmap.setPixels(
            client.pixels,
            region.top * client.width + region.left,
            client.width,
            region.left,
            region.top,
            region.width(),
            region.height(),
        )

        val canvas = holder.lockCanvas()
        if (canvas == null) {
            Timber.w("[RfbView]: no canvas; surface not ready")
            return
        }
        try {
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), null)
        } catch (e: Throwable) {
            Timber.e(e, "[RfbView]: draw failed")
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Throwable) {
                Timber.e(e, "[RfbView]: post failed")
            }
        }

        framesPresented++
        if (framesPresented == 1L || framesPresented % 600 == 0L) {
            Timber.i(
                "[RfbView]: frame %d, %dx%d of %dx%d desktop into %dx%d view",
                framesPresented, region.width(), region.height(),
                client.width, client.height, width, height,
            )
        }
    }

    // Input --------------------------------------------------------------------------

    /**
     * MotionEvent x/y may be window-relative when the Activity forwards the event straight
     * to this view. [MotionEvent.getRawX]/[getRawY] minus our on-screen origin is always
     * view-local, which keeps the guest pointer aligned with Android's cursor under freeform
     * caption insets.
     */
    private fun localX(event: MotionEvent): Float {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return event.rawX - loc[0]
    }

    private fun localY(event: MotionEvent): Float {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return event.rawY - loc[1]
    }

    /** View coordinates to desktop coordinates, which differ until a resize has landed. */
    private fun toDesktopX(x: Float): Int =
        if (width == 0) 0 else (x * client.width / width).roundToInt().coerceIn(0, (client.width - 1).coerceAtLeast(0))

    private fun toDesktopY(y: Float): Int =
        if (height == 0) 0 else (y * client.height / height).roundToInt().coerceIn(0, (client.height - 1).coerceAtLeast(0))

    /**
     * Guest pointer position for relative devices (touchpads). Absolute mouse/touchscreen
     * events overwrite this so the next relative delta still starts from where the cursor is.
     */
    private var cursorX = 0
    private var cursorY = 0

    private var scrollAccumV = 0f
    private var scrollAccumH = 0f

    /** Two-finger scroll on the touchscreen/touchpad; cancelled when the second finger lifts. */
    private var touchScrollActive = false
    private var touchScrollLastY = 0f
    private var touchScrollLastX = 0f
    private var touchFingerCount = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!client.isConnected) return false
        requestFocus()

        // USB pads are often IDC'd as touchScreen (BlissTouchMapper) while still exposing a
        // TOUCHPAD device class / "Touchpad" name. Absolute-mapping those contacts warps the
        // guest cursor independently of Android's pointer — including during two-finger scroll.
        if (isTouchpadDevice(event)) {
            return handlePhysicalTouchpad(event)
        }
        if (isMouseLike(event)) {
            return handleMouseLikeTouch(event)
        }
        return handleTouchscreen(event)
    }

    /**
     * True for real touchpad hardware even when an IDC remaps it to touchScreen.
     *
     * Prefer device sources/name over the event's source bits: after an IDC override the
     * event may only advertise SOURCE_TOUCHSCREEN.
     */
    private fun isTouchpadDevice(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return true
        val device = event.device ?: return false
        if (device.sources and InputDevice.SOURCE_TOUCHPAD != 0) return true
        val name = device.name ?: return false
        return name.contains("Touchpad", ignoreCase = true) ||
            name.contains("Trackpad", ignoreCase = true)
    }

    /** USB/BT mice and the system cursor after the framework has remapped a pad. */
    private fun isMouseLike(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)

    /**
     * Physical touchpad: never absolute-map finger contacts.
     *
     * One-finger motion is owned by the companion mouse HOVER_MOVE stream (Android cursor).
     * Two-finger motion becomes wheel clicks at the current guest pointer — do not move it.
     */
    private fun handlePhysicalTouchpad(event: MotionEvent): Boolean {
        val multi = event.pointerCount >= 2 ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
            touchScrollActive

        if (!multi) {
            // Consume one-finger pad contacts so a touchScreen IDC cannot treat them as
            // absolute screen taps. Cursor tracking stays on HOVER_MOVE.
            if (event.actionMasked == MotionEvent.ACTION_UP && touchpadMayTap &&
                !movedFarFrom(event.x, event.y)
            ) {
                clickLeftAtCursor()
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                touchpadMayTap = true
                pressX = event.x
                pressY = event.y
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE && movedFarFrom(event.x, event.y)) {
                touchpadMayTap = false
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchpadMayTap = false
            }
            return true
        }
        return handleTouchpadDigitizer(event)
    }

    private fun handleMouseLikeTouch(event: MotionEvent): Boolean {
        // Multi-contact "mouse" streams are almost always a touchpad the framework still
        // tagged as SOURCE_MOUSE. Never absolute-warp from those — treat as scroll.
        if (event.pointerCount >= 2 ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
            touchScrollActive
        ) {
            return handleTouchpadDigitizer(event)
        }

        val x = toDesktopX(localX(event))
        val y = toDesktopY(localY(event))
        cursorX = x
        cursorY = y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                buttonMask = mouseButtons(event)
                submit(InputEvent.Pointer(x, y, buttonMask, coalescable = event.actionMasked == MotionEvent.ACTION_MOVE))
            }
            else -> return false
        }
        return true
    }

    /**
     * Touchpad multi-finger contacts: scroll via wheel clicks without moving the pointer.
     */
    private fun handleTouchpadDigitizer(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                touchFingerCount = event.pointerCount
                if (event.pointerCount >= 2) {
                    touchpadMayTap = false
                    if (buttonMask and BUTTON_LEFT != 0) {
                        buttonMask = buttonMask and BUTTON_LEFT.inv()
                        submit(InputEvent.Pointer(cursorX, cursorY, buttonMask, coalescable = false))
                    }
                    cancelRightClick()
                    touchScrollActive = true
                    touchScrollLastY = (event.getY(0) + event.getY(1)) * 0.5f
                    touchScrollLastX = (event.getX(0) + event.getX(1)) * 0.5f
                    scrollAccumV = 0f
                    scrollAccumH = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    touchpadMayTap = false
                    val midY = (event.getY(0) + event.getY(1)) * 0.5f
                    val midX = (event.getX(0) + event.getX(1)) * 0.5f
                    if (!touchScrollActive) {
                        touchScrollActive = true
                        touchScrollLastY = midY
                        touchScrollLastX = midX
                    } else {
                        emitScrollFromDelta(midY - touchScrollLastY, midX - touchScrollLastX)
                        touchScrollLastY = midY
                        touchScrollLastX = midX
                    }
                }
                // Never moveCursorBy from pad contacts — that detaches the guest pointer.
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    touchFingerCount = 0
                    touchScrollActive = false
                    touchpadMayTap = false
                } else {
                    touchFingerCount = event.pointerCount - 1
                    if (touchFingerCount < 2) touchScrollActive = false
                }
            }
        }
        return true
    }

    private fun handleTouchscreen(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchFingerCount = 1
                touchScrollActive = false
                val vx = localX(event)
                val vy = localY(event)
                val x = toDesktopX(vx)
                val y = toDesktopY(vy)
                cursorX = x
                cursorY = y
                buttonMask = buttonMask or BUTTON_LEFT
                submit(InputEvent.Pointer(x, y, 0, coalescable = false))
                submit(InputEvent.Pointer(x, y, buttonMask, coalescable = false))
                scheduleRightClick(x, y, vx, vy)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                touchFingerCount = event.pointerCount
                if (event.pointerCount >= 2) {
                    // Second finger: this is a scroll, not a drag. Lift the left button so GTK/Qt
                    // menus and grab handlers do not see a held click.
                    cancelRightClick()
                    if (buttonMask and BUTTON_LEFT != 0) {
                        buttonMask = buttonMask and BUTTON_LEFT.inv()
                        submit(InputEvent.Pointer(cursorX, cursorY, buttonMask, coalescable = false))
                    }
                    touchScrollActive = true
                    touchScrollLastY = (event.getY(0) + event.getY(1)) * 0.5f
                    touchScrollLastX = (event.getX(0) + event.getX(1)) * 0.5f
                    scrollAccumV = 0f
                    scrollAccumH = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchScrollActive && event.pointerCount >= 2) {
                    val midY = (event.getY(0) + event.getY(1)) * 0.5f
                    val midX = (event.getX(0) + event.getX(1)) * 0.5f
                    emitScrollFromDelta(midY - touchScrollLastY, midX - touchScrollLastX)
                    touchScrollLastY = midY
                    touchScrollLastX = midX
                } else if (!touchScrollActive) {
                    val vx = localX(event)
                    val vy = localY(event)
                    if (movedFarFrom(vx, vy)) cancelRightClick()
                    val x = toDesktopX(vx)
                    val y = toDesktopY(vy)
                    cursorX = x
                    cursorY = y
                    submit(InputEvent.Pointer(x, y, buttonMask, coalescable = true))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                touchFingerCount = event.pointerCount - 1
                if (touchFingerCount < 2) touchScrollActive = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelRightClick()
                touchFingerCount = 0
                touchScrollActive = false
                buttonMask = buttonMask and BUTTON_LEFT.inv()
                submit(InputEvent.Pointer(cursorX, cursorY, buttonMask, coalescable = false))
            }
        }
        return true
    }

    private fun moveCursorBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val scaleX = if (width > 0) client.width.toFloat() / width else 1f
        val scaleY = if (height > 0) client.height.toFloat() / height else 1f
        cursorX = (cursorX + dx * scaleX).roundToInt().coerceIn(0, (client.width - 1).coerceAtLeast(0))
        cursorY = (cursorY + dy * scaleY).roundToInt().coerceIn(0, (client.height - 1).coerceAtLeast(0))
        submit(InputEvent.Pointer(cursorX, cursorY, buttonMask, coalescable = true))
    }

    /**
     * Converts finger-drag distance in view/pad pixels into RFB wheel clicks.
     *
     * Threshold matches the Wine [com.winlator.widget.TouchpadView] path closely enough that the
     * same physical motion scrolls about as far in either session type. Positive [deltaViewY]
     * (finger moved down) scrolls up, matching typical touchpad/content scroll.
     */
    private fun emitScrollFromDelta(deltaViewY: Float, deltaViewX: Float = 0f) {
        scrollAccumV += deltaViewY
        scrollAccumH += deltaViewX
        val step = TOUCH_SCROLL_STEP_PX
        while (scrollAccumV <= -step) {
            emitWheel(BUTTON_WHEEL_DOWN)
            scrollAccumV += step
        }
        while (scrollAccumV >= step) {
            emitWheel(BUTTON_WHEEL_UP)
            scrollAccumV -= step
        }
        while (scrollAccumH <= -step) {
            emitWheel(BUTTON_WHEEL_LEFT)
            scrollAccumH += step
        }
        while (scrollAccumH >= step) {
            emitWheel(BUTTON_WHEEL_RIGHT)
            scrollAccumH -= step
        }
    }

    private fun emitWheel(button: Int) {
        submit(InputEvent.Pointer(cursorX, cursorY, buttonMask or button, coalescable = false))
        submit(InputEvent.Pointer(cursorX, cursorY, buttonMask, coalescable = false))
    }

    private fun clickLeftAtCursor() {
        submit(InputEvent.Pointer(cursorX, cursorY, buttonMask or BUTTON_LEFT, coalescable = false))
        submit(InputEvent.Pointer(cursorX, cursorY, buttonMask, coalescable = false))
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    // A long press as a right-click ------------------------------------------------------
    //
    // X has no notion of a long press, and the menus that matter here -- openbox's root menu,
    // a file manager's context menu -- are all on button three. Without this there is no way
    // to reach any of them from a touch screen.

    private var pressX = 0f
    private var pressY = 0f
    private var lastPadX = 0f
    private var lastPadY = 0f
    private var touchpadMayTap = false
    private var rightClickPending: Runnable? = null

    private fun scheduleRightClick(desktopX: Int, desktopY: Int, viewX: Float, viewY: Float) {
        cancelRightClick()
        pressX = viewX
        pressY = viewY

        val fire = Runnable {
            rightClickPending = null
            // The left button went down when the finger did, and a menu opened while it is
            // still held would be dismissed by the release. Lifting it first is what a mouse
            // user would have done.
            buttonMask = buttonMask and BUTTON_LEFT.inv()
            submit(InputEvent.Pointer(desktopX, desktopY, buttonMask, coalescable = false))
            submit(InputEvent.Pointer(desktopX, desktopY, buttonMask or BUTTON_RIGHT, coalescable = false))
            submit(InputEvent.Pointer(desktopX, desktopY, buttonMask, coalescable = false))
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        rightClickPending = fire
        postDelayed(fire, ViewConfiguration.getLongPressTimeout().toLong())
    }

    /** Whether the finger has travelled far enough that this is a drag, not a press. */
    private fun movedFarFrom(viewX: Float, viewY: Float): Boolean {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        return abs(viewX - pressX) > slop || abs(viewY - pressY) > slop
    }

    private fun cancelRightClick() {
        rightClickPending?.let { removeCallbacks(it) }
        rightClickPending = null
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!client.isConnected) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                // rawX/rawY → view-local so Activity-forwarded window coords still align with
                // the Android cursor under freeform caption insets.
                val x = toDesktopX(localX(event))
                val y = toDesktopY(localY(event))
                cursorX = x
                cursorY = y
                submit(InputEvent.Pointer(x, y, buttonMask, coalescable = true))
            }
            MotionEvent.ACTION_SCROLL -> {
                // Leave cursorX/Y alone — scroll must not warp the guest pointer.
                var v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                var h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                // API 34+ gesture axes from precision touchpads when VSCROLL is empty.
                if (v == 0f && android.os.Build.VERSION.SDK_INT >= 34) {
                    v = -event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE)
                }
                if (h == 0f && android.os.Build.VERSION.SDK_INT >= 34) {
                    h = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE)
                }
                emitAxisScroll(v, vertical = true)
                emitAxisScroll(h, vertical = false)
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val x = toDesktopX(localX(event))
                val y = toDesktopY(localY(event))
                cursorX = x
                cursorY = y
                buttonMask = mouseButtons(event)
                submit(InputEvent.Pointer(x, y, buttonMask, coalescable = false))
            }
            else -> return super.onGenericMotionEvent(event)
        }
        return true
    }

    /**
     * Android touchpads often deliver fractional AXIS_*SCROLL values. Accumulate until a full
     * notch so smooth gestures become discrete X wheel clicks without losing small steps.
     */
    private fun emitAxisScroll(amount: Float, vertical: Boolean) {
        if (amount == 0f) return
        if (vertical) {
            scrollAccumV += amount
            while (scrollAccumV >= 1f) {
                emitWheel(BUTTON_WHEEL_UP)
                scrollAccumV -= 1f
            }
            while (scrollAccumV <= -1f) {
                emitWheel(BUTTON_WHEEL_DOWN)
                scrollAccumV += 1f
            }
        } else {
            scrollAccumH += amount
            while (scrollAccumH >= 1f) {
                emitWheel(BUTTON_WHEEL_RIGHT)
                scrollAccumH -= 1f
            }
            while (scrollAccumH <= -1f) {
                emitWheel(BUTTON_WHEEL_LEFT)
                scrollAccumH += 1f
            }
        }
    }

    private fun mouseButtons(event: MotionEvent): Int {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER == 0) return buttonMask
        var mask = 0
        val state = event.buttonState
        if (state and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or BUTTON_LEFT
        if (state and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or BUTTON_MIDDLE
        if (state and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or BUTTON_RIGHT
        return mask
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = sendKey(event, true) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = sendKey(event, false) || super.onKeyUp(keyCode, event)

    private fun sendKey(event: KeyEvent, down: Boolean): Boolean {
        if (!client.isConnected) return false
        // Back stays with Android so there is always a way out of the desktop.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false

        val keysym = XKeysym.of(event)
        if (keysym == 0) return false
        submit(InputEvent.Key(keysym, down))
        return true
    }

    companion object {
        private const val RESIZE_DEBOUNCE_MS = 350L

        /** Deep enough to absorb a burst of motion, shallow enough not to lag behind it. */
        private const val INPUT_QUEUE_DEPTH = 128

        /** View pixels of two-finger travel per X wheel notch. */
        private const val TOUCH_SCROLL_STEP_PX = 48f

        private const val BUTTON_LEFT = 1
        private const val BUTTON_MIDDLE = 1 shl 1
        private const val BUTTON_RIGHT = 1 shl 2
        private const val BUTTON_WHEEL_UP = 1 shl 3
        private const val BUTTON_WHEEL_DOWN = 1 shl 4
        private const val BUTTON_WHEEL_LEFT = 1 shl 5
        private const val BUTTON_WHEEL_RIGHT = 1 shl 6
    }
}
