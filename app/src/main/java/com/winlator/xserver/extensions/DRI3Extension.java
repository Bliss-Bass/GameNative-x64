package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.renderer.AHBImage;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.NativeTexture;
import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.Visual;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadAlloc;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;

import android.system.ErrnoException;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * DRI3 for the in-app X server.
 *
 * <p>On x86_64 the working path today is DRI3 1.2 with <em>linear-only</em> modifiers: Mesa
 * exports a dma-buf we can {@code mmap}, and Present copies those pixels into the compositor
 * texture. That skips Mesa's software-WSI GPU→CPU readback (the expensive half of
 * {@code MESA_VK_WSI_DEBUG=sw}). Winlator's private modifier sentinels (1255 / 1274) stay for
 * the arm64 Vortek / AHB path. Real GPU import of tiled Intel modifiers is still TODO.
 */
public class DRI3Extension implements Extension {
    public static final byte MAJOR_OPCODE = -102;

    /** {@code DRM_FORMAT_MOD_LINEAR} — the only modifier we can mmap correctly today. */
    private static final long DRM_FORMAT_MOD_LINEAR = 0L;

    /** Highest version we implement (GetSupportedModifiers + PixmapFromBuffers). */
    private static final int SERVER_MAJOR = 1;
    private static final int SERVER_MINOR = 2;

    private byte firstEventId = 0;
    private byte firstErrorId = 0;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte OPEN = 1;
        private static final byte PIXMAP_FROM_BUFFER = 2;
        private static final byte FENCE_FROM_FD = 4;
        /** DRI3 1.2 — see xcb-proto dri3.xml. */
        private static final byte GET_SUPPORTED_MODIFIERS = 6;
        private static final byte PIXMAP_FROM_BUFFERS = 7;
    }

    private SyncExtension syncExtension;

    /**
     * Take over a fence the client allocated and shared with us (libxshmfence: one int32 in a page
     * of shared memory). Mesa waits on this before reusing a swapchain image, so a server that
     * ignores the request presents each image once and then the client blocks for good.
     */
    private void fenceFromFd(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        int fenceId = inputStream.readInt();
        boolean initiallyTriggered = inputStream.readByte() == 1;
        inputStream.skip(3);

        int fd = inputStream.getAncillaryFd();
        try {
            if (client.xServer.drawableManager.getDrawable(drawableId) == null) throw new BadDrawable(drawableId);

            if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);
            if (syncExtension == null) throw new BadImplementation();

            // A page is the smallest thing that can be mapped and the fence is a single int32.
            ByteBuffer mapping = SysVSharedMemory.mapSHMSegment(fd, 4096, 0, false);
            if (mapping == null) throw new BadAlloc();

            syncExtension.registerSharedFence(client, fenceId, mapping, initiallyTriggered);
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }
    }

    @Override
    public String getName() {
        return "DRI3";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public int getNumEvents() { return 0; }

    @Override
    public int getNumErrors() { return 0; }

    @Override
    public void setFirstEventId(byte id) { this.firstEventId = id; }

    @Override
    public void setFirstErrorId(byte id) { this.firstErrorId = id; }

    @Override
    public byte getFirstEventId() { return firstEventId; }

    @Override
    public byte getFirstErrorId() { return firstErrorId; }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int clientMajor = inputStream.readInt();
        int clientMinor = inputStream.readInt();
        int major = Math.min(clientMajor, SERVER_MAJOR);
        int minor = (major == SERVER_MAJOR) ? Math.min(clientMinor, SERVER_MINOR) : SERVER_MINOR;
        if (major < 1) {
            major = SERVER_MAJOR;
            minor = SERVER_MINOR;
        }
        android.util.Log.i("DRI3", "QueryVersion client=" + clientMajor + "." + clientMinor +
                " -> server=" + major + "." + minor);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(major);
            outputStream.writeInt(minor);
            outputStream.writePad(16);
        }
    }

    private void open(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        inputStream.skip(4);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        // Mesa's DRI3 1.2 modifier path wants a real DRM device fd from Open. Without it the
        // ICD falls back to PixmapFromBuffer with untiled-unaware mmap — classic FB garbage.
        int renderFd = openRenderNodeFd();
        android.util.Log.i("DRI3", "Open drawable=" + drawableId + " renderFd=" + renderFd);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            if (renderFd >= 0) {
                outputStream.writeByte((byte)1);
                outputStream.setAncillaryFd(renderFd);
            } else {
                outputStream.writeByte((byte)0);
            }
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
        }
        // SCM_RIGHTS duplicates into the client; drop our copy after the reply is flushed.
        if (renderFd >= 0) XConnectorEpoll.closeFd(renderFd);
    }

    private static int openRenderNodeFd() {
        String[] nodes = {
            "/dev/dri/renderD128",
            "/dev/dri/renderD129",
            "/dev/dri/card0",
        };
        for (String path : nodes) {
            try {
                java.io.FileDescriptor javaFd =
                        android.system.Os.open(path, android.system.OsConstants.O_RDWR, 0);
                java.lang.reflect.Field f;
                try {
                    f = java.io.FileDescriptor.class.getDeclaredField("descriptor");
                } catch (NoSuchFieldException e) {
                    f = java.io.FileDescriptor.class.getDeclaredField("fd");
                }
                f.setAccessible(true);
                return f.getInt(javaFd);
            } catch (ErrnoException | ReflectiveOperationException ignored) {
            }
        }
        return -1;
    }

    /**
     * DRI3 1.2 GetSupportedModifiers. Advertise linear only so Mesa allocates CPU-mmapable
     * buffers; tiled Intel modifiers would need a real GPU import path.
     */
    private void getSupportedModifiers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8); // window + depth + bpp + pad
        android.util.Log.i("DRI3", "GetSupportedModifiers -> LINEAR only");

        // One LINEAR in both window and screen lists → 2 * 8 bytes of extra reply data = length 4.
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(4);
            outputStream.writeInt(1); // num_window_modifiers
            outputStream.writeInt(1); // num_screen_modifiers
            outputStream.writePad(16);
            outputStream.writeLong(DRM_FORMAT_MOD_LINEAR);
            outputStream.writeLong(DRM_FORMAT_MOD_LINEAR);
        }
    }

    private void pixmapFromBuffer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        int size = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short stride = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(1);

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);

        // Pre-1.2 has no modifier on the wire. Accepting it and mmap'ing produced the classic
        // tiled-as-linear FB garbage on Intel. Force Mesa through GetSupportedModifiers + LINEAR.
        int fd = inputStream.getAncillaryFd();
        XConnectorEpoll.closeFd(fd);
        android.util.Log.w("DRI3", "rejecting PixmapFromBuffer " + width + "x" + height +
                " (no modifier); client must use DRI3 1.2 PixmapFromBuffers with LINEAR");
        throw new BadMatch();
    }

    private void pixmapFromBuffers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        inputStream.skip(4); // num_buffers + pad
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        int stride = inputStream.readInt();
        int offset = inputStream.readInt();
        inputStream.skip(24); // other plane strides/offsets
        byte depth = inputStream.readByte();
        byte bpp = inputStream.readByte();
        inputStream.skip(2);
        long modifier = inputStream.readLong();

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        Pixmap existing = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (existing != null) throw new BadIdChoice(pixmapId);

        int fd = inputStream.getAncillaryFd();
        long size = (long) stride * (long) height;

        android.util.Log.i("DRI3", "PixmapFromBuffers " + width + "x" + height +
                " stride=" + stride + " offset=" + offset + " depth=" + depth +
                " bpp=" + bpp + " mod=0x" + Long.toHexString(modifier) + " fd=" + fd);

        if (modifier == 1255) {
            pixmapFromHardwareBuffer(client, pixmapId, width, height, depth, fd);
        } else if (modifier == 1274 || modifier == DRM_FORMAT_MOD_LINEAR) {
            pixmapFromLinearFd(client, pixmapId, width, height, stride, offset, depth, fd, size);
        } else {
            XConnectorEpoll.closeFd(fd);
            android.util.Log.w("DRI3", "unsupported modifier=0x" + Long.toHexString(modifier));
            throw new BadValue((int) modifier);
        }
    }

    private void pixmapFromHardwareBuffer(XClient client, int pixmapId, short width, short height, byte depth, int fd) throws IOException, XRequestError {
        try {
            NativeTexture image = Drawable.IS_ASR() ? new AHBImage(fd) : new GPUImage(fd);
            Drawable drawable = client.xServer.drawableManager.createDrawable(pixmapId, image.getStride(), height, depth);
            drawable.setTexture(image);

            client.xServer.pixmapManager.createPixmap(drawable);
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }
    }

    /**
     * Map a linear dma-buf (or Winlator sentinel 1274) as shared pixmap storage. Present must copy
     * rather than alias, because the client keeps rendering into the same buffer until IdleNotify.
     * The fd is kept open for DMA_BUF_IOCTL_SYNC around each CPU read.
     */
    private void pixmapFromLinearFd(XClient client, int pixmapId, short width, short height, int stride,
                                    int offset, byte depth, int fd, long size) throws IOException, XRequestError {
        ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, size, offset, true);
        if (buffer == null) {
            XConnectorEpoll.closeFd(fd);
            throw new BadAlloc();
        }

        Visual visual = client.xServer.pixmapManager.getVisualForDepth(depth);
        if (visual == null) {
            SysVSharedMemory.unmapSHMSegment(buffer, buffer.capacity());
            XConnectorEpoll.closeFd(fd);
            throw new BadMatch();
        }
        if (width <= 0 || height <= 0 || stride < width * 4) {
            SysVSharedMemory.unmapSHMSegment(buffer, buffer.capacity());
            XConnectorEpoll.closeFd(fd);
            throw new BadValue(stride);
        }

        Drawable drawable = client.xServer.drawableManager.createSharedDrawable(pixmapId, width, height, visual, buffer);
        if (drawable == null) {
            SysVSharedMemory.unmapSHMSegment(buffer, buffer.capacity());
            XConnectorEpoll.closeFd(fd);
            throw new BadIdChoice(pixmapId);
        }
        drawable.setSharedStrideBytes(stride);
        drawable.setDmaBufFd(fd);
        drawable.setOnDestroyListener((d) -> {
            ByteBuffer data = d.getData();
            if (data != null) SysVSharedMemory.unmapSHMSegment(data, data.capacity());
            int ownedFd = d.getDmaBufFd();
            if (ownedFd >= 0) {
                d.setDmaBufFd(-1);
                XConnectorEpoll.closeFd(ownedFd);
            }
        });
        client.xServer.pixmapManager.createPixmap(drawable);
        android.util.Log.i("DRI3", "pixmapFromLinearFd ok " + width + "x" + height +
                " stride=" + stride + " size=" + size + " fd=" + fd);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.OPEN :
                try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    open(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFER:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffer(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.FENCE_FROM_FD:
                try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    fenceFromFd(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.GET_SUPPORTED_MODIFIERS:
                getSupportedModifiers(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFERS:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffers(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
