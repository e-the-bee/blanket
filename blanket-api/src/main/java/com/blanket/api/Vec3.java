// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** A point or vector in world coordinates. Aliased to the game class per target; this body exists only for compilation. */
public final class Vec3 {
    private Vec3() {}

    public static Vec3 of(double x, double y, double z) { throw new AssertionError("blanket stub"); }

    public double x() { throw new AssertionError("blanket stub"); }

    public double y() { throw new AssertionError("blanket stub"); }

    public double z() { throw new AssertionError("blanket stub"); }

    public Vec3 add(Vec3 other) { throw new AssertionError("blanket stub"); }

    public Vec3 subtract(Vec3 other) { throw new AssertionError("blanket stub"); }

    public Vec3 scale(double factor) { throw new AssertionError("blanket stub"); }

    public double length() { throw new AssertionError("blanket stub"); }

    public double distanceTo(Vec3 other) { throw new AssertionError("blanket stub"); }
}
