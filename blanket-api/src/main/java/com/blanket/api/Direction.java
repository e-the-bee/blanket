// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/**
 * A block face. Aliased to the game enum per target; this body exists only for compilation. The constants are in
 * the game's order (checked per target by C5).
 */
public enum Direction {
    DOWN, UP, NORTH, SOUTH, WEST, EAST;

    public Direction opposite() { throw new AssertionError("blanket stub"); }
}
