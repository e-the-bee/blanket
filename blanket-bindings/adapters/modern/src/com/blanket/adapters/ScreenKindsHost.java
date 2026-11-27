// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

/** {@code GlueScreen.isModScreen} on 1.21.1 ({@link ScreenHost}); 1.20.1 has {@link ScreenKindsHost1201}. */
public final class ScreenKindsHost {
    private ScreenKindsHost() {}

    public static boolean isModScreen(Object screen) {
        return screen instanceof ScreenHost;
    }
}
