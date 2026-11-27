// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** A key binding. Aliased to the game class per target; this body exists only for compilation. */
public final class KeyBinding {
    private KeyBinding() {}

    /** Only inside {@link ClientMod#init()}; later calls throw {@link IllegalStateException} on every target. */
    public static KeyBinding register(String id, Key defaultKey, String category) { throw new AssertionError("blanket stub"); }

    /** True once per press. */
    public boolean consumePress() { throw new AssertionError("blanket stub"); }

    public boolean isDown() { throw new AssertionError("blanket stub"); }
}
