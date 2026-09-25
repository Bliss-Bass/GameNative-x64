package com.winlator.xserver;

public class ScreenInfo {
    /**
     * Mutable so Linux freeform sessions can grow/shrink the X screen to match the
     * Android window (reflow) instead of only scaling the presenter.
     */
    public short width;
    public short height;
    /**
     * Dots-per-inch advertised to X clients via the Setup millimeters fields.
     * Zero keeps the historical {@code width/10} mm formula (~254dpi) that Wine
     * sessions have always seen; Linux sessions pass {@link app.gamenative.linux.LinuxDisplayScale}.
     */
    public final int dpi;

    public ScreenInfo(String value) {
        String[] parts = value.split("x");
        width = Short.parseShort(parts[0]);
        height = Short.parseShort(parts[1]);
        dpi = 0;
    }

    public ScreenInfo(int width, int height) {
        this(width, height, 0);
    }

    public ScreenInfo(int width, int height, int dpi) {
        this.width = (short)width;
        this.height = (short)height;
        this.dpi = Math.max(0, dpi);
    }

    /** Updates pixel dimensions; millimeters follow via {@link #getWidthInMillimeters()}. */
    public void setSize(int width, int height) {
        this.width = (short)width;
        this.height = (short)height;
    }

    public short getWidthInMillimeters() {
        if (dpi > 0) {
            return (short)Math.max(1, Math.round(width * 25.4f / dpi));
        }
        return (short)(width / 10);
    }

    public short getHeightInMillimeters() {
        if (dpi > 0) {
            return (short)Math.max(1, Math.round(height * 25.4f / dpi));
        }
        return (short)(height / 10);
    }

    @Override
    public String toString() {
        return width+"x"+height;
    }
}
