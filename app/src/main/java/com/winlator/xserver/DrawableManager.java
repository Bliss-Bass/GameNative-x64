package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.core.Callback;
import com.winlator.renderer.Texture;
import com.winlator.renderer.XServerRenderer;

public class DrawableManager extends XResourceManager implements XResourceManager.OnResourceLifecycleListener {
    private final XServer xServer;
    private final SparseArray<Drawable> drawables = new SparseArray<>();

    public DrawableManager(XServer xServer) {
        this.xServer = xServer;
        xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    public Drawable getDrawable(int id) {
        return drawables.get(id);
    }

    public Drawable createDrawable(int id, short width, short height, byte depth) {
        return createDrawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth));
    }

    public Drawable createDrawable(int id, short width, short height, Visual visual) {
        if (id == 0) return new Drawable(id, width, height, visual);
        if (drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual);
        drawables.put(id, drawable);
        return drawable;
    }

    /** @see Drawable#Drawable(int, int, int, Visual, java.nio.ByteBuffer) */
    public Drawable createSharedDrawable(int id, short width, short height, Visual visual, java.nio.ByteBuffer data) {
        if (id == 0 || drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual, data);
        drawables.put(id, drawable);
        return drawable;
    }

    public void removeDrawable(int id) {
        Drawable drawable = drawables.get(id);
        if (drawable == null) return;

        final Texture texture = drawable.getTexture();
        if (texture != null) {
            // Linux Android-X sessions free cursor/icon pixmaps before the renderer view
            // is attached; destroying the texture then NPE'd and became BadImplementation.
            XServerRenderer renderer = xServer.getRenderer();
            if (renderer != null && renderer.getRendererView() != null) {
                renderer.getRendererView().queueEvent(texture::destroy);
            } else {
                texture.destroy();
            }
        }

        Callback<Drawable> onDestroyListener = drawable.getOnDestroyListener();
        if (onDestroyListener != null) onDestroyListener.call(drawable);

        drawable.setOnDrawListener(null);
        drawables.remove(id);
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) removeDrawable(((Pixmap)resource).drawable.id);
    }

    public XServer getXServer() {
        return xServer;
    }

    public Visual getVisual() {
        return xServer.pixmapManager.visual;
    }

    public SparseArray<Drawable> all(){
        return drawables;
    }
}
