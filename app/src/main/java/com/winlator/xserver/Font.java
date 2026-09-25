package com.winlator.xserver;

/**
 * A single core-font resource. Winlator does not ship XLFD files; every OpenFont
 * name resolves to the same monospace metrics sized from the screen DPI so
 * Linux clients (xterm) can lay out and draw without RENDER/Xft.
 */
public class Font extends XResource {
    public final String name;
    public final int pixelSize;
    public final int ascent;
    public final int descent;
    public final int maxWidth;

    public Font(int id, String name, int pixelSize) {
        super(id);
        this.name = name != null ? name : "fixed";
        this.pixelSize = Math.max(8, pixelSize);
        // Rough monospace proportions that match Android Typeface.MONOSPACE.
        this.ascent = Math.max(6, Math.round(this.pixelSize * 0.8f));
        this.descent = Math.max(2, this.pixelSize - this.ascent);
        this.maxWidth = Math.max(4, Math.round(this.pixelSize * 0.6f));
    }

    public int textWidth(int nChars) {
        return maxWidth * Math.max(0, nChars);
    }
}
