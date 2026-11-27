// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** Build-time queries. Calls are folded to constants per target; this body exists only for compilation. */
@Owned
public final class Blanket {
    private Blanket() {}

    /** The argument must be a {@link Feature} constant. */
    public static boolean supports(Feature feature) { throw new AssertionError("blanket stub"); }
}
