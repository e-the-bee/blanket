// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** An API feature absent on some targets. {@code Blanket.supports(Feature.X)} is folded to a constant per target. */
@Owned
public enum Feature {
    /** The off-hand slot, added in 1.9. */
    OFFHAND
}
