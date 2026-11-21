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

    /** A {@code Matrix4f} as a new column-major {@code float[16]}. */
    public static float[] matrix(Object matrix4f) { throw GlueFrame.stub("matrix"); }
    /** The top {@code Matrix4f} of a {@code PoseStack}. */
    public static Object poseMatrix(Object poseStack) { throw GlueFrame.stub("poseMatrix"); }
    /** The game renderer's main camera (26.x hooks have no camera argument). */
    public static Object camera() { throw GlueFrame.stub("camera"); }
    /** A new identity {@code Matrix4f}. */
    public static Object newMatrix() { throw GlueFrame.stub("newMatrix"); }
    /** The camera's view-rotation-projection matrix, written into {@code dest} and returned (26.x). */
    public static Object viewProjection(Object camera, Object dest) { throw GlueFrame.stub("viewProjection"); }
    /** Camera position in world coordinates. */
    public static double camX(Object camera) { throw GlueFrame.stub("camX"); }
    public static double camY(Object camera) { throw GlueFrame.stub("camY"); }
    public static double camZ(Object camera) { throw GlueFrame.stub("camZ"); }
}
