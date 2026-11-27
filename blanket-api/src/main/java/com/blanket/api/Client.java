// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** The game client handle. Aliased to the game class per target; this body exists only for compilation. */
public final class Client {
    private Client() {}

    /** The client; callable from the client thread. */
    public static Client get() { throw new AssertionError("blanket stub"); }

    /** Null when no world is loaded. */
    public Player player() { throw new AssertionError("blanket stub"); }

    /** Null when no world is loaded. */
    public World world() { throw new AssertionError("blanket stub"); }

    public int guiWidth() { throw new AssertionError("blanket stub"); }

    public int guiHeight() { throw new AssertionError("blanket stub"); }

    public double guiScale() { throw new AssertionError("blanket stub"); }

    public void openScreen(Screen screen) { throw new AssertionError("blanket stub"); }

    public void closeScreen() { throw new AssertionError("blanket stub"); }

    public boolean isScreenOpen() { throw new AssertionError("blanket stub"); }

    /** Null when no world is loaded. */
    public Target crosshairTarget() { throw new AssertionError("blanket stub"); }

    /** Local chat feedback. */
    public void showMessage(String text) { throw new AssertionError("blanket stub"); }

    /** Callable from any thread: runs at the next client tick, or immediately when already on the client thread. */
    public static void runOnClientThread(Runnable task) { throw new AssertionError("blanket stub"); }

    public String translate(String key, Object... args) { throw new AssertionError("blanket stub"); }
}
