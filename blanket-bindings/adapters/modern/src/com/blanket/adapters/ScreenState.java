// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.Minecraft;

/**
 * {@code Client.closeScreen/isScreenOpen} on 1.20.1. An adapter class is shipped with everything it references, and
 * {@link Screens} references {@link ScreenHost}, which does not override on 1.20.1 ({@link Screens1201} is its host),
 * so 1.20.1 must not reach {@code Screens}.
 */
public final class ScreenState {
    private ScreenState() {}

    public static void close(Minecraft mc) {
        mc.setScreen(null);
    }

    public static boolean isOpen(Minecraft mc) {
        return mc.screen != null;
    }
}
