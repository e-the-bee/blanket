// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import com.blanket.api.Screen;

/**
 * Draws a mod's {@link Screen} for the adapters' screen hosts: the screen's {@code render} is recorded into a
 * {@link Recorder} and drawn at once, as {@link Dispatch#hud} does for the HUD. Client thread only.
 */
public final class ScreenDraw {
    private ScreenDraw() {}

    private static final Recorder SCREEN = new Recorder();

    /** Called from the host's render method with the game's mouse position and partial tick. */
    public static void render(Screen screen, int mouseX, int mouseY, float partialTick) {
        SCREEN.clear();
        screen.render(SCREEN, mouseX, mouseY, partialTick);
        Executor.drawHud(SCREEN);
    }
}
