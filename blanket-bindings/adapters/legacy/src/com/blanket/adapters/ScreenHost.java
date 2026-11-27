// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.runtime.ScreenDraw;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.input.Mouse;

/**
 * The game screen that holds a mod's {@link com.blanket.api.Screen} (lowering-and-adapters.md section 5.3), compiled
 * against the 1.8.9 stubs (Legacy Yarn names). 1.8.9 has no scroll callback and passes key and character in one
 * call: scrolling is read from LWJGL 2's mouse event in {@code handleMouse}, and {@code keyPressed(char, int)} feeds
 * both {@code keyPressed} and {@code charTyped}. Drag deltas are computed from the last mouse position. The screen's
 * title is not shown (1.8.9 screens have none). The mod's screen hears of its end through {@code removed()}.
 */
public final class ScreenHost extends Screen {
    private final com.blanket.api.Screen screen;
    private int lastX;
    private int lastY;

    public ScreenHost(com.blanket.api.Screen screen) {
        this.screen = screen;
    }

    /** Called by the game on open and on resize, after it set {@code width} and {@code height}. */
    @Override
    public void init() {
        screen.attach(width, height, this::closeIfOpen);
        screen.runInit();
    }

    private void closeIfOpen() {
        if (client != null && client.currentScreen == this) client.setScreen(null);
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTick) {
        super.render(mouseX, mouseY, partialTick);
        ScreenDraw.render(screen, mouseX, mouseY, partialTick);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        lastX = x;
        lastY = y;
        if (!screen.mouseClicked(x, y, button)) super.mouseClicked(x, y, button);
    }

    @Override
    protected void mouseReleased(int x, int y, int button) {
        if (!screen.mouseReleased(x, y, button)) super.mouseReleased(x, y, button);
    }

    @Override
    protected void mouseDragged(int x, int y, int button, long sinceClick) {
        int dx = x - lastX;
        int dy = y - lastY;
        lastX = x;
        lastY = y;
        if (!screen.mouseDragged(x, y, button, dx, dy)) super.mouseDragged(x, y, button, sinceClick);
    }

    /** The game's mouse event handler; adds the wheel, in GUI-scaled coordinates with the origin top left. */
    @Override
    public void handleMouse() {
        super.handleMouse();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && client != null) {
            double x = Mouse.getEventX() * (double) width / client.width;
            double y = height - Mouse.getEventY() * (double) height / client.height - 1;
            screen.mouseScrolled(x, y, Integer.signum(wheel));
        }
    }

    @Override
    protected void keyPressed(char c, int code) {
        boolean handled = screen.keyPressed(KeyCodes.key(code));
        if (c != 0 && !Character.isISOControl(c)) handled |= screen.charTyped(c);
        if (!handled) super.keyPressed(c, code);
    }

    @Override
    public void removed() {
        screen.onClose();
        super.removed();
    }

    @Override
    public boolean shouldPauseGame() {
        return screen.pausesGame();
    }

    /**
     * Needed by javac only: in the 1.8.9 stub jar {@code Screen} implements this method of
     * {@code IdentifiableBooleanConsumer} under its obfuscated name {@code a(ZI)V} (LF intermediary has the row only on
     * the interface, so the stub jar names it there alone) and javac sees it unimplemented. Remapped, this declaration
     * overrides the game's {@code Screen} method, which only answers a chat-link confirmation screen opened from this
     * screen; a mod's screen never opens one.
     */
    @Override
    public void confirmResult(boolean result, int id) {}
}
