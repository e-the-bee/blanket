// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate.fixture;

/** An invented API type for validation tests: aliased to the fixture game's Client. */
public final class FxClient {
    private FxClient() {}

    public static FxClient get() { throw new UnsupportedOperationException("compile-time stub"); }

    public FxPlayer player() { throw new UnsupportedOperationException("compile-time stub"); }
}
