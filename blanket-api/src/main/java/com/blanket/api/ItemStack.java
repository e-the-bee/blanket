// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** An item stack; never null. Aliased to the game class per target; this body exists only for compilation. */
public final class ItemStack {
    private ItemStack() {}

    public boolean isEmpty() { throw new AssertionError("blanket stub"); }

    public int count() { throw new AssertionError("blanket stub"); }

    /** {@code "minecraft:air"} when empty. */
    public String itemId() { throw new AssertionError("blanket stub"); }

    public String displayName() { throw new AssertionError("blanket stub"); }
}
