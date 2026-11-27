// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.Minecraft;

/** {@code Client.guiWidth/guiHeight/guiScale} on the modern set. */
public final class ClientInfo {
    private ClientInfo() {}

    public static int guiWidth(Minecraft mc) {
        return mc.getWindow().getGuiScaledWidth();
    }

    public static int guiHeight(Minecraft mc) {
        return mc.getWindow().getGuiScaledHeight();
    }

    public static double guiScale(Minecraft mc) {
        return mc.getWindow().getGuiScale();
    }
}
