// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** The payload of {@link Events#SCREEN_OPEN}: the screen that opened, {@link ScreenKind#NONE} when one closed. */
@Owned
public final class ScreenOpen {
    private ScreenKind kind = ScreenKind.NONE;
    private boolean modScreen;

    public ScreenOpen() {}

    public ScreenKind kind() { return kind; }

    /** True when the screen is one of the mod's own {@link Screen}s. */
    public boolean isModScreen() { return modScreen; }

    /** Runtime use only. */
    public ScreenOpen set(ScreenKind kind, boolean modScreen) {
        this.kind = kind;
        this.modScreen = modScreen;
        return this;
    }
}
