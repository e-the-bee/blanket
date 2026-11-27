// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.MinecraftClient;

/** {@code Client.openScreen/closeScreen/isScreenOpen} on the legacy set (1.8.9 stubs, Legacy Yarn names). */
public final class Screens {
    private Screens() {}

    public static void open(MinecraftClient mc, com.blanket.api.Screen screen) {
        mc.setScreen(new ScreenHost(screen));
    }

    public static void close(MinecraftClient mc) {
        mc.setScreen(null);
    }

    public static boolean isOpen(MinecraftClient mc) {
        return mc.currentScreen != null;
    }
}
