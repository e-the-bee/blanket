// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.Minecraft;

/** {@code Client.openScreen/closeScreen/isScreenOpen} on the unobf set (26.1.2 stubs). */
public final class Screens {
    private Screens() {}

    public static void open(Minecraft mc, com.blanket.api.Screen screen) {
        mc.setScreen(new ScreenHost(screen));
    }

    public static void close(Minecraft mc) {
        mc.setScreen(null);
    }

    public static boolean isOpen(Minecraft mc) {
        return mc.screen != null;
    }
}
