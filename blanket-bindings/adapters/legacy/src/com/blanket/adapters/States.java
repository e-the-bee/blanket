// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;

/** {@code BlockState.blockId/isAir/propertyValue} on the legacy set (1.8.9 stubs, Legacy Yarn names). */
public final class States {
    private States() {}

    /** The registry id of the state's block, {@code "minecraft:stone"}. */
    public static String blockId(BlockState state) {
        return Block.REGISTRY.getIdentifier(state.getBlock()).toString();
    }

    /** True for the air block (1.8.9 has one air block, registered as the default of {@code Block.REGISTRY}). */
    public static boolean isAir(BlockState state) {
        return state.getBlock() == net.minecraft.block.Blocks.AIR;
    }

    /**
     * The serialized value of the state's property of that name, or null. Reports properties only: part of some
     * blocks' state lives in metadata that is not a property (api-surface 3.5).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String propertyValue(BlockState state, String name) {
        for (Property p : state.getProperties()) {
            if (p.getName().equals(name)) {
                return p.name(state.get(p));
            }
        }
        return null;
    }
}
