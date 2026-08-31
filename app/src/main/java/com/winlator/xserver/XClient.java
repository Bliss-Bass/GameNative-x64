package com.winlator.xserver;

import androidx.collection.ArrayMap;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.events.Event;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.SyncExtension;
import com.winlator.xserver.extensions.XFixesExtension;
import com.winlator.xserver.extensions.XInput2Extension;

import java.io.IOException;
import java.util.ArrayList;

public class XClient implements XResourceManager.OnResourceLifecycleListener {
    /**
     * Every event pushed to a client, for catching a client that spends its life draining our
     * queue. Events reach a client two ways, here and through {@link EventListener}, so both
     * report or the quiet one hides exactly the flood being looked for.
     */
    static final boolean TRACE_EVENTS = false;

    public final XServer xServer;
    public com.winlator.xconnector.Client connectorClient;
    private boolean authenticated = false;
    public final Integer resourceIDBase;
    private short sequenceNumber = 0;
    private int requestLength;
    private byte requestData;
    private int initialLength;
    private final XInputStream inputStream;
    private final XOutputStream outputStream;
    private final ArrayMap<Window, EventListener> eventListeners = new ArrayMap<>();
    private final ArrayList<XResource> resources = new ArrayList<>();

    public XClient(XServer xServer, XInputStream inputStream, XOutputStream outputStream) {
        this.xServer = xServer;
        this.inputStream = inputStream;
        this.outputStream = outputStream;

        try (XLock lock = xServer.lockAll()) {
            resourceIDBase = xServer.resourceIDs.get();
            xServer.windowManager.addOnResourceLifecycleListener(this);
            xServer.pixmapManager.addOnResourceLifecycleListener(this);
            xServer.graphicsContextManager.addOnResourceLifecycleListener(this);
            xServer.cursorManager.addOnResourceLifecycleListener(this);
        }
    }

    public void registerAsOwnerOfResource(XResource resource) {
        resources.add(resource);
    }

    public void setEventListenerForWindow(Window window, Bitmask eventMask) {
        EventListener eventListener = eventListeners.get(window);
        if (eventListener != null) window.removeEventListener(eventListener);
        if (eventMask.isEmpty()) return;
        eventListener = new EventListener(this, eventMask);
        eventListeners.put(window, eventListener);
        window.addEventListener(eventListener);
    }

    public void sendEvent(Event event) {
        if (TRACE_EVENTS) {
            android.util.Log.d("XEvent", "-> " + event.getClass().getSimpleName());
        }
        try (XStreamLock ignored = outputStream.lock()) {
            event.send(sequenceNumber, outputStream);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isInterestedIn(int eventId, Window window) {
        EventListener eventListener = eventListeners.get(window);
        return eventListener != null && eventListener.isInterestedIn(eventId);
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public void freeResources() {
        try (XLock lock = xServer.lockAll()) {
            while (!resources.isEmpty()) {
                XResource resource = resources.remove(resources.size()-1);
                if (resource instanceof Window) {
                    xServer.windowManager.destroyWindow(resource.id);
                }
                else if (resource instanceof Pixmap) {
                    xServer.pixmapManager.freePixmap(resource.id);
                }
                else if (resource instanceof GraphicsContext) {
                    xServer.graphicsContextManager.freeGraphicsContext(resource.id);
                }
                else if (resource instanceof Cursor) {
                    xServer.cursorManager.freeCursor(resource.id);
                }
            }

            while (!eventListeners.isEmpty()) {
                int i = eventListeners.size()-1;
                eventListeners.keyAt(i).removeEventListener(eventListeners.removeAt(i));
            }

            xServer.windowManager.removeOnResourceLifecycleListener(this);
            xServer.pixmapManager.removeOnResourceLifecycleListener(this);
            xServer.graphicsContextManager.removeOnResourceLifecycleListener(this);
            xServer.cursorManager.removeOnResourceLifecycleListener(this);
            xServer.resourceIDs.free(resourceIDBase);
        }

        XInput2Extension xi2 = xServer.getExtension(XInput2Extension.MAJOR_OPCODE);
        if (xi2 != null)
            xi2.onClientDisconnected(this);

        // Resource ids are recycled, so anything an extension keeps under a departed client's id
        // becomes a trap for the next client to be handed that id: Present's own SelectInput
        // answers BadMatch when it finds an id already claimed, leaving that client with no
        // completion events at all.
        PresentExtension present = xServer.getExtension(PresentExtension.MAJOR_OPCODE);
        if (present != null)
            present.onClientDisconnected(this);

        // The shared-memory present path leaves a fence and a region per swapchain image, and a
        // guest killed mid-frame sends no Destroy for either.
        SyncExtension sync = xServer.getExtension(SyncExtension.MAJOR_OPCODE);
        if (sync != null)
            sync.onClientDisconnected(this);

        XFixesExtension xfixes = xServer.getExtension(XFixesExtension.MAJOR_OPCODE);
        if (xfixes != null)
            xfixes.onClientDisconnected(this);
    }

    public void generateSequenceNumber() {
        sequenceNumber++;
    }

    public short getSequenceNumber() {
        return sequenceNumber;
    }

    public int getRequestLength() {
        return requestLength;
    }

    public void setRequestLength(int requestLength) {
        this.requestLength = requestLength;
        initialLength = inputStream.available();
    }

    public byte getRequestData() {
        return requestData;
    }

    public void setRequestData(byte requestData) {
        this.requestData = requestData;
    }

    public int getRemainingRequestLength() {
        int actualLength = initialLength - inputStream.available();
        return requestLength - actualLength;
    }

    public void skipRequest() {
        inputStream.skip(getRemainingRequestLength());
    }

    public XInputStream getInputStream() {
        return inputStream;
    }

    public XOutputStream getOutputStream() {
        return outputStream;
    }

    public Bitmask getEventMaskForWindow(Window window) {
        EventListener eventListener = eventListeners.get(window);
        return eventListener != null ? eventListener.eventMask : new Bitmask();
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Window) eventListeners.remove(resource);
        resources.remove(resource);
    }

    public boolean isValidResourceId(int id) {
        return xServer.resourceIDs.isInInterval(id, resourceIDBase);
    }
}
