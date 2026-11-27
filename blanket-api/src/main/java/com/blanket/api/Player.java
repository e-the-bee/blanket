// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** The local player. Aliased to the game class per target; this body exists only for compilation. */
public final class Player {
    private Player() {}

    public double x() { throw new AssertionError("blanket stub"); }

    public double y() { throw new AssertionError("blanket stub"); }

    public double z() { throw new AssertionError("blanket stub"); }

    public Vec3 position() { throw new AssertionError("blanket stub"); }

    public float yaw() { throw new AssertionError("blanket stub"); }

    public float pitch() { throw new AssertionError("blanket stub"); }

    public boolean onGround() { throw new AssertionError("blanket stub"); }

    public float health() { throw new AssertionError("blanket stub"); }

    public float maxHealth() { throw new AssertionError("blanket stub"); }

    public int foodLevel() { throw new AssertionError("blanket stub"); }

    public float saturation() { throw new AssertionError("blanket stub"); }

    public ItemStack mainHandItem() { throw new AssertionError("blanket stub"); }

    /** Needs {@link Feature#OFFHAND}. */
    public ItemStack offHandItem() { throw new AssertionError("blanket stub"); }

    public int selectedSlot() { throw new AssertionError("blanket stub"); }

    /** Main inventory slots 0 to 35; out of range throws {@link IndexOutOfBoundsException} on every target. */
    public ItemStack inventoryItem(int slot) { throw new AssertionError("blanket stub"); }

    public Box boundingBox() { throw new AssertionError("blanket stub"); }
}
