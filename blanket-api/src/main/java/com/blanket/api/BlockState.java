// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** A block state. Aliased to the game class per target; this body exists only for compilation. */
public final class BlockState {
    private BlockState() {}

    /** {@code "minecraft:stone"}. */
    public String blockId() { throw new AssertionError("blanket stub"); }

    public boolean isAir() { throw new AssertionError("blanket stub"); }

    /** Null when the property is absent ({@code "north"}, {@code "true"}, {@code "3"}). */
    public String propertyValue(String name) { throw new AssertionError("blanket stub"); }
}
