// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

import com.blanket.api.render.Canvas2D;

/**
 * A screen the mod extends. Owned; the generated host (a subclass of the game's screen class) forwards the game's
 * calls to the methods below.
 */
@Owned
public abstract class Screen {
    private final String title;
    private int width;
    private int height;
    private Runnable closer;

    protected Screen(String title) { this.title = title; }

    /** Called on open and on resize. */
    protected void init() {}

    public abstract void render(Canvas2D canvas, int mouseX, int mouseY, float partialTick);

    public boolean mouseClicked(double x, double y, int button) { return false; }

    public boolean mouseReleased(double x, double y, int button) { return false; }

    public boolean mouseScrolled(double x, double y, double amount) { return false; }

    public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return false; }

    public boolean keyPressed(Key key) { return false; }

    public boolean charTyped(char c) { return false; }

    public void onClose() {}

    public boolean pausesGame() { return true; }

    /** GUI-scaled width. */
    protected final int width() { return width; }

    /** GUI-scaled height. */
    protected final int height() { return height; }

    protected final void close() {
        if (closer != null) closer.run();
    }

    /** Runtime use only: the host sets the GUI size and the close action. */
    public final void attach(int width, int height, Runnable closer) {
        this.width = width;
        this.height = height;
        this.closer = closer;
    }

    /** Runtime use only. */
    public final String title() { return title; }

    /** Runtime use only: lets the host call the protected {@link #init()}. */
    public final void runInit() { init(); }
}
