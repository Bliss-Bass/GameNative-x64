package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.renderer.AHBImage;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.NativeTexture;
import com.winlator.renderer.Texture;
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
 * <p>x86_64 prefers GPU zero-copy: import guest ANV dma-bufs as {@link GPUImage}
 * (AHardwareBuffer via {@code createFromHandle}) so Present can sample through
 * {@code nativeUpdateWindowContentAHB} / Native Rendering+ scanout. LINEAR mmap remains
 * the fallback when AHB wrap fails. Winlator private modifiers 1255 / 1274 stay for arm64.
 */
public class DRI3Extension implements Extension {
    public static final byte MAJOR_OPCODE = -102;

    /** {@code DRM_FORMAT_MOD_LINEAR}. */
    private static final long DRM_FORMAT_MOD_LINEAR = 0L;
    /** Intel X-tiling — common ANV default for scanout-capable buffers. */
    private static final long I915_FORMAT_MOD_X_TILED = 0x0100000000000001L;
    private static final long I915_FORMAT_MOD_Y_TILED = 0x0100000000000002L;
    private static final long I915_FORMAT_MOD_Yf_TILED = 0x0100000000000003L;
    private static final long I915_FORMAT_MOD_4_TILED = 0x0100000000000004L;

    /** DRM fourcc matching Mesa X11 WSI depth-24/32 visuals. */
    private static final int DRM_FORMAT_XRGB8888 = 0x34325258; // 'XR24'
    private static final int DRM_FORMAT_ARGB8888 = 0x34325241; // 'AR24'

    /** Highest version we implement (GetSupportedModifiers + PixmapFromBuffers). */
    private static final int SERVER_MAJOR = 1;
    private static final int SERVER_MINOR = 2;

    private static final long[] SUPPORTED_MODIFIERS = new long[] {
        DRM_FORMAT_MOD_LINEAR,
        I915_FORMAT_MOD_X_TILED,
        I915_FORMAT_MOD_Y_TILED,
        I915_FORMAT_MOD_Yf_TILED,
        I915_FORMAT_MOD_4_TILED,
    };

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
     * DRI3 1.2 GetSupportedModifiers. Advertise LINEAR plus Intel tiled modifiers when the
     * opened DRM render node is i915/xe so ANV can pick an efficient layout.
     */
    private void getSupportedModifiers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8); // window + depth + bpp + pad
        long[] mods = modifiersForRenderNode();
        int n = mods.length;
        android.util.Log.i("DRI3", "GetSupportedModifiers -> " + n + " mods");

        // Each list is n * 8 bytes; reply length is in 4-byte units excluding the 32-byte header.
        int extraWords = (n * 8 * 2) / 4;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(extraWords);
            outputStream.writeInt(n); // num_window_modifiers
            outputStream.writeInt(n); // num_screen_modifiers
            outputStream.writePad(16);
            for (long mod : mods) outputStream.writeLong(mod);
            for (long mod : mods) outputStream.writeLong(mod);
        }
    }

    /** LINEAR always until tiled AHB/Vk import is proven; advertising tiled made ANV
     *  pick I915_FORMAT_MOD_4_TILED and Present went black (CreateImage aux mismatch). */
    private static long[] modifiersForRenderNode() {
        return new long[] { DRM_FORMAT_MOD_LINEAR };
    }

    private static boolean isSupportedModifier(long modifier) {
        for (long m : modifiersForRenderNode()) if (m == modifier) return true;
        return false;
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
            return;
        }
        if (modifier == 1274) {
            pixmapFromLinearFd(client, pixmapId, width, height, stride, offset, depth, fd, size);
            return;
        }
        if (!isSupportedModifier(modifier)) {
            XConnectorEpoll.closeFd(fd);
            android.util.Log.w("DRI3", "unsupported modifier=0x" + Long.toHexString(modifier));
            throw new BadValue((int) modifier);
        }

        // Prefer AHB wrap (true GPU zero-copy into VulkanRenderer / scanout).
        int drmFormat = (depth == 32) ? DRM_FORMAT_ARGB8888 : DRM_FORMAT_XRGB8888;
        if (pixmapFromDmaBufAhb(client, pixmapId, width, height, stride, depth, fd, drmFormat, modifier)) {
            return;
        }
        // Vk dma-buf import is LINEAR-only for now: tiled modifiers fail CreateImage with
        // ANV "wrong aux usage" and leave a blank Present path.
        if (modifier == DRM_FORMAT_MOD_LINEAR
                && com.winlator.renderer.VulkanRenderer.isDmaBufImportSupported()
                && pixmapFromDmaBufVk(client, pixmapId, width, height, stride, depth, fd, drmFormat, modifier)) {
            return;
        }
        // Tiled buffers cannot be safely mmap'd as linear — fail rather than show FB garbage.
        if (modifier != DRM_FORMAT_MOD_LINEAR) {
            XConnectorEpoll.closeFd(fd);
            android.util.Log.w("DRI3", "GPU import failed for tiled mod=0x" + Long.toHexString(modifier));
            throw new BadAlloc();
        }
        pixmapFromLinearFd(client, pixmapId, width, height, stride, offset, depth, fd, size);
    }

    /**
     * Wrap dma-buf as GPUImage/AHB. On success the pixmap is GPU-backed and Present uses the
     * AHB path; the original fd is closed (AHB holds its own reference). Scanout-eligible.
     */
    private boolean pixmapFromDmaBufAhb(XClient client, int pixmapId, short width, short height,
                                        int stride, byte depth, int fd, int drmFormat, long modifier)
            throws XRequestError {
        if (Drawable.IS_ASR()) return false;
        GPUImage image = GPUImage.fromDmaBuf(fd, width, height, stride, drmFormat, modifier);
        if (!image.isValid() || !image.hasHardwareBuffer()) {
            if (image.isValid()) image.destroy();
            return false;
        }

        Visual visual = client.xServer.pixmapManager.getVisualForDepth(depth);
        if (visual == null) {
            image.destroy();
            return false;
        }
        Drawable drawable = client.xServer.drawableManager.createDrawable(pixmapId, width, height, visual);
        if (drawable == null) {
            image.destroy();
            return false;
        }
        drawable.setTexture(image);
        drawable.setDirectScanout(image.supportsDirectScanout());
        drawable.setOnDestroyListener((d) -> {
            Texture t = d.getTexture();
            if (t instanceof GPUImage) ((GPUImage) t).destroy();
        });
        client.xServer.pixmapManager.createPixmap(drawable);
        XConnectorEpoll.closeFd(fd);
        android.util.Log.i("DRI3", "pixmapFromDmaBufAhb ok " + width + "x" + height +
                " stride=" + stride + " mod=0x" + Long.toHexString(modifier));
        return true;
    }

    /**
     * Dup dma-buf into a GPUImage for Vulkan {@code VK_EXT_external_memory_dma_buf} import.
     * Compositor zero-copy without AHB; not eligible for SurfaceControl scanout.
     */
    private boolean pixmapFromDmaBufVk(XClient client, int pixmapId, short width, short height,
                                       int stride, byte depth, int fd, int drmFormat, long modifier)
            throws XRequestError {
        if (Drawable.IS_ASR()) return false;
        GPUImage image = GPUImage.fromDmaBufFd(fd, width, height, stride, drmFormat, modifier);
        if (!image.isValid() || !image.hasDmaBufFd()) {
            if (image.isValid()) image.destroy();
            return false;
        }

        Visual visual = client.xServer.pixmapManager.getVisualForDepth(depth);
        if (visual == null) {
            image.destroy();
            return false;
        }
        Drawable drawable = client.xServer.drawableManager.createDrawable(pixmapId, width, height, visual);
        if (drawable == null) {
            image.destroy();
            return false;
        }
        drawable.setTexture(image);
        drawable.setDirectScanout(false);
        drawable.setOnDestroyListener((d) -> {
            Texture t = d.getTexture();
            if (t instanceof GPUImage) ((GPUImage) t).destroy();
        });
        client.xServer.pixmapManager.createPixmap(drawable);
        XConnectorEpoll.closeFd(fd);
        android.util.Log.i("DRI3", "pixmapFromDmaBufVk ok " + width + "x" + height +
                " stride=" + stride + " mod=0x" + Long.toHexString(modifier));
        return true;
    }

    private void pixmapFromHardwareBuffer(XClient client, int pixmapId, short width, short height, byte depth, int fd) throws IOException, XRequestError {
        try {
            NativeTexture image = Drawable.IS_ASR() ? new AHBImage(fd) : new GPUImage(fd);
            Drawable drawable = client.xServer.drawableManager.createDrawable(pixmapId, image.getStride(), height, depth);
            drawable.setTexture(image);
            drawable.setDirectScanout(true);

            client.xServer.pixmapManager.createPixmap(drawable);
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }
    }

    /**
     * Fallback: map a LINEAR dma-buf as shared pixmap storage. Present CPU-copies with
     * DMA_BUF_IOCTL_SYNC and forces opaque alpha (XRGB). Not used for tiled modifiers.
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
        drawable.setForceOpaqueAlpha(true);
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
        android.util.Log.i("DRI3", "pixmapFromLinearFd fallback " + width + "x" + height +
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
