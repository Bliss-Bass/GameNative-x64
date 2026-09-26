package com.winlator.core;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counts which X drawing path carries a client's frames (core PutImage, MIT-SHM PutImage,
 * CopyArea, PresentPixmap) and how many CPU uploads reach the renderer, reported every 2s.
 *
 * Off unless {@code setprop log.tag.XPathStats DEBUG}; the property is re-read every few
 * seconds so it can be toggled on a running session.
 */
public abstract class XPathStats {
    public static final int PUT_IMAGE_WINDOW = 0;
    public static final int PUT_IMAGE_PIXMAP = 1;
    public static final int SHM_PUT_IMAGE_WINDOW = 2;
    public static final int SHM_PUT_IMAGE_PIXMAP = 3;
    public static final int COPY_AREA_TO_WINDOW = 4;
    public static final int COPY_AREA_TO_PIXMAP = 5;
    public static final int PRESENT_PIXMAP = 6;
    public static final int CPU_UPLOAD = 7;
    private static final String[] NAMES = {
            "putImgWin", "putImgPix", "shmPutWin", "shmPutPix",
            "copyToWin", "copyToPix", "present", "cpuUpload"};

    private static final String TAG = "XPathStats";
    private static final long REPORT_INTERVAL_NS = 2_000_000_000L;
    private static final long RECHECK_NS = 3_000_000_000L;

    private static volatile boolean enabled = false;
    private static volatile long lastCheckNs = 0;
    private static final AtomicLongArray counts = new AtomicLongArray(NAMES.length);
    private static final AtomicLongArray pixels = new AtomicLongArray(NAMES.length);
    private static final AtomicLong intervalStartNs = new AtomicLong();
    private static volatile String lastShape = "";

    public static boolean isEnabled() {
        long now = System.nanoTime();
        if (now - lastCheckNs > RECHECK_NS) {
            lastCheckNs = now;
            try {
                enabled = android.util.Log.isLoggable(TAG, android.util.Log.DEBUG);
            } catch (Throwable t) {
                enabled = false;
            }
        }
        return enabled;
    }

    public static void count(int path, int width, int height, int dstW, int dstH) {
        if (!isEnabled()) return;
        counts.incrementAndGet(path);
        pixels.addAndGet(path, (long) width * height);
        if (path != CPU_UPLOAD && path != PRESENT_PIXMAP) {
            lastShape = NAMES[path] + " " + width + "x" + height + " into " + dstW + "x" + dstH;
        }
        report();
    }

    private static void report() {
        long now = System.nanoTime();
        long start = intervalStartNs.get();
        if (start == 0) {
            intervalStartNs.compareAndSet(0, now);
            return;
        }
        long elapsed = now - start;
        if (elapsed < REPORT_INTERVAL_NS) return;
        if (!intervalStartNs.compareAndSet(start, now)) return;

        double seconds = elapsed / 1e9;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            long c = counts.getAndSet(i, 0);
            long px = pixels.getAndSet(i, 0);
            if (c == 0) continue;
            sb.append(NAMES[i]).append('=').append(String.format("%.1f/s", c / seconds))
              .append("(").append(px / Math.max(1, c)).append("px) ");
        }
        sb.append("last=").append(lastShape);
        android.util.Log.i(TAG, sb.toString());
    }
}
