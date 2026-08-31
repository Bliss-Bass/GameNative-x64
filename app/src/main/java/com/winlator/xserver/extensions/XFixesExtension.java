package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * The regions half of XFIXES, which is what Mesa's X11 WSI needs.
 *
 * It is not optional for the shared-memory present path: Mesa creates a region per swapchain image
 * before it attaches the segment, and does so whether or not the server advertises XFIXES. Since
 * libxcb refuses to send a request for an extension the server says it lacks -- it shuts the
 * connection down with CLOSED_EXT_NOTSUPPORTED instead, without writing a byte or reporting an
 * error -- a missing XFIXES silently kills the connection there, which looks nothing like the cause.
 *
 * Regions are recorded rather than used. They carry the damage a client presents, and this server
 * repaints whole windows, so keeping the rectangles only makes a future partial update possible.
 */
public class XFixesExtension implements Extension {
    public static final byte MAJOR_OPCODE = -107;
    /** Mesa gives up on XFIXES below 2; regions arrived in 2 and this implements nothing later. */
    private static final int SERVER_MAJOR = 2;
    private static final int SERVER_MINOR = 0;

    private final SparseArray<short[]> regions = new SparseArray<>();
    /** Who each region belongs to; guarded by {@link #regions}. */
    private final SparseArray<XClient> owners = new SparseArray<>();

    /**
     * Drop the regions of a client that has gone, since a client killed mid-frame never sends
     * DestroyRegion and its ids will be handed to somebody else.
     */
    public void onClientDisconnected(XClient client) {
        synchronized (regions) {
            for (int i = owners.size() - 1; i >= 0; i--) {
                if (owners.valueAt(i) != client) continue;
                regions.remove(owners.keyAt(i));
                owners.removeAt(i);
            }
        }
    }

    private static abstract class ClientOpcodes {
        private static final int QUERY_VERSION = 0;
        private static final int CREATE_REGION = 5;
        private static final int DESTROY_REGION = 10;
        private static final int SET_REGION = 11;
    }

    @Override
    public String getName() {
        return "XFIXES";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int clientMajor = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        int major = Math.min(clientMajor, SERVER_MAJOR);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(major);
            outputStream.writeInt(major < SERVER_MAJOR ? 0 : SERVER_MINOR);
            outputStream.writePad(16);
        }
    }

    /** Rectangles are x, y, width, height and fill the rest of the request. */
    private short[] readRectangles(XClient client, XInputStream inputStream) {
        int count = client.getRemainingRequestLength() / 8;
        short[] rectangles = new short[count * 4];
        for (int i = 0; i < count * 4; i++) rectangles[i] = inputStream.readShort();
        inputStream.skip(client.getRemainingRequestLength());
        return rectangles;
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.CREATE_REGION: {
                int region = inputStream.readInt();
                short[] created = readRectangles(client, inputStream);
                synchronized (regions) {
                    regions.put(region, created);
                    owners.put(region, client);
                }
                break;
            }
            case ClientOpcodes.SET_REGION: {
                int region = inputStream.readInt();
                short[] rectangles = readRectangles(client, inputStream);
                synchronized (regions) {
                    if (regions.indexOfKey(region) < 0) throw new BadValue(region);
                    regions.put(region, rectangles);
                }
                break;
            }
            case ClientOpcodes.DESTROY_REGION: {
                int region = inputStream.readInt();
                inputStream.skip(client.getRemainingRequestLength());
                synchronized (regions) {
                    regions.remove(region);
                    owners.remove(region);
                }
                break;
            }
            default:
                inputStream.skip(client.getRemainingRequestLength());
                android.util.Log.w("XFIXES", "unimplemented opcode=" + opcode + "; reporting BadImplementation");
                throw new BadImplementation();
        }
    }
}
