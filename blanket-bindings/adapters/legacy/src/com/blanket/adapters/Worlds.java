// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.world.ClientWorld;

/** {@code World.dimension} on the legacy set: 1.8.9 knows dimensions by number only. */
public final class Worlds {
    private Worlds() {}

    /** {@code "minecraft:overworld"}, {@code "minecraft:the_nether"}, {@code "minecraft:the_end"}; a mod dimension keeps its number. */
    public static String dimension(ClientWorld world) {
        int id = world.dimension.getType();
        switch (id) {
            case 0: return "minecraft:overworld";
            case -1: return "minecraft:the_nether";
            case 1: return "minecraft:the_end";
            default: return "blanket:dimension_" + id;
        }
    }
}
