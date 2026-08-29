package app.gamenative.linux.rfb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import timber.log.Timber

/**
 * A minimal RFB (VNC) client, enough to present one local X server.
 *
 * Only raw and copy-rectangle encodings are decoded: the server is a process on this
 * device, so the compressing encodings would trade CPU for bandwidth that costs nothing.
 * A full frame at 1280x800 is about 4MB over loopback, and only damaged rectangles are
 * sent after the first one.
 *
 * Not thread-safe for reads: [pumpUpdate] is expected to run on one thread. Writes are
 * synchronized, so input and resize requests may come from another.
 */
class RfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int,
) {

    interface Observer {
        /** Pixels changed within this rectangle of the framebuffer. */
        fun onFramebufferUpdate(x: Int, y: Int, width: Int, height: Int)

        /** The desktop changed size; the framebuffer has been reallocated. */
        fun onResize(width: Int, height: Int)
    }

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private val writeLock = Any()

    var width = 0
        private set
    var height = 0
        private set

    /** The framebuffer, one opaque ARGB_8888 pixel per entry, row-major. */
    var pixels = IntArray(0)
        private set

    var desktopName = ""
        private set

    @Volatile
    var isConnected = false
        private set

    private var observer: Observer? = null

    /** Scratch for decoding a rectangle, grown as needed rather than per update. */
    private var rowBuffer = ByteArray(0)

    fun connect(observer: Observer) {
        this.observer = observer
        // Resolved outside the apply block below, where `port` would otherwise mean the
        // socket's own port -- zero until it is connected.
        val address = InetSocketAddress(host, port)
        socket = Socket().apply {
            tcpNoDelay = true          // input latency matters more than packet efficiency
            connect(address, CONNECT_TIMEOUT_MS)
            soTimeout = READ_TIMEOUT_MS
        }
        input = DataInputStream(socket.getInputStream().buffered(256 * 1024))
        output = DataOutputStream(socket.getOutputStream().buffered())

        handshake()
        isConnected = true
        Timber.i("[RfbClient]: connected to %s:%d, %dx%d %s", host, port, width, height, desktopName)
    }

    private fun handshake() {
        val version = ByteArray(12).also { input.readFully(it) }.decodeToString().trim()
        // 3.8 is what TigerVNC speaks and the only version whose SecurityResult carries a
        // reason string, which makes failures diagnosable.
        output.write("RFB 003.008\n".toByteArray())
        output.flush()

        val types = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        if (types.isEmpty()) throw IOException("Server rejected the connection during handshake")
        if (types.none { it.toInt() == SECURITY_NONE }) {
            throw IOException("Server requires authentication (offered ${types.joinToString()})")
        }
        output.writeByte(SECURITY_NONE)
        output.flush()

        val result = input.readInt()
        if (result != 0) throw IOException("Authentication failed: reason ${readString()}")

        output.writeByte(1)   // shared: do not disconnect other clients
        output.flush()

        width = input.readUnsignedShort()
        height = input.readUnsignedShort()
        readPixelFormat()
        desktopName = readString()
        allocate(width, height)

        setEncodings()
        Timber.i("[RfbClient]: server version %s", version)
    }

    /**
     * Asks for exactly the format the framebuffer already uses, rather than adapting to
     * whatever the server prefers: 32bpp true colour with red high and blue low is what an
     * Android ARGB_8888 bitmap wants, so decoding is a copy plus an alpha fill.
     */
    private fun readPixelFormat() {
        input.skipBytes(16)
        synchronized(writeLock) {
            output.writeByte(0)          // SetPixelFormat
            output.write(ByteArray(3))   // padding
            output.writeByte(32)         // bits per pixel
            output.writeByte(24)         // depth
            output.writeByte(0)          // little endian
            output.writeByte(1)          // true colour
            output.writeShort(255); output.writeShort(255); output.writeShort(255)
            output.writeByte(16); output.writeByte(8); output.writeByte(0)
            output.write(ByteArray(3))   // padding
            output.flush()
        }
    }

    private fun setEncodings() {
        val encodings = intArrayOf(ENCODING_COPY_RECT, ENCODING_RAW, ENCODING_EXTENDED_DESKTOP_SIZE, ENCODING_DESKTOP_SIZE)
        synchronized(writeLock) {
            output.writeByte(2)
            output.writeByte(0)
            output.writeShort(encodings.size)
            encodings.forEach { output.writeInt(it) }
            output.flush()
        }
    }

    private fun readString(): String {
        val length = input.readInt()
        if (length !in 0..MAX_STRING) throw IOException("Implausible string length $length")
        return ByteArray(length).also { input.readFully(it) }.decodeToString()
    }

    private fun allocate(width: Int, height: Int) {
        this.width = width
        this.height = height
        pixels = IntArray(width * height)
    }

    /** Asks for the whole screen, or only what changed since the last update. */
    fun requestUpdate(incremental: Boolean) {
        synchronized(writeLock) {
            output.writeByte(3)
            output.writeByte(if (incremental) 1 else 0)
            output.writeShort(0); output.writeShort(0)
            output.writeShort(width); output.writeShort(height)
            output.flush()
        }
    }

    /**
     * Reads one server message, decoding it into [pixels] and notifying the observer.
     *
     * Returns false when the read timed out with nothing pending, which is normal: the
     * server sends nothing while the screen is idle.
     */
    fun pumpUpdate(): Boolean {
        val type = try {
            input.readUnsignedByte()
        } catch (e: java.net.SocketTimeoutException) {
            return false
        }

        when (type) {
            MSG_FRAMEBUFFER_UPDATE -> readFramebufferUpdate()
            MSG_SET_COLOUR_MAP -> skipColourMap()
            MSG_BELL -> Unit
            MSG_SERVER_CUT_TEXT -> {
                input.skipBytes(3)
                readString()
            }
            else -> throw IOException("Unknown server message type $type")
        }
        return true
    }

    private fun skipColourMap() {
        input.skipBytes(3)
        val count = input.readUnsignedShort()
        input.skipBytes(count * 6)
    }

    private fun readFramebufferUpdate() {
        input.skipBytes(1)
        val rectangles = input.readUnsignedShort()
        for (i in 0 until rectangles) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                ENCODING_RAW -> readRaw(x, y, w, h)
                ENCODING_COPY_RECT -> readCopyRect(x, y, w, h)
                // Carries the new size in the rectangle header and no pixel data. The
                // server answers the first request after a resize with this alone, so a
                // client that assumes every update brings pixels waits forever.
                ENCODING_EXTENDED_DESKTOP_SIZE -> {
                    val screens = input.readUnsignedByte()
                    input.skipBytes(3 + screens * 16)
                    resized(w, h)
                }
                ENCODING_DESKTOP_SIZE -> resized(w, h)
                else -> throw IOException("Unsupported encoding $encoding")
            }
        }
    }

    private fun resized(width: Int, height: Int) {
        if (width == this.width && height == this.height) return
        Timber.i("[RfbClient]: desktop resized to %dx%d", width, height)
        allocate(width, height)
        observer?.onResize(width, height)
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val stride = w * 4
        if (rowBuffer.size < stride) rowBuffer = ByteArray(stride)

        for (row in 0 until h) {
            input.readFully(rowBuffer, 0, stride)
            var dst = (y + row) * width + x
            var src = 0
            for (column in 0 until w) {
                // Little-endian BGRX from the wire; the alpha byte the server sends is
                // undefined, so opacity is forced rather than copied.
                pixels[dst] = 0xFF shl 24 or
                    ((rowBuffer[src + 2].toInt() and 0xFF) shl 16) or
                    ((rowBuffer[src + 1].toInt() and 0xFF) shl 8) or
                    (rowBuffer[src].toInt() and 0xFF)
                dst++
                src += 4
            }
        }
        observer?.onFramebufferUpdate(x, y, w, h)
    }

    /** Scrolling and window movement, sent as "these pixels are already on screen". */
    private fun readCopyRect(x: Int, y: Int, w: Int, h: Int) {
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()
        if (w <= 0 || h <= 0) return

        // Copy in the direction that does not overwrite the source when they overlap.
        val rows = if (srcY < y) (h - 1) downTo 0 else 0 until h
        for (row in rows) {
            val from = (srcY + row) * width + srcX
            val to = (y + row) * width + x
            System.arraycopy(pixels, from, pixels, to, w)
        }
        observer?.onFramebufferUpdate(x, y, w, h)
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        if (!isConnected) return
        synchronized(writeLock) {
            output.writeByte(5)
            output.writeByte(buttonMask)
            output.writeShort(x.coerceIn(0, width - 1))
            output.writeShort(y.coerceIn(0, height - 1))
            output.flush()
        }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        if (!isConnected) return
        synchronized(writeLock) {
            output.writeByte(4)
            output.writeByte(if (down) 1 else 0)
            output.writeShort(0)
            output.writeInt(keysym)
            output.flush()
        }
    }

    /**
     * Asks the server to resize the desktop. The reply arrives as a rectangle in a later
     * update, so callers should keep pumping rather than wait here.
     */
    fun requestDesktopSize(width: Int, height: Int) {
        if (!isConnected) return
        synchronized(writeLock) {
            output.writeByte(251)
            output.writeByte(0)
            output.writeShort(width)
            output.writeShort(height)
            output.writeByte(1)          // one screen
            output.writeByte(0)
            output.writeInt(0)           // screen id
            output.writeShort(0); output.writeShort(0)
            output.writeShort(width); output.writeShort(height)
            output.writeInt(0)           // flags
            output.flush()
        }
    }

    fun close() {
        isConnected = false
        observer = null
        runCatching { socket.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        /** Long enough that an idle screen is not mistaken for a dead one. */
        private const val READ_TIMEOUT_MS = 2_000
        private const val MAX_STRING = 1 shl 20
        private const val SECURITY_NONE = 1

        private const val MSG_FRAMEBUFFER_UPDATE = 0
        private const val MSG_SET_COLOUR_MAP = 1
        private const val MSG_BELL = 2
        private const val MSG_SERVER_CUT_TEXT = 3

        private const val ENCODING_RAW = 0
        private const val ENCODING_COPY_RECT = 1
        private const val ENCODING_DESKTOP_SIZE = -223
        private const val ENCODING_EXTENDED_DESKTOP_SIZE = -308
    }
}
