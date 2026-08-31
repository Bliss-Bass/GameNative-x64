package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.GraphicsContext;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.Visual;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadAccess;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadGraphicsContext;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadSHMSegment;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MITSHMExtension implements Extension {
    public static final byte MAJOR_OPCODE = -101;
    private byte firstEventId = 0;
    private byte firstErrorId = 0;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte ATTACH = 1;
        private static final byte DETACH = 2;
        private static final byte PUT_IMAGE = 3;
        private static final byte CREATE_PIXMAP = 5;
    }

    /** ZPixmap, the only layout in which a shared pixmap can be handed over here. */
    private static final byte SHARED_PIXMAP_FORMAT = 2;

    @Override
    public String getName() {
        return "MIT-SHM";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public int getNumEvents() { return 1; }

    @Override
    public int getNumErrors() { return 1; }

    @Override
    public void setFirstEventId(byte id) { this.firstEventId = id; }

    @Override
    public void setFirstErrorId(byte id) { this.firstErrorId = id; }

    @Override
    public byte getFirstEventId() { return firstEventId; }

    @Override
    public byte getFirstErrorId() { return firstErrorId; }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            // shared_pixmaps. Mesa's shared-memory present path is built on CreatePixmap rather
            // than PutImage, so saying no here is what sends it off waiting for a Present event
            // that never comes.
            outputStream.writeByte((byte)1);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeByte(SHARED_PIXMAP_FORMAT);
            // A reply is 32 bytes and nothing here pads for us, so without this the client reads the
            // next 15 bytes on the socket as the tail of this one and every reply after it is skewed.
            outputStream.writePad(15);
        }
    }

    private static void attach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int xid = inputStream.readInt();
        int shmid = inputStream.readInt();
        inputStream.skip(4);
        boolean attached = client.xServer.getSHMSegmentManager().attach(xid, shmid);
        android.util.Log.d("MITSHM", "attach seg=" + xid + " shmid=" + shmid + " ok=" + attached);
        // BadAccess is what Xorg reports when it cannot attach the segment, and it is what Mesa's
        // shm probe expects to see before it gives up on MIT-SHM.
        if (!attached) throw new BadAccess();
    }

    private static void detach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int xid = inputStream.readInt();
        android.util.Log.d("MITSHM", "detach seg=" + xid);
        client.xServer.getSHMSegmentManager().detach(xid);
    }

    private static void putImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short totalWidth = inputStream.readShort();
        short totalHeight = inputStream.readShort();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short srcWidth = inputStream.readShort();
        short srcHeight = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(3);
        int shmseg = inputStream.readInt();
        inputStream.skip(4);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        ByteBuffer data = client.xServer.getSHMSegmentManager().getData(shmseg);
        android.util.Log.d("MITSHM", "putImage seg=" + shmseg + " have=" + (data != null) + " " +
                srcWidth + "x" + srcHeight + " depth=" + depth + " into " + drawableId);
        if (data == null) throw new BadSHMSegment(shmseg);

        if (graphicsContext.getFunction() != GraphicsContext.Function.COPY) {
            throw new UnsupportedOperationException("GC Function other than COPY is not supported.");
        }

        drawable.drawImage(srcX, srcY, dstX, dstY, srcWidth, srcHeight, depth, data, totalWidth, totalHeight);

        // Only a blit whose clipped destination covers >=80% of the drawable
        // counts as a presented frame.
        int clippedW = Math.min((int) srcWidth, drawable.width - Math.max(0, (int) dstX));
        int clippedH = Math.min((int) srcHeight, drawable.height - Math.max(0, (int) dstY));
        if (clippedW > 0 && clippedH > 0
                && clippedW * clippedH * 5 >= drawable.width * drawable.height * 4
                && client.connectorClient != null) {
            long delayNs = com.winlator.xserver.ShmFramePacer.framePresented(drawableId);
            if (delayNs > 0) {
                client.connectorClient.getConnector().pauseClientReads(client.connectorClient, delayNs);
            }
        }
    }

    /**
     * A pixmap drawn straight from a client's shared segment. This is how a client that presents
     * through the Present extension gets its frames onto the screen without pushing them down the
     * X socket: the pixmap it presents is the memory it rendered into.
     *
     * There is no stride on the wire because there is no need for one: every depth this server
     * offers pads scanlines to 32 bits, so both ends compute width * 4 for it.
     */
    private static void createPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int drawableId = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(3);
        int shmseg = inputStream.readInt();
        int offset = inputStream.readInt();

        if (!client.isValidResourceId(pixmapId)) throw new BadIdChoice(pixmapId);
        if (client.xServer.drawableManager.getDrawable(drawableId) == null) throw new BadDrawable(drawableId);
        if (width <= 0 || height <= 0) throw new BadValue(width <= 0 ? width : height);

        ByteBuffer segment = client.xServer.getSHMSegmentManager().getData(shmseg);
        if (segment == null) throw new BadSHMSegment(shmseg);

        Visual visual = client.xServer.pixmapManager.getVisualForDepth(depth);
        if (visual == null) throw new BadMatch();

        long size = (long)width * (long)height * 4L;
        if (offset < 0 || offset + size > segment.capacity()) throw new BadValue(offset);

        ByteBuffer pixels = segment.duplicate();
        pixels.position(offset);
        pixels.limit(offset + (int)size);
        pixels = pixels.slice().order(ByteOrder.LITTLE_ENDIAN);

        Drawable backingStore = client.xServer.drawableManager.createSharedDrawable(pixmapId, width, height, visual, pixels);
        if (backingStore == null) throw new BadIdChoice(pixmapId);
        Pixmap pixmap = client.xServer.pixmapManager.createPixmap(backingStore);
        if (pixmap == null) throw new BadIdChoice(pixmapId);
        client.registerAsOwnerOfResource(pixmap);

        android.util.Log.d("MITSHM", "createPixmap " + pixmapId + " " + width + "x" + height +
                " depth=" + depth + " from seg=" + shmseg + " offset=" + offset);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        android.util.Log.d("MITSHM", "request opcode=" + opcode);
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.ATTACH :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    attach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.DETACH :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    detach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PUT_IMAGE :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                    putImage(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.CREATE_PIXMAP :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    createPixmap(client, inputStream, outputStream);
                }
                break;
            default:
                android.util.Log.w("MITSHM", "unimplemented opcode=" + opcode + "; reporting BadImplementation");
                throw new BadImplementation();
        }
    }
}
