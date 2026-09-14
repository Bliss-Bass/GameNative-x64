package com.winlator.xconnector;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;

public class XOutputStreamDropPolicyTest {
    private static ByteBuffer packet(int type, int size) {
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(0, (byte) type);
        buf.limit(size);
        buf.position(0);
        return buf;
    }

    @Test
    public void motionNotifyIsDroppable() {
        assertTrue(XOutputStream.isDroppableInputEvent(packet(6, 32)));
    }

    @Test
    public void keyAndButtonEventsAreDroppable() {
        assertTrue(XOutputStream.isDroppableInputEvent(packet(2, 32)));
        assertTrue(XOutputStream.isDroppableInputEvent(packet(5, 32)));
    }

    @Test
    public void repliesAndErrorsAreNeverDroppable() {
        assertFalse(XOutputStream.isDroppableInputEvent(packet(1, 32)));
        assertFalse(XOutputStream.isDroppableInputEvent(packet(0, 32)));
    }

    @Test
    public void oversizedFlushIsNeverDroppable() {
        // 168B was dropped during winex11.drv attach when the whole socket was
        // O_NONBLOCK; replies must stay on the blocking write path.
        assertFalse(XOutputStream.isDroppableInputEvent(packet(1, 168)));
        assertFalse(XOutputStream.isDroppableInputEvent(packet(6, 168)));
    }
}
