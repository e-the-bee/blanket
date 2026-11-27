// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.lower.Hierarchy;
import com.blanket.core.resolve.TargetSpec;

import java.io.IOException;
import java.util.List;

/**
 * Plans for one mod jar and one bindings directory. Built by {@code Planner.plan(modJar, bindingsDir, embed, testkit)}.
 */
public interface PlanSource {

    /** The supported targets, in targets.json {@code supported} order. */
    List<TargetSpec> targets();

    /** The plan for one of {@link #targets()}. */
    Plan plan(TargetSpec target);

    /**
     * Whether the plans are for a {@code --testkit} build: {@link Planner#TESTKIT} was a root of every used set, so its
     * events are wired, and the generated entry starts it after {@code Boot.init}.
     */
    boolean testkit();

    /**
     * The class hierarchy of the target's game, libraries and JDK in runtime names, for frame computation
     * ({@link TargetHierarchy}); mod, embedded and adapter classes are not in it. The caller closes it.
     */
    Hierarchy hierarchy(TargetSpec target) throws IOException;
}
