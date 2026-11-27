// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.testkit;

import com.blanket.runtime.Gl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads single pixels of the bound read framebuffer with {@code glReadPixels}. On core-profile eras a bound pixel-pack
 * buffer would turn the read into a buffer write, so it is unbound for the read and restored after.
 */
final class GlPixels implements Session.Pixels {
    static final int RGBA = 0x1908, UNSIGNED_BYTE = 0x1401;
    static final int PIXEL_PACK_BUFFER = 0x88EB, PIXEL_PACK_BUFFER_BINDING = 0x88ED;

    private final ByteBuffer pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
    private Gl gl;
    private boolean core;

    GlPixels at(Gl gl, boolean core) {
        this.gl = gl;
        this.core = core;
        return this;
    }

    @Override
    public int rgb(int x, int y) {
        int pack = core ? gl.getInteger(PIXEL_PACK_BUFFER_BINDING) : 0;
        if (pack != 0) gl.bindBuffer(PIXEL_PACK_BUFFER, 0);
        try {
            pixel.clear();
            gl.readPixels(x, y, 1, 1, RGBA, UNSIGNED_BYTE, pixel);
        } finally {
            if (pack != 0) gl.bindBuffer(PIXEL_PACK_BUFFER, pack);
        }
        return (pixel.get(0) & 255) << 16 | (pixel.get(1) & 255) << 8 | (pixel.get(2) & 255);
    }
}
