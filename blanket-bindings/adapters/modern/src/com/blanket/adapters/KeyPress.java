// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/** Glue {@code GlueTestkit.press} on the modern set (1.20.1 and 1.21.1): the keyboard handler's click for the mapping's bound key. */
public final class KeyPress {
    private KeyPress() {}

    public static void press(Object mapping) {
        KeyMapping.click(InputConstants.getKey(((KeyMapping) mapping).saveString()));
    }
}
