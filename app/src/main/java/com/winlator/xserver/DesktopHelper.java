package com.winlator.xserver;

import androidx.collection.ArrayMap;

import java.util.Map;

public abstract class DesktopHelper {
    public static void attachTo(final XServer xServer) {
        setupXResources(xServer);

        xServer.pointer.addOnPointerMotionListener(new Pointer.OnPointerMotionListener() {
            @Override
            public void onPointerButtonPress(Pointer.Button button) {
                updateFocusedWindow(xServer);
            }
        });

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                setFocusedWindow(xServer, window);
            }
        });
    }

    private static void updateFocusedWindow(XServer xServer) {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            Window focusedWindow = xServer.windowManager.getFocusedWindow();
            Window child = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(), xServer.pointer.getClampedY());
            if (child == null && focusedWindow != xServer.windowManager.rootWindow) {
                xServer.windowManager.setFocus(xServer.windowManager.rootWindow, WindowManager.FocusRevertTo.NONE);
            } else if (child != null) {
                Window target = resolveFocusTarget(xServer, child);
                if (target == null || target == focusedWindow) return;
                applyFocus(xServer, target);
            }
        }
    }

    /**
     * Gives X focus to the topmost mapped top-level when nothing holds it (focus reverted to
     * root or None after its window went away). Returns true when focus was moved.
     */
    public static boolean focusTopLevelIfNone(XServer xServer) {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            Window root = xServer.windowManager.rootWindow;
            Window current = xServer.windowManager.getFocusedWindow();
            if (current != null && current != root) return false;
            java.util.List<Window> children = root.getChildren();
            for (int i = children.size() - 1; i >= 0; i--) {
                Window target = resolveFocusTarget(xServer, children.get(i));
                if (target != null && target.attributes.isMapped()) {
                    applyFocus(xServer, target);
                    return true;
                }
            }
            return false;
        }
    }

    private static void setFocusedWindow(XServer xServer, Window window) {
        Window focusTarget = resolveFocusTarget(xServer, window);
        if (focusTarget == null) return;
        applyFocus(xServer, focusTarget);
    }

    private static void applyFocus(XServer xServer, Window focusTarget) {
        Window current = xServer.windowManager.getFocusedWindow();
        // xterm maps the VT before the shell. Focusing the VT first is unavoidable; when the
        // shell later maps, move focus up to it so Xt receives FocusIn on the shell (the VT
        // alone selecting KeyPress is not enough for Xt keyboard focus).
        boolean parentIsRoot = focusTarget.getParent() == xServer.windowManager.rootWindow;
        WindowManager.FocusRevertTo revert = parentIsRoot
                ? WindowManager.FocusRevertTo.POINTER_ROOT
                : WindowManager.FocusRevertTo.PARENT;
        if (current != null && focusTarget.isAncestorOf(current)) {
            xServer.windowManager.setFocus(focusTarget, revert);
            if (xServer.getWinHandler() != null) {
                xServer.getWinHandler().bringToFront(focusTarget.getClassName(), focusTarget.getHandle());
            }
            return;
        }

        xServer.windowManager.setFocus(focusTarget, revert);
        if (xServer.getWinHandler() != null) {
            xServer.getWinHandler().bringToFront(focusTarget.getClassName(), focusTarget.getHandle());
        }
    }

    /**
     * Wine application windows advertise WM_HINTS WINDOW_GROUP == self. Native Linux clients
     * (xterm, GTK) are focused like a real WM: the top-level under the root, not the VT leaf.
     * [InputDeviceManager] then delivers KeyPress to the KeyPress-selecting descendant.
     */
    private static Window resolveFocusTarget(XServer xServer, Window window) {
        if (window == null) return null;

        if (xServer.getWinHandler() != null) {
            return window.isApplicationWindow() ? window : null;
        }

        Window root = xServer.windowManager.rootWindow;
        Window top = window;
        while (top.getParent() != null && top.getParent() != root) {
            top = top.getParent();
        }
        if (top != root && top.isRenderable() && !top.attributes.isOverrideRedirect()) {
            return top;
        }

        Window focusTarget = window;
        while (focusTarget != null && focusTarget != root) {
            if (focusTarget.isRenderable() && !focusTarget.attributes.isOverrideRedirect()) {
                return focusTarget;
            }
            focusTarget = focusTarget.getParent();
        }
        return null;
    }

    private static void setupXResources(XServer xServer) {
        int atom = Atom.getId("RESOURCE_MANAGER");
        int type = Atom.getId("STRING");

        ArrayMap<String, String> values = new ArrayMap<>();
        values.put("size", "20");
        values.put("theme", "dmz");
        values.put("theme_core", "true");

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append("Xcursor")
                    .append('.')
                    .append(entry.getKey())
                    .append(':')
                    .append('\t')
                    .append(entry.getValue())
                    .append('\n');
        }

        byte[] data = sb.toString().getBytes(XServer.LATIN1_CHARSET);
        xServer.windowManager.rootWindow.modifyProperty(atom, type, Property.Format.BYTE_ARRAY, Property.Mode.APPEND, data);
    }
}