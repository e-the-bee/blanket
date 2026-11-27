// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** The client world. Aliased to the game class per target; this body exists only for compilation. */
public final class World {
    private World() {}

    public BlockState blockState(BlockPos pos) { throw new AssertionError("blanket stub"); }

    public long gameTime() { throw new AssertionError("blanket stub"); }

    /** {@code "minecraft:overworld"} etc. on every target. */
    public String dimension() { throw new AssertionError("blanket stub"); }
}
