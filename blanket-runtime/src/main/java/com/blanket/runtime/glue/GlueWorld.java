// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.glue;

/**
 * Player position for camera interpolation on the legacy eras. Glue stubs, bound per target (see
 * {@link GlueFrame}).
 */
public final class GlueWorld {
    private GlueWorld() {}

    public static double px() { throw GlueFrame.stub("px"); }
    public static double py() { throw GlueFrame.stub("py"); }
    public static double pz() { throw GlueFrame.stub("pz"); }
    public static double lastPx() { throw GlueFrame.stub("lastPx"); }
    public static double lastPy() { throw GlueFrame.stub("lastPy"); }
    public static double lastPz() { throw GlueFrame.stub("lastPz"); }
}
