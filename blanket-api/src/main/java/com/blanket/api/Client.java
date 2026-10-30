// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** Aliased to the game's client class per target; this body exists only for compilation. */
public final class Client {
    private Client() {}

    public static Client get() { throw new UnsupportedOperationException("compile-time stub"); }

    /** Null when no world is loaded. */
    public Player player() { throw new UnsupportedOperationException("compile-time stub"); }
}
