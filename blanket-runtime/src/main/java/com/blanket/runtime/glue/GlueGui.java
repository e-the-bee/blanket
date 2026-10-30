// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.glue;

/**
 * Payload conversions for the GUI hooks (binding-format.md section 7, D-051). Glue stubs, bound per target.
 */
public final class GlueGui {
    private GlueGui() {}

    /** Flushes the GUI graphics batch before immediate HUD drawing (`GuiGraphics#flush()` on 1.21). */
    public static void flush(Object guiGraphics) { throw GlueFrame.stub("flush"); }
}
