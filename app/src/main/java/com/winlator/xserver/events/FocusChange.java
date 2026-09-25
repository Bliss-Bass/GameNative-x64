package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;

import java.io.IOException;

/**
 * X11 focus-change event body shared by FocusIn (9) and FocusOut (10).
 *
 * Wire format: detail, sequence, event window, mode, same-screen, 22 bytes unused.
 */
public abstract class FocusChange extends Event {
    public enum Detail {
        ANCESTOR, VIRTUAL, INFERIOR, NONLINEAR, NONLINEAR_VIRTUAL, POINTER, POINTER_ROOT, NONE
    }

    public enum Mode { NORMAL, GRAB, UNGRAB, WHILE_GRABBED }

    private final Window event;
    private final Detail detail;
    private final Mode mode;

    protected FocusChange(int code, Window event, Detail detail, Mode mode) {
        super(code);
        this.event = event;
        this.detail = detail;
        this.mode = mode;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte((byte) detail.ordinal());
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(event != null ? event.id : 0);
            outputStream.writeByte((byte) mode.ordinal());
            // Single-screen server: same-screen must be true or Xt drops keyboard focus.
            outputStream.writeByte((byte) 1);
            outputStream.writePad(22);
        }
    }
}
