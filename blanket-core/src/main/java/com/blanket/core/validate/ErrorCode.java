// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

/** The error codes of binding-format.md section 2.5. */
public enum ErrorCode {
    /** No rule matches the target. */
    UNBOUND,
    /** A rule matches but a reference does not resolve or is absent from the jar. */
    UNRESOLVED,
    /** A direct lowering's stack effect differs from the API member's. */
    SHAPE,
    /** A feature-gated or era-gated use is reachable on a target that cannot do it. */
    UNSUPPORTED,
    /** An aliased enum's constants are in a different order than the API's. */
    ORDER,
    /** The mod's class or classes need a newer class-file version than the target's Java accepts (lowering section 3). */
    VERSION,
    /** A mod class name collides with a name the remap would produce (lowering section 3). */
    COLLISION,
    /** A construct the lowering never allows, such as a non-literal {@code Blanket.supports} argument. */
    FORBIDDEN
}
