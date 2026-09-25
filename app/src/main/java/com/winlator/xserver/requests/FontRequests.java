package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Font;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadFont;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.charset.Charset;

public abstract class FontRequests {
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    /** Names every OpenFont resolves to the same monospace metrics. */
    private static final String[] ADVERTISED = {
            "fixed",
            "cursor",
            "9x15",
            "7x14",
            "-misc-fixed-medium-r-normal--0-0-0-0-c-0-iso8859-1",
    };

    public static void openFont(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int fontId = inputStream.readInt();
        int length = inputStream.readShort() & 0xffff;
        inputStream.skip(2);
        String name = inputStream.readString8(length);
        if (!client.isValidResourceId(fontId)) throw new BadIdChoice(fontId);
        Font font = client.xServer.fontManager.createFont(fontId, name);
        if (font == null) throw new BadIdChoice(fontId);
        client.registerAsOwnerOfResource(font);
    }

    public static void closeFont(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int fontId = inputStream.readInt();
        if (client.xServer.fontManager.getFont(fontId) == null) throw new BadFont(fontId);
        client.xServer.fontManager.freeFont(fontId);
    }

    public static void queryFont(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int fontId = inputStream.readInt();
        Font font = client.xServer.fontManager.getFont(fontId);
        if (font == null) throw new BadFont(fontId);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            // length = 7 when nFontProps=0 and nCharInfos=0 (52 bytes after the 8-byte header).
            outputStream.writeInt(7);
            writeCharInfo(outputStream, 0, font.maxWidth, font.maxWidth, font.ascent, font.descent);
            outputStream.writeInt(0); // pad
            writeCharInfo(outputStream, 0, font.maxWidth, font.maxWidth, font.ascent, font.descent);
            outputStream.writeInt(0); // pad
            outputStream.writeShort((short) 0); // min-char-or-byte2
            outputStream.writeShort((short) 255); // max-char-or-byte2
            outputStream.writeShort((short) 0); // default-char
            outputStream.writeShort((short) 0); // nFontProps
            outputStream.writeByte((byte) 0); // LeftToRight
            outputStream.writeByte((byte) 0); // min-byte1
            outputStream.writeByte((byte) 0); // max-byte1
            outputStream.writeByte((byte) 1); // all-chars-exist
            outputStream.writeShort((short) font.ascent);
            outputStream.writeShort((short) font.descent);
            outputStream.writeInt(0); // nCharInfos
        }
    }

    public static void queryTextExtents(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        boolean oddLength = client.getRequestData() == 1;
        int fontId = inputStream.readInt();
        Font font = client.xServer.fontManager.getFont(fontId);
        if (font == null) {
            // GC font id is also legal here; fall back to default metrics.
            font = new Font(0, "fixed", client.xServer.fontManager.getDefaultPixelSize());
        }
        int remaining = client.getRemainingRequestLength();
        // STRING16: two bytes per char; oddLength means a pad byte was added for an odd count.
        int nChars = remaining / 2;
        if (oddLength && nChars > 0) nChars -= 1;
        inputStream.skip(remaining);

        int width = font.textWidth(nChars);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0); // draw-direction
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short) font.ascent);
            outputStream.writeShort((short) font.descent);
            outputStream.writeShort((short) font.ascent);
            outputStream.writeShort((short) font.descent);
            outputStream.writeInt(width);
            outputStream.writeInt(0); // left
            outputStream.writeInt(width); // right
            outputStream.writePad(4);
        }
    }

    public static void listFonts(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(2); // max-names
        short patternLength = inputStream.readShort();
        inputStream.readString8(patternLength);

        int namesLength = 0;
        for (String name : ADVERTISED) {
            namesLength += 1 + name.getBytes(LATIN1).length;
        }
        int pad = (-namesLength) & 3;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((namesLength + pad) / 4);
            outputStream.writeShort((short) ADVERTISED.length);
            outputStream.writePad(22);
            for (String name : ADVERTISED) {
                byte[] bytes = name.getBytes(LATIN1);
                outputStream.writeByte((byte) bytes.length);
                outputStream.write(bytes);
            }
            if (pad > 0) outputStream.writePad(pad);
        }
    }

    /**
     * Athena (xmessage) and many core-font clients use ListFontsWithInfo instead of
     * ListFonts+QueryFont. Protocol: one reply per name, then a zero-length terminator.
     */
    public static void listFontsWithInfo(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(2); // max-names
        short patternLength = inputStream.readShort();
        inputStream.readString8(patternLength);

        Font metrics = new Font(0, "fixed", client.xServer.fontManager.getDefaultPixelSize());
        try (XStreamLock lock = outputStream.lock()) {
            for (int i = 0; i < ADVERTISED.length; i++) {
                writeListFontsWithInfoReply(
                        outputStream,
                        client.getSequenceNumber(),
                        metrics,
                        ADVERTISED[i],
                        ADVERTISED.length - i);
            }
            // Terminator: name-length 0, reply length 7 (same as QueryFont with no props/chars).
            writeListFontsWithInfoReply(outputStream, client.getSequenceNumber(), metrics, null, 0);
        }
    }

    private static void writeListFontsWithInfoReply(
            XOutputStream outputStream,
            int sequence,
            Font font,
            String name,
            int remainingIncludingThis) throws IOException {
        byte[] nameBytes = name != null ? name.getBytes(LATIN1) : new byte[0];
        int namePad = (-nameBytes.length) & 3;
        // X11: length = 7+(n+p)/4 beyond the 32-byte minimum (same fixed metrics as QueryFont).
        int length = 7 + (nameBytes.length + namePad) / 4;

        outputStream.writeByte(RESPONSE_CODE_SUCCESS);
        outputStream.writeByte((byte) nameBytes.length);
        outputStream.writeShort((short) sequence);
        outputStream.writeInt(length);
        writeCharInfo(outputStream, 0, font.maxWidth, font.maxWidth, font.ascent, font.descent);
        outputStream.writeInt(0); // pad
        writeCharInfo(outputStream, 0, font.maxWidth, font.maxWidth, font.ascent, font.descent);
        outputStream.writeInt(0); // pad
        outputStream.writeShort((short) 0); // min-char-or-byte2
        outputStream.writeShort((short) 255); // max-char-or-byte2
        outputStream.writeShort((short) 0); // default-char
        outputStream.writeShort((short) 0); // nFontProps
        outputStream.writeByte((byte) 0); // LeftToRight
        outputStream.writeByte((byte) 0); // min-byte1
        outputStream.writeByte((byte) 0); // max-byte1
        outputStream.writeByte((byte) 1); // all-chars-exist
        outputStream.writeShort((short) font.ascent);
        outputStream.writeShort((short) font.descent);
        outputStream.writeInt(remainingIncludingThis);
        if (nameBytes.length > 0) {
            outputStream.write(nameBytes);
            if (namePad > 0) outputStream.writePad(namePad);
        }
    }

    private static void writeCharInfo(XOutputStream out, int left, int right, int width, int ascent, int descent) throws IOException {
        out.writeShort((short) left);
        out.writeShort((short) right);
        out.writeShort((short) width);
        out.writeShort((short) ascent);
        out.writeShort((short) descent);
        out.writeShort((short) 0); // attributes
    }
}
