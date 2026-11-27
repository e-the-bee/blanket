// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * {@code ItemStack} members and {@code Player.mainHandItem} on the legacy set. 1.8.9 uses {@code null} for an empty
 * slot; the API never returns null, so empty slots become the per-jar sentinel {@link #EMPTY}. Every member here
 * treats null, the sentinel and a count of 0 or less as empty. The sentinel never reaches Minecraft code.
 */
public final class Stacks {
    private Stacks() {}

    /** The empty stack of this jar: an item-less stack, only ever compared by identity. */
    public static final ItemStack EMPTY = new ItemStack((Item) null, 0, 0);

    public static ItemStack nonNull(ItemStack s) {
        return s == null ? EMPTY : s;
    }

    public static ItemStack mainHand(ClientPlayerEntity p) {
        return nonNull(p.getMainHandStack());
    }

    public static boolean isEmpty(ItemStack s) {
        return s == null || s == EMPTY || s.count <= 0;
    }

    public static int count(ItemStack s) {
        return isEmpty(s) ? 0 : s.count;
    }

    public static String itemId(ItemStack s) {
        if (isEmpty(s)) return "minecraft:air";
        return Item.REGISTRY.getIdentifier(s.getItem()).toString();
    }

    public static String displayName(ItemStack s) {
        if (isEmpty(s)) return "Air";
        return I18n.translate(s.getTranslationKey() + ".name", new Object[0]).trim();
    }
}
