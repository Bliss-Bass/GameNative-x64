package com.winlator.xserver;

import android.util.SparseArray;

public class FontManager extends XResourceManager {
    private final SparseArray<Font> fonts = new SparseArray<>();
    private final int defaultPixelSize;

    public FontManager(ScreenInfo screenInfo) {
        int dpi = screenInfo.dpi > 0 ? screenInfo.dpi : 96;
        // 11pt at the advertised DPI — matches the old XTerm*faceSize default.
        defaultPixelSize = Math.max(10, Math.round(11f * dpi / 72f));
    }

    public Font getFont(int id) {
        return fonts.get(id);
    }

    public Font createFont(int id, String name) {
        if (fonts.indexOfKey(id) >= 0) return null;
        Font font = new Font(id, name, defaultPixelSize);
        fonts.put(id, font);
        triggerOnCreateResourceListener(font);
        return font;
    }

    public void freeFont(int id) {
        Font font = fonts.get(id);
        if (font == null) return;
        triggerOnFreeResourceListener(font);
        fonts.remove(id);
    }

    public int getDefaultPixelSize() {
        return defaultPixelSize;
    }
}
