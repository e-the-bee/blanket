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
 * {@code GlueScreen.kind} on the modern set (1.20.1 and 1.21.1 have these screen classes under the same names): the screen
 * object is tested against the title, pause, chat, inventory and container screens (events-and-hooks.md section 4.5).
 * The creative inventory is its own class beside the player inventory; both extend the container screen, which comes
 * last. The mod screen host differs between 1.20.1 and 1.21.1, so {@code isModScreen} is in {@link ScreenKindsHost} and
 * {@link ScreenKindsHost1201}; an adapter class is shipped with everything it references, and this one must not reach
 * {@link ScreenHost} on 1.20.1.
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
}
