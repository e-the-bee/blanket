// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.lower.Hierarchy;
import com.blanket.core.resolve.TargetSpec;

import java.io.IOException;
import java.util.List;

/** Plans for one mod jar and one bindings directory. Built by {@code Planner.plan(modJar, bindingsDir)}. */
public interface PlanSource {

    /** The supported targets, in targets.json {@code supported} order. */
    List<TargetSpec> targets();

    /** The plan for one of {@link #targets()}. */
    Plan plan(TargetSpec target);

    /** The class hierarchy of the target's runtime classes, for frame computation. The caller closes it. */
    Hierarchy hierarchy(TargetSpec target) throws IOException;
}
