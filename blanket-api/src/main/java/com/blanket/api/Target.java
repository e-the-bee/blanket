// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** What the crosshair points at. Owned; built by the runtime from the game's hit result. */
@Owned
public final class Target {
    private final TargetKind kind;
    private final BlockPos blockPos;
    private final Direction side;
    private final Vec3 location;

    private Target(TargetKind kind, BlockPos blockPos, Direction side, Vec3 location) {
        this.kind = kind;
        this.blockPos = blockPos;
        this.side = side;
        this.location = location;
    }

    public TargetKind kind() { return kind; }

    /** Null unless {@link TargetKind#BLOCK}. */
    public BlockPos blockPos() { return blockPos; }

    /** Null unless {@link TargetKind#BLOCK}. */
    public Direction side() { return side; }

    public Vec3 location() { return location; }

    /** Runtime use only. */
    public static Target block(BlockPos pos, Direction side, Vec3 location) {
        return new Target(TargetKind.BLOCK, pos, side, location);
    }

    /** Runtime use only. */
    public static Target entity(Vec3 location) { return new Target(TargetKind.ENTITY, null, null, location); }

    /** Runtime use only. */
    public static Target miss(Vec3 location) { return new Target(TargetKind.MISS, null, null, location); }
}
