package com.winlator.xconnector;

import androidx.annotation.Keep;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

public class ClientSocket {
    public final int fd;
    private final ArrayDeque<Integer> ancillaryFds = new ArrayDeque<>();

    static {
        System.loadLibrary("winlator");
    }

    public ClientSocket(int fd) {
        this.fd = fd;
    }

    public boolean hasAncillaryFds() {
        return !ancillaryFds.isEmpty();
    }

    public int getAncillaryFd() {
        return hasAncillaryFds() ? ancillaryFds.poll() : -1;
    }

    @Keep
    public void addAncillaryFd(int ancillaryFd) {
        ancillaryFds.add(ancillaryFd);
    }

    public int read(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = read(fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to read data.");
    }

    public void write(ByteBuffer data) throws IOException {
        int bytesWritten = write(fd, data, data.limit());
        if (bytesWritten >= 0) {
            data.position(bytesWritten);
        }
        else throw new IOException("Failed to write data.");
    }

    /**
     * Attempts a non-blocking write via MSG_DONTWAIT without changing fd flags.
     * @return bytes written; {@code 0} if the socket would block
     */
    public int writeDontWait(ByteBuffer data) throws IOException {
        int remaining = data.remaining();
        if (remaining == 0) return 0;
        int bytesWritten = writeDontWait(fd, data, data.position(), remaining);
        if (bytesWritten > 0) {
            data.position(data.position() + bytesWritten);
            return bytesWritten;
        }
        if (bytesWritten == 0) {
            return 0;
        }
        throw new IOException("Failed to write data (dontwait).");
    }

    /** Blocking write of {@code data.remaining()} bytes starting at {@code data.position()}. */
    public void writeRemaining(ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            int bytesWritten = writeAt(fd, data, data.position(), data.remaining());
            if (bytesWritten > 0) {
                data.position(data.position() + bytesWritten);
            } else {
                throw new IOException("Failed to write remaining data.");
            }
        }
    }

    public int recvAncillaryMsg(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = recvAncillaryMsg(fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to receive ancillary messages.");
    }

    public void sendAncillaryMsg(ByteBuffer data, int ancillaryFd) throws IOException {
        int bytesSent = sendAncillaryMsg(fd, data, data.limit(), ancillaryFd);
        if (bytesSent >= 0) {
            data.position(bytesSent);
        }
        else throw new IOException("Failed to send ancillary messages.");
    }

    private native int read(int fd, ByteBuffer data, int offset, int length);

    private native int write(int fd, ByteBuffer data, int length);

    private native int writeAt(int fd, ByteBuffer data, int offset, int length);

    private native int writeDontWait(int fd, ByteBuffer data, int offset, int length);

    private native int recvAncillaryMsg(int clientFd, ByteBuffer data, int offset, int length);

    private native int sendAncillaryMsg(int clientFd, ByteBuffer data, int length, int ancillaryFd);
}
