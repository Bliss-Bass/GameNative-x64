package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Font;
import com.winlator.xserver.GraphicsContext;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadGraphicsContext;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class DrawRequests {
    public enum Format {BITMAP, XY_PIXMAP, Z_PIXMAP}
    private enum CoordinateMode {ORIGIN, PREVIOUS}

    public static void putImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        Format format = Format.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        byte leftPad = inputStream.readByte();
        byte depth = inputStream.readByte();
        inputStream.skip(2);
        int length = client.getRemainingRequestLength();
        ByteBuffer data = inputStream.readByteBuffer(length);

        Drawable drawable =  client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        if (!(graphicsContext.getFunction() == GraphicsContext.Function.COPY || format == Format.Z_PIXMAP)) {
            throw new UnsupportedOperationException("GC Function other than COPY is not supported.");
        }

        if (com.winlator.core.XPathStats.isEnabled()) {
            com.winlator.core.XPathStats.count(drawable.getOnDrawListener() != null
                            ? com.winlator.core.XPathStats.PUT_IMAGE_WINDOW
                            : com.winlator.core.XPathStats.PUT_IMAGE_PIXMAP,
                    width, height, drawable.width, drawable.height);
        }

        boolean splitBand = format == Format.Z_PIXMAP && drawable.getOnDrawListener() != null
                && isSplitBand(width, depth, PUT_IMAGE_HEADER_BYTES + length);
        if (splitBand) {
            int generation = drawable.beginSplitBand(dstX, dstY, width, height);
            scheduleBandFlush(client, drawable, generation);
        }

        switch (format) {
            case BITMAP:
                if (leftPad != 0) throw new UnsupportedOperationException("PutImage.leftPad cannot be != 0.");
                if (depth == 1) {
                    drawable.drawImage((short)0, (short)0, dstX, dstY, width, height, (byte)1, data, width, height);
                }
                else throw new BadMatch();
                break;
            case XY_PIXMAP:
                if (drawable.visual.depth != depth) throw new BadMatch();
                break;
            case Z_PIXMAP:
                if (leftPad == 0) {
                    drawable.drawImage((short)0, (short)0, dstX, dstY, width, height, depth, data, width, height);
                }
                else throw new BadMatch();
                break;
        }

        // The shorter last band (or any unsplit PutImage) completes the image.
        if (!splitBand) drawable.endDeferredDraw();
    }

    private static final int PUT_IMAGE_HEADER_BYTES = 24;
    /** Largest request a client without BIG-REQUESTS may send (65535 four-byte units). */
    private static final int CORE_MAX_REQUEST_BYTES = 65535 * 4;
    /** Flush a held band sequence whose final band is also full-sized (height divides evenly). */
    private static final long BAND_FLUSH_TIMEOUT_MS = 32;
    private static final java.util.concurrent.ScheduledExecutorService bandFlushExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PutImageBandFlush");
                t.setDaemon(true);
                return t;
            });

    /**
     * True when another scanline would not have fit in this request: Xlib only fills a
     * PutImage to the core limit when it is splitting a larger image, so more bands follow.
     */
    private static boolean isSplitBand(int width, int depth, int requestBytes) {
        if (depth != 24 && depth != 32) return false;
        int rowBytes = width * 4;
        return requestBytes <= CORE_MAX_REQUEST_BYTES && requestBytes + rowBytes > CORE_MAX_REQUEST_BYTES;
    }

    private static void scheduleBandFlush(XClient client, Drawable drawable, int generation) {
        final com.winlator.xserver.XServer xServer = client.xServer;
        bandFlushExecutor.schedule(() -> {
            try (com.winlator.xserver.XLock lock = xServer.lock(com.winlator.xserver.XServer.Lockable.DRAWABLE_MANAGER)) {
                if (drawable.isDeferredDrawGeneration(generation)) drawable.endDeferredDraw();
            } catch (Exception e) {
                android.util.Log.w("DrawRequests", "band flush failed", e);
            }
        }, BAND_FLUSH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public static void getImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        Format format = Format.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        inputStream.skip(4);

        if (format != Format.Z_PIXMAP) throw new UnsupportedOperationException("Only Z_PIXMAP is supported.");

        Drawable drawable =  client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        int visualId = client.xServer.pixmapManager.getPixmap(drawableId) == null ? drawable.visual.id : 0;
        ByteBuffer data = drawable.getImage(x, y, width, height);
        int length = data.limit();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(drawable.visual.depth);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((length + 3) / 4);
            outputStream.writeInt(visualId);
            outputStream.writePad(20);
            outputStream.write(data);
            if ((-length & 3) > 0) outputStream.writePad(-length & 3);
        }
    }

    public static void copyArea(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int srcDrawableId = inputStream.readInt();
        int dstDrawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();

        Drawable srcDrawable =  client.xServer.drawableManager.getDrawable(srcDrawableId);
        if (srcDrawable == null) throw new BadDrawable(srcDrawableId);

        Drawable dstDrawable =  client.xServer.drawableManager.getDrawable(dstDrawableId);
        if (dstDrawable == null) throw new BadDrawable(dstDrawableId);

        GraphicsContext graphicsContext =  client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        if (srcDrawable.visual.depth != dstDrawable.visual.depth) throw new BadMatch();

        if (com.winlator.core.XPathStats.isEnabled()) {
            com.winlator.core.XPathStats.count(dstDrawable.getOnDrawListener() != null
                            ? com.winlator.core.XPathStats.COPY_AREA_TO_WINDOW
                            : com.winlator.core.XPathStats.COPY_AREA_TO_PIXMAP,
                    width, height, dstDrawable.width, dstDrawable.height);
        }

        dstDrawable.copyArea(srcX, srcY, dstX, dstY, width, height, srcDrawable, graphicsContext.getFunction());
    }

    public static void polyLine(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        CoordinateMode coordinateMode = CoordinateMode.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);
        int length = client.getRemainingRequestLength();

        short[] points = new short[length / 2];
        int i = 0;
        while (length != 0) {
            points[i++] = inputStream.readShort();
            points[i++] = inputStream.readShort();
            length -= 4;
        }

        if (coordinateMode == CoordinateMode.ORIGIN && graphicsContext.getLineWidth() > 0) {
            drawable.drawLines(graphicsContext.getForeground(), graphicsContext.getLineWidth(), points);
        }
    }

    public static void polyFillRectangle(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);
        int length = client.getRemainingRequestLength();

        while (length != 0) {
            short x = inputStream.readShort();
            short y = inputStream.readShort();
            short width = inputStream.readShort();
            short height = inputStream.readShort();
            drawable.fillRect(x, y, width, height, graphicsContext.getForeground());
            length -= 8;
        }
    }

    public static void imageText8(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int nChars = client.getRequestData() & 0xff;
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        String text = inputStream.readString8(nChars);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        Font font = resolveFont(client, graphicsContext);
        drawable.drawText(x, y, text, graphicsContext.getForeground(), graphicsContext.getBackground(), true, font);
    }

    public static void imageText16(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int nChars = client.getRequestData() & 0xff;
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        String text = readString16(inputStream, nChars);
        int pad = (-(nChars * 2)) & 3;
        if (pad > 0) inputStream.skip(pad);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        Font font = resolveFont(client, graphicsContext);
        drawable.drawText(x, y, text, graphicsContext.getForeground(), graphicsContext.getBackground(), true, font);
    }

    public static void polyText8(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        int remaining = client.getRemainingRequestLength();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        Font font = resolveFont(client, graphicsContext);
        int cursorX = x;
        while (remaining > 0) {
            int n = inputStream.readByte() & 0xff;
            remaining -= 1;
            if (n == 255) {
                // Font shift: unused byte + FONT.
                if (remaining < 5) break;
                inputStream.skip(1);
                int fontId = inputStream.readInt();
                remaining -= 5;
                Font next = client.xServer.fontManager.getFont(fontId);
                if (next != null) {
                    font = next;
                    graphicsContext.setFont(fontId);
                }
                continue;
            }
            // String item: delta then n chars (no per-item pad).
            if (remaining < 1 + n) break;
            int delta = (byte) inputStream.readByte();
            remaining -= 1;
            byte[] chars = new byte[n];
            inputStream.read(chars);
            remaining -= n;
            String text = new String(chars, java.nio.charset.StandardCharsets.ISO_8859_1);
            cursorX += delta;
            drawable.drawText(cursorX, y, text, graphicsContext.getForeground(), graphicsContext.getBackground(), false, font);
            cursorX += font.textWidth(n);
        }
        if (remaining > 0) inputStream.skip(remaining);
    }

    public static void polyText16(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        int remaining = client.getRemainingRequestLength();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        Font font = resolveFont(client, graphicsContext);
        int cursorX = x;
        while (remaining > 0) {
            int n = inputStream.readByte() & 0xff;
            remaining -= 1;
            if (n == 255) {
                if (remaining < 5) break;
                inputStream.skip(1);
                int fontId = inputStream.readInt();
                remaining -= 5;
                Font next = client.xServer.fontManager.getFont(fontId);
                if (next != null) {
                    font = next;
                    graphicsContext.setFont(fontId);
                }
                continue;
            }
            // 16-bit chars: delta + 2*n bytes.
            if (remaining < 1 + n * 2) break;
            int delta = (byte) inputStream.readByte();
            remaining -= 1;
            String text = readString16(inputStream, n);
            remaining -= n * 2;
            cursorX += delta;
            drawable.drawText(cursorX, y, text, graphicsContext.getForeground(), graphicsContext.getBackground(), false, font);
            cursorX += font.textWidth(n);
        }
        if (remaining > 0) inputStream.skip(remaining);
    }

    private static String readString16(XInputStream inputStream, int nChars) {
        char[] chars = new char[nChars];
        for (int i = 0; i < nChars; i++) {
            int hi = inputStream.readByte() & 0xff;
            int lo = inputStream.readByte() & 0xff;
            chars[i] = (char) ((hi << 8) | lo);
        }
        return new String(chars);
    }

    private static Font resolveFont(XClient client, GraphicsContext graphicsContext) {
        Font font = client.xServer.fontManager.getFont(graphicsContext.getFont());
        if (font != null) return font;
        return new Font(0, "fixed", client.xServer.fontManager.getDefaultPixelSize());
    }

    /**
     * TrueColor shortcut: expand each pixel's 8-bit RGB into the 16-bit RGB reply
     * xterm expects when it probes the default colormap.
     */
    public static void queryColors(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.readInt(); // colormap
        int remaining = client.getRemainingRequestLength();
        int n = remaining / 4;
        int[] pixels = new int[n];
        for (int i = 0; i < n; i++) pixels[i] = inputStream.readInt();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(n * 2); // each RGB is 8 bytes = 2 CARD32s
            outputStream.writeShort((short) n);
            outputStream.writePad(22);
            for (int pixel : pixels) {
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                outputStream.writeShort((short) (r * 257));
                outputStream.writeShort((short) (g * 257));
                outputStream.writeShort((short) (b * 257));
                outputStream.writeShort((short) 0);
            }
        }
    }

    /** TrueColor: pack the requested 16-bit RGB into a 24-bit pixel. */
    public static void allocColor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.readInt(); // colormap
        int red = inputStream.readShort() & 0xffff;
        int green = inputStream.readShort() & 0xffff;
        int blue = inputStream.readShort() & 0xffff;
        inputStream.skip(2);
        int pixel = ((red >> 8) << 16) | ((green >> 8) << 8) | (blue >> 8);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            // Spec order: red, green, blue, unused, pixel, pad — not pixel-first.
            outputStream.writeShort((short) red);
            outputStream.writeShort((short) green);
            outputStream.writeShort((short) blue);
            outputStream.writePad(2);
            outputStream.writeInt(pixel);
            outputStream.writePad(12);
        }
    }

    public static void allocNamedColor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.readInt(); // colormap
        int nameLength = inputStream.readShort() & 0xffff;
        inputStream.skip(2);
        String name = inputStream.readString8(nameLength);
        int[] rgb = namedColor(name);
        int pixel = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        int r16 = rgb[0] * 257;
        int g16 = rgb[1] * 257;
        int b16 = rgb[2] * 257;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(pixel);
            outputStream.writeShort((short) r16); // exact
            outputStream.writeShort((short) g16);
            outputStream.writeShort((short) b16);
            outputStream.writeShort((short) r16); // visual
            outputStream.writeShort((short) g16);
            outputStream.writeShort((short) b16);
            outputStream.writePad(8);
        }
    }

    public static void lookupColor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.readInt(); // colormap
        int nameLength = inputStream.readShort() & 0xffff;
        inputStream.skip(2);
        String name = inputStream.readString8(nameLength);
        int[] rgb = namedColor(name);
        int r16 = rgb[0] * 257;
        int g16 = rgb[1] * 257;
        int b16 = rgb[2] * 257;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short) r16);
            outputStream.writeShort((short) g16);
            outputStream.writeShort((short) b16);
            outputStream.writeShort((short) r16);
            outputStream.writeShort((short) g16);
            outputStream.writeShort((short) b16);
            outputStream.writePad(12);
        }
    }

    private static int[] namedColor(String name) {
        if (name == null) return new int[]{0, 0, 0};
        switch (name.trim().toLowerCase(java.util.Locale.US)) {
            case "white": return new int[]{255, 255, 255};
            case "black": return new int[]{0, 0, 0};
            case "red": return new int[]{255, 0, 0};
            case "green": return new int[]{0, 255, 0};
            case "blue": return new int[]{0, 0, 255};
            case "yellow": return new int[]{255, 255, 0};
            case "cyan": return new int[]{0, 255, 255};
            case "magenta": return new int[]{255, 0, 255};
            case "gray":
            case "grey": return new int[]{190, 190, 190};
            default:
                // #RRGGBB or rgb:RR/GG/BB — fall back to white so clients keep going.
                if (name.startsWith("#") && name.length() >= 7) {
                    try {
                        int v = Integer.parseInt(name.substring(1, 7), 16);
                        return new int[]{(v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff};
                    } catch (NumberFormatException ignored) {}
                }
                return new int[]{255, 255, 255};
        }
    }
}