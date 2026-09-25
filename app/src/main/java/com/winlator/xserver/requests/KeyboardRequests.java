package com.winlator.xserver.requests;

import static com.winlator.xserver.Keyboard.KEYSYMS_PER_KEYCODE;
import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Keyboard;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class KeyboardRequests {
    public static void getKeyboardMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        byte firstKeycode = inputStream.readByte();
        int count = inputStream.readUnsignedByte();
        inputStream.skip(2);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(KEYSYMS_PER_KEYCODE);
            outputStream.writeShort(client.getSequenceNumber());
            // Length is in CARD32s: one group of keysyms-per-keycode per keycode.
            outputStream.writeInt(count * KEYSYMS_PER_KEYCODE);
            outputStream.writePad(24);

            int i = (firstKeycode & 0xff) - Keyboard.MIN_KEYCODE;
            int[] keysyms = client.xServer.keyboard.keysyms;
            while (count != 0) {
                int base = Math.max(0, i) * KEYSYMS_PER_KEYCODE;
                for (int j = 0; j < KEYSYMS_PER_KEYCODE; j++) {
                    int idx = base + j;
                    outputStream.writeInt(idx >= 0 && idx < keysyms.length ? keysyms[idx] : 0);
                }
                count--;
                i++;
            }
        }
    }

    public static void getModifierMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(2);
            outputStream.writePad(24);
            outputStream.writePad(8);
        }
    }
}