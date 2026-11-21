// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import java.util.List;

/**
 * The body of one runtime glue stub for a target.
 *
 * @param name    {@code <stub class internal name>.<method name>}, e.g. {@code com/blanket/runtime/glue/GlueFrame.fbo};
 *                the stub class method of that name (one only) gets {@code steps} as its body
 * @param retDesc the method's return descriptor; the body ends with the matching return instruction
 * @param steps   the chain from nothing (parameters via {@code LOAD}, slot 0 first) to the return value
 */
public record Glue(String name, String retDesc, List<Op> steps) {}
