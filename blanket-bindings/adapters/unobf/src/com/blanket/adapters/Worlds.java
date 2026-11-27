// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.multiplayer.ClientLevel;

/** {@code World.dimension} on the unobf set. */
public final class Worlds {
    private Worlds() {}

    /** The dimension's resource key as {@code "minecraft:overworld"}. */
    public static String dimension(ClientLevel level) {
        return level.dimension().identifier().toString();
    }
}
