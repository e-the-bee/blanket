// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The recorder stores triangles as x, y, z, r, g, b, a per vertex. */
class RecorderTest {
    private static final int GREEN = 0xFF00FF00;

    @Test
    void fillRectIsTwoTrianglesWithColour() {
        Recorder r = new Recorder();
        r.fillRect(10, 20, 30, 40, GREEN);
        assertEquals(6, r.vertexCount());
        // First vertex: (x, y, z, r, g, b, a) = (10, 20, 0, 0, 1, 0, 1).
        assertEquals(10f, r.data[0], 0f);
        assertEquals(20f, r.data[1], 0f);
        assertEquals(0f, r.data[2], 0f);
        assertEquals(0f, r.data[3], 0f);
        assertEquals(1f, r.data[4], 0f);
        assertEquals(0f, r.data[5], 0f);
        assertEquals(1f, r.data[6], 0f);
    }

    @Test
    void boxFillIsTwelveTrianglesRelativeToCamera() {
        Recorder r = new Recorder();
        r.camX = 1; r.camY = 2; r.camZ = 3;
        r.boxFill(1, 2, 3, 2, 3, 4, GREEN);
        assertEquals(36, r.vertexCount());
        // The first corner is the box minimum minus the camera, so it sits at the origin.
        assertEquals(0f, r.data[0], 0f);
        assertEquals(0f, r.data[1], 0f);
        assertEquals(0f, r.data[2], 0f);
    }

    @Test
    void clearResetsAndStorageGrows() {
        Recorder r = new Recorder();
        for (int i = 0; i < 200; i++) r.fillRect(i, 0, 1, 1, GREEN);
        assertEquals(1200, r.vertexCount());
        r.clear();
        assertEquals(0, r.vertexCount());
    }
}
