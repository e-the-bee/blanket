// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** Aliased to the game's local player class per target; this body exists only for compilation. */
public final class Player {
    private Player() {}

    public double x() { throw new UnsupportedOperationException("compile-time stub"); }
    public double y() { throw new UnsupportedOperationException("compile-time stub"); }
    public double z() { throw new UnsupportedOperationException("compile-time stub"); }
}
