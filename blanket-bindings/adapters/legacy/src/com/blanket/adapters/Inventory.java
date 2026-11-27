// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

/** {@code Player.inventoryItem} on the legacy set: slots 0 to 35, else {@link IndexOutOfBoundsException}. */
public final class Inventory {
    private Inventory() {}

    public static ItemStack inventoryItem(ClientPlayerEntity p, int slot) {
        if (slot < 0 || slot > 35) throw new IndexOutOfBoundsException("inventory slot " + slot + " is not in 0..35");
        return Stacks.nonNull(p.inventory.main[slot]);
    }
}
