// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/** {@code Player.inventoryItem} on the unobf set: slots 0 to 35, else {@link IndexOutOfBoundsException} (the game's {@code getItem} also reaches armor and off-hand). */
public final class Inventory {
    private Inventory() {}

    public static ItemStack inventoryItem(LocalPlayer p, int slot) {
        if (slot < 0 || slot > 35) throw new IndexOutOfBoundsException("inventory slot " + slot + " is not in 0..35");
        return p.getInventory().getItem(slot);
    }
}
