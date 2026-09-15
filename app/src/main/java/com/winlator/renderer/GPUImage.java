package com.winlator.renderer;

import androidx.annotation.Keep;
import com.winlator.xserver.Drawable;
import java.nio.ByteBuffer;

public class GPUImage extends NativeTexture {
    private long hardwareBufferPtr;
    private long imageKHRPtr;
    private ByteBuffer virtualData;
    private short stride;
    private static boolean supported = false;

    /** True when this image wraps a guest dma-buf for GPU sampling (no CPU mapping). */
    private boolean gpuOnlyImport = false;
    private int dmaBufFd = -1;
    private int dmaWidth;
    private int dmaHeight;
    private int dmaStrideBytes;
    private int dmaDrmFormat;
    private long dmaModifier;

    static {
        System.loadLibrary("extras");
    }

    public GPUImage(short width, short height) {
        hardwareBufferPtr = createHardwareBuffer(width, height);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    public GPUImage(int socketFd) {
        hardwareBufferPtr = hardwareBufferFromSocket(socketFd);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    /**
     * Import a guest DRI3 dma-buf as an AHardwareBuffer (via platform
     * {@code AHardwareBuffer_createFromHandle}). Returns an empty image when the platform
     * cannot wrap the fd — callers should fall back to mmap or VkImage import.
     */
    public static GPUImage fromDmaBuf(int fd, int width, int height, int strideBytes,
                                      int drmFormat, long modifier) {
        GPUImage image = new GPUImage();
        image.hardwareBufferPtr = hardwareBufferFromDmaBuf(
            fd, width, height, strideBytes, drmFormat, modifier);
        if (image.hardwareBufferPtr != 0) {
            // DRI3 buffers are GPU-produced; skip CPU lock so we never stall the guest.
            image.gpuOnlyImport = true;
            image.stride = (short) Math.max(1, strideBytes / 4);
            image.dmaWidth = width;
            image.dmaHeight = height;
            image.dmaStrideBytes = strideBytes;
            image.dmaDrmFormat = drmFormat;
            image.dmaModifier = modifier;
        }
        return image;
    }

    /**
     * GPU-only image that holds a dup'd dma-buf fd for Vulkan
     * {@code VK_EXT_external_memory_dma_buf} import when AHB wrap is unavailable.
     * Not eligible for SurfaceControl scanout (needs AHB).
     */
    public static GPUImage fromDmaBufFd(int fd, int width, int height, int strideBytes,
                                        int drmFormat, long modifier) {
        GPUImage image = new GPUImage();
        int dup = dupDmaBufFd(fd);
        if (dup < 0) return image;
        image.gpuOnlyImport = true;
        image.dmaBufFd = dup;
        image.dmaWidth = width;
        image.dmaHeight = height;
        image.dmaStrideBytes = strideBytes;
        image.dmaDrmFormat = drmFormat;
        image.dmaModifier = modifier;
        image.stride = (short) Math.max(1, strideBytes / 4);
        return image;
    }

    private GPUImage() {}

    public boolean isValid() {
        return hardwareBufferPtr != 0 || dmaBufFd >= 0;
    }

    public boolean hasHardwareBuffer() {
        return hardwareBufferPtr != 0;
    }

    public boolean hasDmaBufFd() {
        return dmaBufFd >= 0;
    }

    public boolean isGpuOnlyImport() {
        return gpuOnlyImport;
    }

    public int getDmaBufFd() { return dmaBufFd; }
    public int getDmaWidth() { return dmaWidth; }
    public int getDmaHeight() { return dmaHeight; }
    public int getDmaStrideBytes() { return dmaStrideBytes; }
    public int getDmaDrmFormat() { return dmaDrmFormat; }
    public long getDmaModifier() { return dmaModifier; }

    /** True when Native Rendering+ SurfaceControl scanout can consume this buffer. */
    public boolean supportsDirectScanout() {
        return hardwareBufferPtr != 0;
    }

    @Override
    public void allocateTexture(short width, short height, ByteBuffer data) {
        if (isAllocated()) return;
        super.allocateTexture(width, height, null);
        if (hardwareBufferPtr != 0) {
            imageKHRPtr = createImageKHR(hardwareBufferPtr, textureId);
            if (imageKHRPtr == 0) {
                System.err.println("Error: Failed to create EGL image");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        }
    }

    @Override
    public void updateFromDrawable(Drawable drawable) {
        if (!isAllocated()) allocateTexture(drawable.width, drawable.height, null);
        needsUpdate = false;
    }

    public short getStride() {
        return stride;
    }

    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }

    @Override
    public void destroy() {
        if (imageKHRPtr != 0) {
            destroyImageKHR(imageKHRPtr);
            imageKHRPtr = 0;
        }
        if (hardwareBufferPtr != 0) {
            destroyHardwareBuffer(hardwareBufferPtr);
            hardwareBufferPtr = 0;
        }
        if (dmaBufFd >= 0) {
            closeDmaBufFd(dmaBufFd);
            dmaBufFd = -1;
        }
        virtualData = null;
        super.destroy();
    }

    public static boolean isSupported() {
        return supported;
    }

    public static void checkIsSupported() {
        final short size = 8;
        GPUImage gpuImage = new GPUImage(size, size);
        gpuImage.allocateTexture(size, size, null);
        supported = gpuImage.hardwareBufferPtr != 0 && gpuImage.imageKHRPtr != 0 && gpuImage.virtualData != null;
        gpuImage.destroy();
    }

    public long getHardwareBufferPtr() {
        return this.hardwareBufferPtr;
    }

    public void lock() {
        // GPU-only DRI3 imports must never CPU-lock (stalls ANV / breaks scanout).
        if (gpuOnlyImport) return;
        if (hardwareBufferPtr != 0 && virtualData == null) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
        }
    }

    public int unlock() {
        if (gpuOnlyImport) return -1;
        if (hardwareBufferPtr != 0 && virtualData != null) {
            int fenceFd = unlockHardwareBuffer(hardwareBufferPtr);
            virtualData = null;
            return fenceFd;
        }
        return -1;
    }

    private native long hardwareBufferFromSocket(int fd);

    private static native long hardwareBufferFromDmaBuf(
        int fd, int width, int height, int strideBytes, int drmFormat, long modifier);

    private static native int dupDmaBufFd(int fd);

    private static native void closeDmaBufFd(int fd);

    private native long createHardwareBuffer(short width, short height);

    private native void destroyHardwareBuffer(long hardwareBufferPtr);

    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);

    private native int unlockHardwareBuffer(long hardwareBufferPtr);

    private native long createImageKHR(long hardwareBufferPtr, int textureId);

    private native void destroyImageKHR(long imageKHRPtr);
}
