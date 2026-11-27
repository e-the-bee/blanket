// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import java.util.List;

/** What the planner decided for one API member on one target: how its call site is lowered. */
public sealed interface Lowered permits Lowered.Inline, Lowered.Construct, Lowered.Adapter, Lowered.Unsupported {

    /** invoke, get, put, const, chain: replaces the call; same stack effect as the API member. */
    record Inline(List<Op> ops) implements Lowered {}

    /** new: bridge NEW owner; DUP; loads; INVOKESPECIAL owner.&lt;init&gt;ctorDesc; ARETURN (runtime names). */
    record Construct(String owner, String ctorDesc) implements Lowered {}

    /**
     * adapter: INVOKESTATIC owner.name desc. owner = {@code com/blanket/adapters/<set>/<Class>}; desc = API descriptor,
     * receiver first, API names (pass 5 remaps both).
     */
    record Adapter(String owner, String name, String desc) implements Lowered {}

    /** feature == null: explicit "unsupported"; else the Feature constant a "requires" rule needs. */
    record Unsupported(String reason, String feature) implements Lowered {}
}
