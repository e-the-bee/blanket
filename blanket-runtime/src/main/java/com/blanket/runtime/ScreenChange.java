// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

/**
 * Decides which screen events {@link Dispatch#screenOpen} reports. A recipe hands over the screen being opened, and
 * null when a screen closes. Forge 1.20.1 and NeoForge 1.21.1 post their opening event only for a non-null screen
 * and post the closing event of the old screen after it, so a replacement (A to B) arrives as {@code B} then null
 * while A is still the open screen. That null is no close: B was reported and has not been assigned yet. Holds the
 * screens as {@link Object}, so it needs no game type. Client thread only.
 */
final class ScreenChange {
    private Object last;

    /**
     * @param screen  the screen being opened, null for a close
     * @param current the screen the client has open right now (before the change takes effect)
     * @return whether to fire SCREEN_OPEN for it
     */
    boolean accept(Object screen, Object current) {
        if (screen != null) {
            last = screen;
            return true;
        }
        boolean replacement = last != null && current != null && last != current;
        last = null;
        return !replacement;
    }
}
