// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** {@code ItemStack.itemId} and {@code displayName} on the unobf set: the registry key of the item, {@code minecraft:air} for an empty stack. */
public final class Stacks {
    private Stacks() {}

    public static String itemId(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    public static String displayName(ItemStack s) {
        return s.getHoverName().getString();
    }
}
