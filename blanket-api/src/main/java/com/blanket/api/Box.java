// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** An axis-aligned box. Aliased to the game class per target; this body exists only for compilation. */
public final class Box {
    private Box() {}

    public static Box of(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) { throw new AssertionError("blanket stub"); }

    public double minX() { throw new AssertionError("blanket stub"); }

    public double minY() { throw new AssertionError("blanket stub"); }

    public double minZ() { throw new AssertionError("blanket stub"); }

    public double maxX() { throw new AssertionError("blanket stub"); }

    public double maxY() { throw new AssertionError("blanket stub"); }

    public double maxZ() { throw new AssertionError("blanket stub"); }

    public Box inflate(double amount) { throw new AssertionError("blanket stub"); }

    public Vec3 center() { throw new AssertionError("blanket stub"); }
}
