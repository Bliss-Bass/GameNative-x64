package com.winlator.xserver.extensions;

import android.util.SparseBooleanArray;

import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadFence;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

public class SyncExtension implements Extension {
    public static final byte MAJOR_OPCODE = -104;
    private final SparseBooleanArray fences = new SparseBooleanArray();
    /** Mappings for the fences a client shares with us; guarded by {@link #fences}. */
    private final android.util.SparseArray<java.nio.ByteBuffer> sharedFences = new android.util.SparseArray<>();
    /** Who each fence belongs to, so a client that dies mid-frame does not leave them behind. */
    private final android.util.SparseArray<XClient> owners = new android.util.SparseArray<>();
    private byte firstEventId = 0;
    private byte firstErrorId = 0;

    private static abstract class ClientOpcodes {
        private static final byte CREATE_FENCE = 14;
        private static final byte TRIGGER_FENCE = 15;
        private static final byte RESET_FENCE = 16;
        private static final byte DESTROY_FENCE = 17;
        private static final byte AWAIT_FENCE = 19;
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public int getNumEvents() { return 2; }

    @Override
    public int getNumErrors() { return 2; }

    @Override
    public void setFirstEventId(byte id) { this.firstEventId = id; }

    @Override
    public void setFirstErrorId(byte id) { this.firstErrorId = id; }

    @Override
    public byte getFirstEventId() { return firstEventId; }

    @Override
    public byte getFirstErrorId() { return firstErrorId; }

    /**
     * Adopt a fence whose state lives in memory shared with the client, as DRI3 FenceFromFD
     * provides. A client that presents through DRI3 blocks on this memory rather than on any reply,
     * so a fence recorded only here would leave it waiting forever.
     */
    public void registerSharedFence(XClient client, int id, java.nio.ByteBuffer mapping, boolean initiallyTriggered) {
        synchronized (fences) {
            // Fence ids are recycled along with the rest of a departed client's resource ids, so an
            // id arriving again means the old mapping is stale, not that two fences share an id.
            forgetShared(id);

            sharedFences.put(id, mapping);
            owners.put(id, client);
            fences.put(id, initiallyTriggered);
        }
        if (initiallyTriggered) SysVSharedMemory.triggerFence(mapping);
    }

    /** Release a shared fence's mapping. Caller holds {@link #fences}. */
    private void forgetShared(int id) {
        java.nio.ByteBuffer mapping = sharedFences.get(id);
        if (mapping == null) return;
        sharedFences.remove(id);
        owners.remove(id);
        SysVSharedMemory.unmapSHMSegment(mapping, mapping.capacity());
    }

    /**
     * Drop the fences of a client that has gone. A guest killed mid-frame never sends DestroyFence,
     * so without this every swapchain it created leaves a mapping behind for the life of the
     * session, and a recycled fence id would point at memory nobody is waiting on.
     */
    public void onClientDisconnected(XClient client) {
        synchronized (fences) {
            for (int i = owners.size() - 1; i >= 0; i--) {
                if (owners.valueAt(i) != client) continue;
                int id = owners.keyAt(i);
                forgetShared(id);
                fences.delete(id);
            }
        }
    }

    public void setTriggered(int id) {
        synchronized (fences) {
            if (fences.indexOfKey(id) < 0) return;
            fences.put(id, true);
            java.nio.ByteBuffer mapping = sharedFences.get(id);
            if (mapping != null) SysVSharedMemory.triggerFence(mapping);
        }
    }

    private void createFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            inputStream.skip(4);
            int id = inputStream.readInt();

            if (fences.indexOfKey(id) >= 0) throw new BadIdChoice(id);

            boolean initiallyTriggered = inputStream.readByte() == 1;
            inputStream.skip(3);

            fences.put(id, initiallyTriggered);
            owners.put(id, client);
        }
    }

    private void triggerFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int id = inputStream.readInt();
        synchronized (fences) {
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
        }
        setTriggered(id);
    }

    private void resetFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);

            boolean triggered = fences.get(id);
            if (!triggered) throw new BadMatch();

            fences.put(id, false);
        }
    }

    private void destroyFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            fences.delete(id);
            forgetShared(id);
        }
    }

    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int length = client.getRemainingRequestLength();
            int[] ids = new int[length / 4];
            int i = 0;

            while (length != 0) {
                ids[i++] = inputStream.readInt();
                length -= 4;
            }

            boolean anyTriggered = false;
            do {
                for (int id : ids) {
                    if (fences.indexOfKey(id) < 0) throw new BadFence(id);
                    anyTriggered = fences.get(id);
                    if (anyTriggered) break;
                }
            }
            while (!anyTriggered);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.CREATE_FENCE :
                createFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.TRIGGER_FENCE:
                triggerFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.RESET_FENCE:
                resetFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_FENCE:
                destroyFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT_FENCE:
                awaitFence(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}
