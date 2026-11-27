// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.api.ScreenKind;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen;

/**
 * {@code GlueScreen.kind} and {@code isModScreen} on the legacy set (1.8.9 stubs, Legacy Yarn names): the screen object
 * is tested against the era's title, pause, chat, inventory and container screens and the mod screen host
 * (events-and-hooks.md section 4.5). The player inventory is {@code SurvivalInventoryScreen}; the game's abstract
 * {@code InventoryScreen} is only the base of it and of the creative screen. The creative inventory is its own class,
 * so it is listed beside the player inventory; both extend the container screen, which comes last.
 */
public final class ScreenKinds {
    private ScreenKinds() {}

    /** The {@link ScreenKind} ordinal of {@code screen}; {@code NONE} for null. */
    public static int kind(Object screen) {
        if (screen == null) return ScreenKind.NONE.ordinal();
        if (screen instanceof TitleScreen) return ScreenKind.TITLE.ordinal();
        if (screen instanceof GameMenuScreen) return ScreenKind.PAUSE.ordinal();
        if (screen instanceof ChatScreen) return ScreenKind.CHAT.ordinal();
        if (screen instanceof SurvivalInventoryScreen || screen instanceof CreativeInventoryScreen) return ScreenKind.INVENTORY.ordinal();
        if (screen instanceof HandledScreen) return ScreenKind.CONTAINER.ordinal();
        return ScreenKind.OTHER.ordinal();
    }

    public static boolean isModScreen(Object screen) {
        return screen instanceof ScreenHost;
    }
}
