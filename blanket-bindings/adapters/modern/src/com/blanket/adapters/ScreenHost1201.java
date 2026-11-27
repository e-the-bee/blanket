// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.runtime.ScreenDraw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * {@link ScreenHost} for 1.20.1, compiled against the 1.20.1 stubs: there {@code mouseScrolled} takes
 * {@code (double, double, double)} (1.21.1: four doubles), so a host compiled against 1.21.1 does not override it.
 */
@Stubs("1.20.1")
public final class ScreenHost1201 extends Screen {
    private final com.blanket.api.Screen screen;

    public ScreenHost1201(com.blanket.api.Screen screen) {
        super(Component.literal(screen.title()));
        this.screen = screen;
    }

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
    public boolean mouseScrolled(double x, double y, double amount) {
        return screen.mouseScrolled(x, y, amount) || super.mouseScrolled(x, y, amount);
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
