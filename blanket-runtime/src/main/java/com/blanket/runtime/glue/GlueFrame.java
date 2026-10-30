// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.glue;

/**
 * Frame and render-target facts the GL backend needs. Each method is a glue stub: bindings
 * (glue/GlueFrame.json, blanket-bindings) give its lowering per target, so the body here never runs in
 * a generated jar. Calling it from a plain JVM is an error.
 */
public final class GlueFrame {
    private GlueFrame() {}

    /** GLSL 1.50 with a vertex array object (core profile) instead of GLSL 1.20. */
    public static boolean core() { throw stub("core"); }

    /** Framebuffer to draw into: an id, -1 for "whatever is bound" (1.8.9), -2 for "build from colorTex/depthTex". */
    public static int fbo() { throw stub("fbo"); }

    public static int colorTex() { throw stub("colorTex"); }
    public static int depthTex() { throw stub("depthTex"); }
    public static int fbWidth() { throw stub("fbWidth"); }
    public static int fbHeight() { throw stub("fbHeight"); }
    public static int guiWidth() { throw stub("guiWidth"); }
    public static int guiHeight() { throw stub("guiHeight"); }

    static UnsupportedOperationException stub(String member) {
        return new UnsupportedOperationException("GlueFrame." + member + " is a glue stub; bound per target by blanket-bindings");
    }
}
