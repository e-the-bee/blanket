// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Vec3 and Box members that 1.8.9 has no direct method for: {@code Vec3.scale}, {@code Box.inflate}, {@code Box.center}.
 * The receiver is the first parameter.
 */
public final class Boxes {
    private Boxes() {}

    /** A new vector with every component multiplied by {@code s}. */
    public static Vec3d scale(Vec3d v, double s) {
        return new Vec3d(v.x * s, v.y * s, v.z * s);
    }

    /** The box grown by {@code d} on both sides of all three axes. */
    public static Box inflate(Box b, double d) {
        return b.expand(d, d, d);
    }

    /** The point at the middle of the box. */
    public static Vec3d center(Box b) {
        return new Vec3d((b.minX + b.maxX) * 0.5, (b.minY + b.maxY) * 0.5, (b.minZ + b.maxZ) * 0.5);
    }
}
