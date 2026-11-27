// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.glue;

/** What the testkit asks of the game (testing-strategy.md section 4). Glue stubs, bound per target (see {@link GlueFrame}). */
public final class GlueTestkit {
    private GlueTestkit() {}

    /** Presses a key mapping (the game's own object, from {@code KeyQueue.registered()}) once: its next {@code consumeClick} is true. */
    public static void press(Object mapping) { throw GlueFrame.stub("press"); }

    /** Quits the game the way its Quit button does: the main loop ends after the current frame. */
    public static void quit() { throw GlueFrame.stub("quit"); }
}
