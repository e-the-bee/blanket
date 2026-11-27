// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.option.KeyBinding;

/** Glue {@code GlueTestkit.press} on the legacy set: the keyboard handler's click for the mapping's bound key code. */
public final class KeyPress {
    private KeyPress() {}

    public static void press(Object mapping) {
        KeyBinding.onKeyPressed(((KeyBinding) mapping).getCode());
    }
}
