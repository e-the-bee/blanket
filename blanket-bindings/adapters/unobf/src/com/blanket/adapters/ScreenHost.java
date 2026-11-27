// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.runtime.ScreenDraw;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The game screen that holds a mod's {@link com.blanket.api.Screen} (lowering-and-adapters.md section 5.3), compiled
 * against the 26.1.2 stubs. On 26.x the screen's frame is {@code extractRenderState} and mouse and key methods take
 * the input event records. The mod's screen hears of its end through {@code removed()}.
 */
public final class ScreenHost extends Screen {
    private final com.blanket.api.Screen screen;

    public ScreenHost(com.blanket.api.Screen screen) {
        super(Component.literal(screen.title()));
        this.screen = screen;
    }

    @Override
    protected void init() {
        screen.attach(width, height, this::closeIfOpen);
        screen.runInit();
    }

    private void closeIfOpen() {
        if (minecraft.screen == this) minecraft.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        ScreenDraw.render(screen, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return screen.mouseClicked(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return screen.mouseReleased(event.x(), event.y(), event.button()) || super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return screen.mouseDragged(event.x(), event.y(), event.button(), dx, dy) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return screen.mouseScrolled(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return screen.keyPressed(KeyCodes.key(event.key())) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        int c = event.codepoint();
        return (Character.isBmpCodePoint(c) && screen.charTyped((char) c)) || super.charTyped(event);
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
