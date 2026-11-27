// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

/** {@code GlueScreen.isModScreen} on 1.20.1: its screen host is {@link ScreenHost1201} (as {@link Screens1201} opens it). */
@Stubs("1.20.1")
public final class ScreenKindsHost1201 {
    private ScreenKindsHost1201() {}

    public static boolean isModScreen(Object screen) {
        return screen instanceof ScreenHost1201;
    }
}
