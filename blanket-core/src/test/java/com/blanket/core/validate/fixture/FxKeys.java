// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate.fixture;

/** An invented glue type for validation tests: lowered to a loader method that takes a game class. */
public final class FxKeys {
    private FxKeys() {}

    public static void register(FxPlayer player) { throw new UnsupportedOperationException("glue stub"); }
}
