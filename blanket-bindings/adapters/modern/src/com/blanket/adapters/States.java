// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** {@code BlockState.blockId/isAir/propertyValue} on the modern set. */
public final class States {
    private States() {}

    /** The registry id of the state's block, {@code "minecraft:stone"}. */
    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    public static boolean isAir(BlockState state) {
        return state.isAir();
    }

    /** The serialized value of the state's property of that name, or null when the state has no such property. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String propertyValue(BlockState state, String name) {
        for (Property p : state.getProperties()) {
            if (p.getName().equals(name)) {
                return p.getName((Comparable) state.getValue(p));
            }
        }
        return null;
    }
}
