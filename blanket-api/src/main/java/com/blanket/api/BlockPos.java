// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** A block position. Aliased to the game class per target; this body exists only for compilation. */
public final class BlockPos {
    private BlockPos() {}

    public static BlockPos of(int x, int y, int z) { throw new AssertionError("blanket stub"); }

    public int x() { throw new AssertionError("blanket stub"); }

    public int y() { throw new AssertionError("blanket stub"); }

    public int z() { throw new AssertionError("blanket stub"); }

    public BlockPos offset(Direction d) { throw new AssertionError("blanket stub"); }

    public BlockPos offset(int dx, int dy, int dz) { throw new AssertionError("blanket stub"); }
}
