package com.winlator.xconnector;

import com.winlator.xserver.XServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.ReentrantLock;

public class XOutputStream {
    private static final byte[] ZERO = new byte[64];
    public ByteBuffer buffer;
    public final ClientSocket clientSocket;
    private final ReentrantLock lock = new ReentrantLock();
    private int ancillaryFd = -1;
    private static final double FP3232_SCALE = 4294967296.0;

    public XOutputStream(int initialCapacity) {
        this(null, initialCapacity);
    }

    public XOutputStream(ClientSocket clientSocket, int initialCapacity) {
        this.clientSocket = clientSocket;
        buffer = ByteBuffer.allocateDirect(initialCapacity);
    }

    public void setByteOrder(ByteOrder byteOrder) {
        buffer.order(byteOrder);
    }

    public void setAncillaryFd(int ancillaryFd) {
        this.ancillaryFd = ancillaryFd;
    }

    public void writeByte(byte value) {
        ensureSpaceIsAvailable(1);
        buffer.put(value);
    }

    public void writeShort(short value) {
        ensureSpaceIsAvailable(2);
        buffer.putShort(value);
    }

    public void writeInt(int value) {
        ensureSpaceIsAvailable(4);
        buffer.putInt(value);
    }

    public void writeLong(long value) {
        ensureSpaceIsAvailable(8);
        buffer.putLong(value);
    }

    public void writeFP3232(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("FP3232 value must be finite");
        }

        long fixed = Math.round(value * FP3232_SCALE);

        int integral = (int) (fixed >> 32);
        int frac = (int) fixed;

        // FP3232 is a struct { int32_t integral; uint32_t frac; } in X11.
        writeInt(integral);
        writeInt(frac);
    }

    public void writeFP3232(int integerPart, long fractionalPart) {
        if (fractionalPart < 0L || fractionalPart > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("fractionalPart must be in range 0 .. 0xFFFFFFFF");
        }

        writeInt(integerPart);
        writeInt((int) fractionalPart);
    }

    public void writeString8(String str) {
        byte[] bytes = str.getBytes(XServer.LATIN1_CHARSET);
        int length = -str.length() & 3;
        ensureSpaceIsAvailable(bytes.length + length);
        buffer.put(bytes);
        if (length > 0) writePad(length);
    }

    public void write(byte[] data) {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int offset, int length) {
        ensureSpaceIsAvailable(length);
        buffer.put(data, offset, length);
    }

    public void write(ByteBuffer data) {
        ensureSpaceIsAvailable(data.remaining());
        buffer.put(data);
    }

    public void writePad(int length) {
        write(ZERO, 0, length);
    }

    /** Hex dump of everything sent to a client, for decoding what a client choked on. */
    private static final boolean TRACE_WRITES = false;

    private void flush() throws IOException {
        if (buffer.position() != 0) {
            buffer.flip();

            if (TRACE_WRITES) {
                StringBuilder hex = new StringBuilder();
                for (int i = buffer.position(); i < buffer.limit(); i++) {
                    hex.append(String.format("%02x", buffer.get(i)));
                    if ((i - buffer.position()) % 4 == 3) hex.append(' ');
                }
                android.util.Log.d("XWrite", "fd=" + (clientSocket != null ? clientSocket.fd : -1)
                        + " " + buffer.remaining() + "B " + hex);
            }

            try {
                if (ancillaryFd != -1) {
                    // SCM_RIGHTS must stay intact; never drop.
                    clientSocket.sendAncillaryMsg(buffer, ancillaryFd);
                    ancillaryFd = -1;
                } else if (isDroppableInputEvent(buffer)) {
                    // Keep the fd blocking for protocol. Only input events use
                    // MSG_DONTWAIT so a stalled guest cannot ANR the UI thread.
                    writeInputEventOrDrop(buffer);
                } else {
                    clientSocket.write(buffer);
                }
            } finally {
                buffer.clear();
            }
        }
    }

    /**
     * True only for a single 32-byte core input event. Replies (type 1), errors (0),
     * and multi-message flushes must use blocking write — dropping them hangs wine.
     */
    static boolean isDroppableInputEvent(ByteBuffer data) {
        if (data == null || data.remaining() != 32) return false;
        int type = data.get(data.position()) & 0xff;
        // KeyPress=2 KeyRelease=3 ButtonPress=4 ButtonRelease=5 MotionNotify=6
        return type >= 2 && type <= 6;
    }

    private void writeInputEventOrDrop(ByteBuffer data) throws IOException {
        if (clientSocket == null || !data.hasRemaining()) return;

        int written = clientSocket.writeDontWait(data);
        if (written == 0) {
            android.util.Log.w("XWrite", "dropping " + data.remaining()
                    + "B input event (guest socket full, fd=" + clientSocket.fd + ")");
            data.position(data.limit());
            return;
        }
        // Partial send: finish remaining bytes with blocking write (respects position).
        if (data.hasRemaining()) {
            clientSocket.writeRemaining(data);
        }
    }

    public XStreamLock lock() {
        return new OutputStreamLock();
    }

    private void ensureSpaceIsAvailable(int length) {
        int position = buffer.position();
        if ((buffer.capacity() - position) >= length) return;
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() + length).order(buffer.order());
        buffer.rewind();
        newBuffer.put(buffer).position(position);
        buffer = newBuffer;
    }

    public void writeSuccessReply(int sequenceNumber, int replyLength) throws IOException {
        try (XStreamLock lock = lock()) {
            writeByte((byte) 1);
            writeByte((byte) 0);
            writeShort((short) sequenceNumber);
            writeInt(replyLength);
            writePad(24);
        }
    }

    private class OutputStreamLock implements XStreamLock {
        public OutputStreamLock() {
            lock.lock();
        }

        @Override
        public void close() throws IOException {
            try {
                flush();
            }
            finally {
                lock.unlock();
            }
        }
    }
}
