// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.runtime.ScreenDraw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The game screen that holds a mod's {@link com.blanket.api.Screen} (lowering-and-adapters.md section 5.3), compiled
 * against the 1.21.1 stubs. Each {@code @Override} is proved by javac on 1.21.1; {@code remapAdapters} lists the
 * overriding declarations in {@code adapter-refs.json} so they are renamed with the methods they override. On 1.20.1
 * {@code mouseScrolled} takes three doubles, so 1.20.1 uses {@link ScreenHost1201}. The mod's screen hears of its
 * end through {@code removed()}, which the game calls whenever the screen is replaced or closed.
 */
public final class ScreenHost extends Screen {
    private final com.blanket.api.Screen screen;

    public ScreenHost(com.blanket.api.Screen screen) {
        super(Component.literal(screen.title()));
        this.screen = screen;
    }

    /** Called by the game on open and on resize, after it set {@code width} and {@code height}. */
    @Override
    protected void init() {
        screen.attach(width, height, this::closeIfOpen);
        screen.runInit();
    }

    private void closeIfOpen() {
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        ScreenDraw.render(screen, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return screen.mouseClicked(x, y, button) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return screen.mouseReleased(x, y, button) || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return screen.mouseDragged(x, y, button, dx, dy) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return screen.mouseScrolled(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        return screen.keyPressed(KeyCodes.key(key)) || super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return screen.charTyped(c) || super.charTyped(c, modifiers);
    }

    @Override
    public void removed() {
        screen.onClose();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return screen.pausesGame();
    }
}
