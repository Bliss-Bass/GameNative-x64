package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;

import java.io.IOException;

public class RawEvent extends Event {
    private final byte[] data;

    public RawEvent(byte[] data) {
        super(data[0]);
        this.data = data;
    }

    /**
     * A SendEvent copy: the code gets the "sent" bit, and the sequence field is the receiver's.
     * The sender's bytes carry whatever it left there (usually 0), and a sequence number that
     * goes backwards makes xcb assume a wrap and lose every reply it is still waiting for.
     */
    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte) (data[0] | 0x80));
            outputStream.writeByte(data[1]);
            if ((data[0] & 0x7f) == KEYMAP_NOTIFY) {
                outputStream.write(data, 2, 30);
            } else {
                outputStream.writeShort(sequenceNumber);
                outputStream.write(data, 4, 28);
            }
        }
    }

    private static final int KEYMAP_NOTIFY = 11;
}
