// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.glue;

/**
 * Screen facts for SCREEN_OPEN and the HUD suppression (events-and-hooks.md sections 4.3 and 4.5, D-018). Glue stubs,
 * bound per target (glue/GlueScreen.json): the first two to an adapter of the target's set, which tests the screen
 * object against the era's screen classes; {@link #current()} to a read of the client's open screen.
 */
public final class GlueScreen {
    private GlueScreen() {}

    /** {@code ScreenKind} ordinal of a game screen object: title, pause, chat, inventory, container or other; NONE for null. */
    public static int kind(Object screen) { throw GlueFrame.stub("kind"); }

    /** Whether the game screen object is the host of one of the mod's own screens. */
    public static boolean isModScreen(Object screen) { throw GlueFrame.stub("isModScreen"); }

    /** The open game screen, or null when none is open. */
    public static Object current() { throw GlueFrame.stub("current"); }
}
