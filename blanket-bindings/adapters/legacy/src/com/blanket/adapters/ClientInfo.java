// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

/** {@code Client.guiWidth/guiHeight/guiScale} on the legacy set: 1.8.9 computes the scaled size in a {@code Window} (ScaledResolution) built on demand. */
public final class ClientInfo {
    private ClientInfo() {}

    public static int guiWidth(MinecraftClient mc) {
        return (int) new Window(mc).getScaledWidth();
    }

    public static int guiHeight(MinecraftClient mc) {
        return (int) new Window(mc).getScaledHeight();
    }

    public static double guiScale(MinecraftClient mc) {
        return new Window(mc).getScaleFactor();
    }
}
