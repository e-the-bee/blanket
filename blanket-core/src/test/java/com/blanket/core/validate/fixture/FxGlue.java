// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate.fixture;

/** An invented glue type for validation tests. */
public final class FxGlue {
    private FxGlue() {}

    public static int fbo() { throw new UnsupportedOperationException("glue stub"); }

    public static int armorOf(Object player) { throw new UnsupportedOperationException("glue stub"); }
}
