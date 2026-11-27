// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** Input queries. Owned facade: the members are bound per target like alias members. */
@Owned
public final class Input {
    private Input() {}

    public static boolean isKeyDown(Key key) { throw new AssertionError("blanket stub"); }
}
