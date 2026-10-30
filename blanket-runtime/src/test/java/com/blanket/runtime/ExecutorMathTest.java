// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Column-major matrix helpers used to build the draw transform (rendering-api.md section 3). */
class ExecutorMathTest {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    private static float[] translate(float x, float y, float z) {
        float[] m = IDENTITY.clone();
        m[12] = x; m[13] = y; m[14] = z;
        return m;
    }

    private static float[] scale(float s) {
        float[] m = IDENTITY.clone();
        m[0] = s; m[5] = s; m[10] = s;
        return m;
    }

    @Test
    void identityIsNeutralOnBothSides() {
        float[] m = translate(3, -4, 5);
        assertArrayEquals(m, Executor.mul(IDENTITY, m), 0f);
        assertArrayEquals(m, Executor.mul(m, IDENTITY), 0f);
    }

    @Test
    void translationsAddUp() {
        assertArrayEquals(translate(5, 7, 9), Executor.mul(translate(1, 2, 3), translate(4, 5, 6)), 1e-6f);
    }

    @Test
    void multiplicationOrderMatters() {
        // S * T moves the scaled translation: the translation column is S applied to (1,0,0,1).
        assertEquals(2f, Executor.mul(scale(2), translate(1, 0, 0))[12], 1e-6f);
        // T * S keeps the translation as written.
        assertEquals(1f, Executor.mul(translate(1, 0, 0), scale(2))[12], 1e-6f);
    }

    @Test
    void orthoMapsGuiPixelsWithOriginTopLeft() {
        float[] m = Executor.ortho(640, 480);
        // Pixel (0,0) is the top-left corner: clip (-1, 1).
        assertEquals(-1f, m[0] * 0 + m[12], 1e-6f);
        assertEquals(1f, m[5] * 0 + m[13], 1e-6f);
        // Pixel (w, h) is the bottom-right corner: clip (1, -1).
        assertEquals(1f, m[0] * 640 + m[12], 1e-6f);
        assertEquals(-1f, m[5] * 480 + m[13], 1e-6f);
        assertEquals(1f, m[15], 0f);
    }
}
