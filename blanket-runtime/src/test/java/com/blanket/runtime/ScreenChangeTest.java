// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * SCREEN_OPEN reporting (events-and-hooks.md section 4.5). Forge 1.20.1 and NeoForge 1.21.1 post "opening" for a
 * non-null screen and "closing" for the old screen after it (checked against the Minecraft patches), so a replacement
 * arrives as B then null while A is still open; Fabric and Forge 1.8.9 hand over the new screen, null on a close.
 */
class ScreenChangeTest {
    private final Object a = new Object();
    private final Object b = new Object();

    @Test
    void anOpenAndALaterCloseAreBothReported() {
        ScreenChange s = new ScreenChange();
        assertTrue(s.accept(a, null), "open from no screen");
        assertTrue(s.accept(null, a), "the close: A is the open screen");
    }

    @Test
    void aCloseWithNothingOpenIsReported() {
        assertTrue(new ScreenChange().accept(null, null));
    }

    @Test
    void aReplacementOnForgeIsOneEventNotAnOpenThenAClose() {
        ScreenChange s = new ScreenChange();
        assertTrue(s.accept(a, null));
        assertTrue(s.accept(b, a), "opening B while A is open");
        assertFalse(s.accept(null, a), "the closing event of A, posted after B was reported: no close");
        assertTrue(s.accept(null, b), "B closes for real");
    }

    @Test
    void aReplacementOnFabricLeavesTheLaterCloseReported() {
        ScreenChange s = new ScreenChange();
        assertTrue(s.accept(a, null));
        assertTrue(s.accept(b, a));
        assertTrue(s.accept(null, b), "no closing event follows on Fabric: the null is the close of B");
    }

    @Test
    void openingTheScreenThatIsAlreadyOpenIsNotAReplacement() {
        ScreenChange s = new ScreenChange();
        assertTrue(s.accept(a, null));
        assertTrue(s.accept(a, a));
        assertTrue(s.accept(null, a));
    }

    @Test
    void aNullTwiceIsReportedTwice() {
        ScreenChange s = new ScreenChange();
        assertTrue(s.accept(a, null));
        assertTrue(s.accept(null, a));
        assertTrue(s.accept(null, null), "the client had no screen to compare with: report the close");
    }
}
