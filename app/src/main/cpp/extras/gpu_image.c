#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <jni.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <fcntl.h>
#include <dlfcn.h>

#define LOG_TAG "System.out"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define HAL_PIXEL_FORMAT_BGRA_8888 5

// Function to create an EGL image from a hardware buffer
EGLImageKHR createImageKHR(AHardwareBuffer* hardwareBuffer, int textureId) {
    if (!hardwareBuffer) {
        printf("createImageKHR: Invalid AHardwareBuffer pointer\n");
        return NULL;
    }

    const EGLint attribList[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    AHardwareBuffer_acquire(hardwareBuffer);

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (!clientBuffer) {
        printf("Failed to get native client buffer\n");
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
        printf("Invalid EGLDisplay\n");
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    EGLImageKHR imageKHR = eglCreateImageKHR(eglDisplay, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribList);
    if (!imageKHR) {
        printf("Failed to create EGLImageKHR\n");
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    glBindTexture(GL_TEXTURE_2D, textureId);
    if (glGetError() != GL_NO_ERROR) {
        printf("Failed to bind texture\n");
        eglDestroyImageKHR(eglDisplay, imageKHR);
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, imageKHR);
    if (glGetError() != GL_NO_ERROR) {
        printf("Failed to bind EGLImage to texture\n");
        eglDestroyImageKHR(eglDisplay, imageKHR);
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    glBindTexture(GL_TEXTURE_2D, 0);

    return imageKHR;
}

// Function to create a hardware buffer
AHardwareBuffer* createHardwareBuffer(int width, int height) {
    AHardwareBuffer_Desc buffDesc = {};
    buffDesc.width = width;
    buffDesc.height = height;
    buffDesc.layers = 1;
    buffDesc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    buffDesc.format = HAL_PIXEL_FORMAT_BGRA_8888;

    AHardwareBuffer *hardwareBuffer = NULL;
    if (AHardwareBuffer_allocate(&buffDesc, &hardwareBuffer) != 0) {
        printf("Failed to allocate AHardwareBuffer\n");
        return NULL;
    }

    return hardwareBuffer;
}

/*
 * Wrap a dma-buf fd as an AHardwareBuffer when the platform supports
 * AHardwareBuffer_createFromHandle (not in the NDK headers; resolve at runtime).
 * This is the Android-native path that lets VulkanRenderer reuse importAHBToWinTex
 * and SurfaceControl scanout for guest ANV DRI3 buffers.
 */
typedef struct native_handle {
    int version;
    int numFds;
    int numInts;
    int data[0];
} native_handle_t;

enum {
    AHARDWAREBUFFER_CREATE_FROM_HANDLE_METHOD_REGISTER = 2,
    AHARDWAREBUFFER_CREATE_FROM_HANDLE_METHOD_CLONE = 1,
};

typedef int (*PFN_AHardwareBuffer_createFromHandle)(
    const AHardwareBuffer_Desc* desc,
    const native_handle_t* handle,
    int32_t method,
    AHardwareBuffer** outBuffer);

static PFN_AHardwareBuffer_createFromHandle loadCreateFromHandle(void) {
    static PFN_AHardwareBuffer_createFromHandle fn;
    static int resolved;
    if (resolved) return fn;
    resolved = 1;
    void* lib = dlopen("libnativewindow.so", RTLD_NOW);
    if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) return NULL;
    fn = (PFN_AHardwareBuffer_createFromHandle)dlsym(lib, "AHardwareBuffer_createFromHandle");
    return fn;
}

// fourcc helpers matching drm_fourcc.h
#define GN_FOURCC(a,b,c,d) ((uint32_t)(a) | ((uint32_t)(b)<<8) | ((uint32_t)(c)<<16) | ((uint32_t)(d)<<24))
#define DRM_FORMAT_ARGB8888 GN_FOURCC('A','R','2','4')
#define DRM_FORMAT_XRGB8888 GN_FOURCC('X','R','2','4')
#define DRM_FORMAT_ABGR8888 GN_FOURCC('A','B','2','4')
#define DRM_FORMAT_XBGR8888 GN_FOURCC('X','B','2','4')

static uint32_t ahbFormatForDrm(uint32_t drmFormat) {
    switch (drmFormat) {
        case DRM_FORMAT_ABGR8888:
        case DRM_FORMAT_XBGR8888:
            return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        case DRM_FORMAT_ARGB8888:
        case DRM_FORMAT_XRGB8888:
        default:
            return HAL_PIXEL_FORMAT_BGRA_8888;
    }
}

static AHardwareBuffer* tryCreateFromHandle(
    PFN_AHardwareBuffer_createFromHandle createFromHandle,
    int fd, int width, int height, int strideBytes, uint32_t ahbFormat, uint64_t modifier)
{
    (void)modifier;
    int dupFd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (dupFd < 0) dupFd = dup(fd);
    if (dupFd < 0) return NULL;

    size_t handleBytes = sizeof(native_handle_t) + sizeof(int);
    native_handle_t* handle = (native_handle_t*)calloc(1, handleBytes);
    if (!handle) {
        close(dupFd);
        return NULL;
    }
    handle->version = sizeof(native_handle_t);
    handle->numFds = 1;
    handle->numInts = 0;
    handle->data[0] = dupFd;

    AHardwareBuffer_Desc desc;
    memset(&desc, 0, sizeof(desc));
    desc.width = (uint32_t)width;
    desc.height = (uint32_t)height;
    desc.layers = 1;
    desc.format = ahbFormat;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                 AHARDWAREBUFFER_USAGE_CPU_READ_RARELY;
    desc.stride = (uint32_t)(strideBytes / 4);

    AHardwareBuffer* ahb = NULL;
    // CLONE: implementation dups fds; we keep ownership of dupFd + handle memory.
    int err = createFromHandle(&desc, handle, AHARDWAREBUFFER_CREATE_FROM_HANDLE_METHOD_CLONE, &ahb);
    if (err == 0 && ahb) {
        free(handle);
        close(dupFd);
        return ahb;
    }
    // REGISTER: AHB takes ownership of the handle (and its fds).
    err = createFromHandle(&desc, handle, AHARDWAREBUFFER_CREATE_FROM_HANDLE_METHOD_REGISTER, &ahb);
    if (err == 0 && ahb) {
        return ahb;
    }
    free(handle);
    close(dupFd);
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_hardwareBufferFromDmaBuf(
    JNIEnv *env, jclass obj, jint fd, jint width, jint height, jint strideBytes,
    jint drmFormat, jlong modifier)
{
    (void)env; (void)obj;
    if (fd < 0 || width <= 0 || height <= 0 || strideBytes < width * 4) return 0;

    PFN_AHardwareBuffer_createFromHandle createFromHandle = loadCreateFromHandle();
    if (!createFromHandle) {
        printf("hardwareBufferFromDmaBuf: AHardwareBuffer_createFromHandle unavailable\n");
        return 0;
    }

    uint32_t formats[3];
    int nFormats = 0;
    formats[nFormats++] = ahbFormatForDrm((uint32_t)drmFormat);
    if (formats[0] != HAL_PIXEL_FORMAT_BGRA_8888)
        formats[nFormats++] = HAL_PIXEL_FORMAT_BGRA_8888;
    if (formats[0] != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM)
        formats[nFormats++] = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;

    for (int i = 0; i < nFormats; i++) {
        AHardwareBuffer* ahb = tryCreateFromHandle(
            createFromHandle, fd, width, height, strideBytes, formats[i], (uint64_t)modifier);
        if (ahb) {
            printf("hardwareBufferFromDmaBuf: ok %dx%d stride=%d fmt=0x%x mod=0x%llx ahb=%p\n",
                   width, height, strideBytes / 4, formats[i],
                   (unsigned long long)modifier, (void*)ahb);
            return (jlong)ahb;
        }
    }
    printf("hardwareBufferFromDmaBuf: createFromHandle failed %dx%d mod=0x%llx drm=0x%x\n",
           width, height, (unsigned long long)modifier, drmFormat);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_winlator_renderer_GPUImage_dupDmaBufFd(JNIEnv *env, jclass obj, jint fd) {
    (void)env; (void)obj;
    if (fd < 0) return -1;
    int dupFd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (dupFd < 0) dupFd = dup(fd);
    return dupFd;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_closeDmaBufFd(JNIEnv *env, jclass obj, jint fd) {
    (void)env; (void)obj;
    if (fd >= 0) close(fd);
}

// JNI method to extract a hardware buffer from a socketpair
JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_hardwareBufferFromSocket(JNIEnv *env, jclass obj, jint fd) {
    AHardwareBuffer *ahb;

    uint8_t buf = 1;

    if ((write(fd, &buf, 1)) == -1) {
        printf("Failed to write data to socketpair");
        return 0;
    }

    if ((AHardwareBuffer_recvHandleFromUnixSocket(fd, &ahb)) != 0) {
        printf("Failed to extract hardware buffer from socketpair");
        return 0;
    }

    return (jlong)ahb;
}

// JNI method to create a hardware buffer
JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_createHardwareBuffer(JNIEnv *env, jclass obj, jshort width, jshort height) {
    AHardwareBuffer *buffer = createHardwareBuffer(width, height);
    if (!buffer) {
        printf("Failed to create hardware buffer\n");
        return 0;
    }
    return (jlong)buffer;
}

// JNI method to create an EGL image
JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_createImageKHR(JNIEnv *env, jclass obj, jlong hardwareBufferPtr, jint textureId) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (!hardwareBuffer) {
        printf("Invalid AHardwareBuffer pointer\n");
        return 0;
    }
    return (jlong)createImageKHR(hardwareBuffer, textureId);
}

// JNI method to destroy a hardware buffer
JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_destroyHardwareBuffer(JNIEnv *env, jclass obj, jlong hardwareBufferPtr) {
    (void)env; (void)obj;
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (hardwareBuffer) {
        // May not be locked (DRI3 dma-buf imports skip CPU lock); ignore unlock errors.
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        AHardwareBuffer_release(hardwareBuffer);
    }
}

// JNI method to lock a hardware buffer
JNIEXPORT jobject JNICALL
Java_com_winlator_renderer_GPUImage_lockHardwareBuffer(JNIEnv *env, jclass obj, jlong hardwareBufferPtr) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (!hardwareBuffer) {
        printf("Invalid AHardwareBuffer pointer\n");
        return NULL;
    }

    void *virtualAddr;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &virtualAddr) != 0) {
        printf("Failed to lock AHardwareBuffer\n");
        return NULL;
    }

    AHardwareBuffer_Desc buffDesc;
    AHardwareBuffer_describe(hardwareBuffer, &buffDesc);

    jclass cls = (*env)->GetObjectClass(env, obj);
    if (cls == NULL) {
        printf("Failed to get Java class reference\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return NULL;
    }

    jmethodID setStride = (*env)->GetMethodID(env, cls, "setStride", "(S)V");
    if (setStride == NULL) {
        printf("Failed to get setStride method ID\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return NULL;
    }
    (*env)->CallVoidMethod(env, obj, setStride, (jshort)buffDesc.stride);

    jlong size = buffDesc.stride * buffDesc.height * 4;
    jobject buffer = (*env)->NewDirectByteBuffer(env, virtualAddr, size);
    if (buffer == NULL) {
        printf("Failed to create Java ByteBuffer\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
    }

    return buffer;
}

// JNI method to unlock a hardware buffer; returns release fence FD (-1 if none)
JNIEXPORT jint JNICALL
Java_com_winlator_renderer_GPUImage_unlockHardwareBuffer(JNIEnv *env, jclass obj, jlong hardwareBufferPtr) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (hardwareBuffer) {
        int32_t fenceFd = -1;
        if (AHardwareBuffer_unlock(hardwareBuffer, &fenceFd) != 0) {
            return -1;
        }
        return (jint)fenceFd;
    }
    return -1;
}

// JNI method to destroy an EGL image
JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_destroyImageKHR(JNIEnv *env, jclass obj, jlong imageKHRPtr) {
    EGLImageKHR imageKHR = (EGLImageKHR)imageKHRPtr;
    if (imageKHR) {
        EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglDestroyImageKHR(eglDisplay, imageKHR);
    }
}
