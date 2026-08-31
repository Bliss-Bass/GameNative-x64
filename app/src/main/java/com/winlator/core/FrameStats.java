package com.winlator.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the two rates that bracket the presentation path: how many frames the guest hands us
 * (Present requests) and how many we actually put on the display (renderer frames).
 *
 * A Vulkan or DXVK overlay only reports the first of those, which the copy to the Android surface
 * and then vsync throttle again further along, so a figure from inside the guest cannot say which
 * stage is the limit. Two counters over one interval can: a submit rate above the present rate is
 * the copy costing us frames, and both well under the display's rate means the guest is the limit.
 *
 * Off unless {@link #ENABLED} is edited, since presentPixmap is on the hot path.
 */
public abstract class FrameStats {
    public static final boolean ENABLED = false;

    private static final long REPORT_INTERVAL_NS = 1_000_000_000L;

    private static final AtomicLong presents = new AtomicLong();
    private static final AtomicLong frames = new AtomicLong();
    private static final AtomicLong intervalStartNs = new AtomicLong();

    /** A frame the guest presented, counted before any of our own work on it. */
    public static void countPresent() {
        if (!ENABLED) return;
        presents.incrementAndGet();
        report();
    }

    /** A frame the renderer drew to the surface Android composites. */
    public static void countRenderedFrame() {
        if (!ENABLED) return;
        frames.incrementAndGet();
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
        // Whoever wins this claims the interval; the losers keep counting into the next one.
        if (!intervalStartNs.compareAndSet(start, now)) return;

        long presented = presents.getAndSet(0);
        long rendered = frames.getAndSet(0);
        double seconds = elapsed / 1e9;
        android.util.Log.i("FrameStats", String.format(
                "guest presented %.1f/s, rendered %.1f/s over %.2fs",
                presented / seconds, rendered / seconds, seconds));
    }
}
