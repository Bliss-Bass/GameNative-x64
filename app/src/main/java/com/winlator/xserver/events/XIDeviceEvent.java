package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;

import java.io.IOException;

/**
 * XI2 pointer event (button or motion). Electron and GTK act on these and
 * ignore the core ButtonPress when they have selected XI2 on the window.
 */
public class XIDeviceEvent extends Event {
    public static final int GENERIC_EVENT_CODE = 35;

    private final byte extensionOpcode;
    private final int evtype;
    private final int deviceId;
    private final int detail;
    private final int rootId;
    private final int eventId;
    private final int childId;
    private final int rootX;
    private final int rootY;
    private final int eventX;
    private final int eventY;
    private final int buttonBits;

    public XIDeviceEvent(byte extensionOpcode, int evtype, int deviceId, int detail,
                         int rootId, int eventId, int childId,
                         int rootX, int rootY, int eventX, int eventY, int buttonBits) {
        super(GENERIC_EVENT_CODE);
        this.extensionOpcode = extensionOpcode;
        this.evtype = evtype;
        this.deviceId = deviceId;
        this.detail = detail;
        this.rootId = rootId;
        this.eventId = eventId;
        this.childId = childId;
        this.rootX = rootX;
        this.rootY = rootY;
        this.eventX = eventX;
        this.eventY = eventY;
        this.buttonBits = buttonBits;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            // 32-byte generic header, then 48 bytes of device event, then one
            // 4-byte button mask. length is in 4-byte units after the header.
            outputStream.writeByte(this.code);
            outputStream.writeByte(extensionOpcode);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(13);

            outputStream.writeShort((short) evtype);
            outputStream.writeShort((short) deviceId);
            outputStream.writeInt((int) System.currentTimeMillis());
            outputStream.writeInt(detail);
            outputStream.writeInt(rootId);
            outputStream.writeInt(eventId);
            outputStream.writeInt(childId);

            outputStream.writeInt(rootX << 16);
            outputStream.writeInt(rootY << 16);
            outputStream.writeInt(eventX << 16);
            outputStream.writeInt(eventY << 16);
            outputStream.writeShort((short) 1);
            outputStream.writeShort((short) 0);
            outputStream.writeShort((short) deviceId);
            outputStream.writeShort((short) 0);
            outputStream.writeInt(0);
            outputStream.writePad(16);
            outputStream.writeInt(0);
            outputStream.writeInt(buttonBits);
        }
    }
}
