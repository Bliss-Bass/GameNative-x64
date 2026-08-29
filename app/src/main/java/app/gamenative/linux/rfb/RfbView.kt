package app.gamenative.linux.rfb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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

    /** View coordinates to desktop coordinates, which differ until a resize has landed. */
    private fun toDesktopX(x: Float): Int =
        if (width == 0) 0 else (x * client.width / width).roundToInt()

    private fun toDesktopY(y: Float): Int =
        if (height == 0) 0 else (y * client.height / height).roundToInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!client.isConnected) return false
        requestFocus()

        val x = toDesktopX(event.x)
        val y = toDesktopY(event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                buttonMask = buttonMask or BUTTON_LEFT
                // Position first with no button: a click that arrives at the same instant
                // as the move is treated by some toolkits as a click wherever the pointer
                // was before.
                submit(InputEvent.Pointer(x, y, 0, coalescable = false))
                submit(InputEvent.Pointer(x, y, buttonMask, coalescable = false))
            }
            MotionEvent.ACTION_MOVE -> submit(InputEvent.Pointer(x, y, buttonMask, coalescable = true))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                buttonMask = buttonMask and BUTTON_LEFT.inv()
                submit(InputEvent.Pointer(x, y, buttonMask, coalescable = false))
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!client.isConnected) return false
        val x = toDesktopX(event.x)
        val y = toDesktopY(event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> submit(InputEvent.Pointer(x, y, buttonMask, coalescable = true))
            MotionEvent.ACTION_SCROLL -> {
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vertical != 0f) {
                    // Wheels are buttons in X, pressed and released once per notch.
                    val button = if (vertical > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN
                    submit(InputEvent.Pointer(x, y, buttonMask or button, coalescable = false))
                    submit(InputEvent.Pointer(x, y, buttonMask, coalescable = false))
                }
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                buttonMask = mouseButtons(event)
                submit(InputEvent.Pointer(x, y, buttonMask, coalescable = false))
            }
            else -> return super.onGenericMotionEvent(event)
        }
        return true
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

        private const val BUTTON_LEFT = 1
        private const val BUTTON_MIDDLE = 1 shl 1
        private const val BUTTON_RIGHT = 1 shl 2
        private const val BUTTON_WHEEL_UP = 1 shl 3
        private const val BUTTON_WHEEL_DOWN = 1 shl 4
    }
}
