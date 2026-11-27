// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.Minecraft;

/** {@code Client.openScreen} on 1.20.1 ({@link ScreenHost1201}); close and isOpen are {@link Screens}'s. */
@Stubs("1.20.1")
public final class Screens1201 {
    private Screens1201() {}

    public static void open(Minecraft mc, com.blanket.api.Screen screen) {
        mc.setScreen(new ScreenHost1201(screen));
    }
}
