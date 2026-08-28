package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.extensions.Extension;

import java.io.IOException;

import timber.log.Timber;

public abstract class ExtensionRequests {
    public static void queryExtension(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        short length = inputStream.readShort();
        inputStream.skip(2);
        String name = inputStream.readString8(length);
        Extension extension = client.xServer.getExtensionByName(name);
        Timber.d("QueryExtension: name=%s present=%b opcode=%d", name, extension != null, extension != null ? extension.getMajorOpcode() : -1);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);

            if (extension != null) {
                outputStream.writeByte((byte)1);
                outputStream.writeByte(extension.getMajorOpcode());
                outputStream.writeByte(extension.getFirstEventId());
                outputStream.writeByte(extension.getFirstErrorId());
                outputStream.writePad(20);
            }
            else {
                outputStream.writeByte((byte)0);
                outputStream.writePad(23);
            }
        }
    }

    public static void listExtensions(XClient client, XOutputStream outputStream) throws IOException {
        int count = client.xServer.extensions.size();

        // Names are sent as STRs (one length byte plus the characters), and the reply length
        // counts the whole list padded up to a 4-byte boundary.
        byte[][] names = new byte[count][];
        int nameBytes = 0;
        for (int i = 0; i < count; i++) {
            names[i] = client.xServer.extensions.valueAt(i).getName().getBytes(XServer.LATIN1_CHARSET);
            nameBytes += 1 + names[i].length;
        }
        int padding = (4 - (nameBytes % 4)) % 4;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)count);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((nameBytes + padding) / 4);
            outputStream.writePad(24);

            // writeString8 pads each string to a 4-byte boundary, which would desynchronize
            // the STR list; the padding belongs at the end of the whole list instead.
            for (int i = 0; i < count; i++) {
                outputStream.writeByte((byte)names[i].length);
                outputStream.write(names[i]);
            }
            outputStream.writePad(padding);
        }
    }
}
