// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate.fixture;

/** An invented API type for validation tests: aliased to the fixture game's Player. */
public final class FxPlayer {
    private FxPlayer() {}

    public double x() { throw new UnsupportedOperationException("compile-time stub"); }

    public int armor() { throw new UnsupportedOperationException("compile-time stub"); }
}
