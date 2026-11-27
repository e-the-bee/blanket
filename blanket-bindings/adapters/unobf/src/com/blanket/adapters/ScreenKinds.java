// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.api.ScreenKind;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

/**
 * {@code GlueScreen.kind} and {@code isModScreen} on the unobf set (26.1.2 stubs): the screen object is tested against
 * the title, pause, chat, inventory and container screens and the mod screen host (events-and-hooks.md section 4.5).
 * The creative inventory is its own class beside the player inventory; both extend the container screen, which comes last.
 */
public final class ScreenKinds {
    private ScreenKinds() {}

    /** The {@link ScreenKind} ordinal of {@code screen}; {@code NONE} for null. */
    public static int kind(Object screen) {
        if (screen == null) return ScreenKind.NONE.ordinal();
        if (screen instanceof TitleScreen) return ScreenKind.TITLE.ordinal();
        if (screen instanceof PauseScreen) return ScreenKind.PAUSE.ordinal();
        if (screen instanceof ChatScreen) return ScreenKind.CHAT.ordinal();
        if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) return ScreenKind.INVENTORY.ordinal();
        if (screen instanceof AbstractContainerScreen) return ScreenKind.CONTAINER.ordinal();
        return ScreenKind.OTHER.ordinal();
    }

    public static boolean isModScreen(Object screen) {
        return screen instanceof ScreenHost;
    }
}
