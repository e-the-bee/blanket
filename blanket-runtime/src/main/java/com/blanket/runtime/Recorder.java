// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import com.blanket.api.render.Canvas2D;
import com.blanket.api.render.Draw3D;

/** Records triangles (x, y, z, r, g, b, a per vertex) for one event; the executor draws them. */
public final class Recorder implements Canvas2D, Draw3D {
    float[] data = new float[7 * 6 * 64];
    int floats;
    double camX, camY, camZ;

    void clear() { floats = 0; }

    int vertexCount() { return floats / 7; }

    public void fillRect(float x, float y, float w, float h, int argb) {
        quad(x, y, 0, x, y + h, 0, x + w, y + h, 0, x + w, y, 0, argb);
    }

    public void boxFill(double x0, double y0, double z0, double x1, double y1, double z1, int argb) {
        float a = (float) (x0 - camX), b = (float) (y0 - camY), c = (float) (z0 - camZ);
        float d = (float) (x1 - camX), e = (float) (y1 - camY), f = (float) (z1 - camZ);
        quad(a, b, c, d, b, c, d, b, f, a, b, f, argb); // bottom
        quad(a, e, c, a, e, f, d, e, f, d, e, c, argb); // top
        quad(a, b, c, a, e, c, d, e, c, d, b, c, argb); // north
        quad(a, b, f, d, b, f, d, e, f, a, e, f, argb); // south
        quad(a, b, c, a, b, f, a, e, f, a, e, c, argb); // west
        quad(d, b, c, d, e, c, d, e, f, d, b, f, argb); // east
    }

    private void quad(float x0, float y0, float z0, float x1, float y1, float z1,
                      float x2, float y2, float z2, float x3, float y3, float z3, int argb) {
        vertex(x0, y0, z0, argb); vertex(x1, y1, z1, argb); vertex(x2, y2, z2, argb);
        vertex(x0, y0, z0, argb); vertex(x2, y2, z2, argb); vertex(x3, y3, z3, argb);
    }

    private void vertex(float x, float y, float z, int argb) {
        if (floats + 7 > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
        data[floats++] = x; data[floats++] = y; data[floats++] = z;
        data[floats++] = ((argb >> 16) & 255) / 255f;
        data[floats++] = ((argb >> 8) & 255) / 255f;
        data[floats++] = (argb & 255) / 255f;
        data[floats++] = ((argb >>> 24) & 255) / 255f;
    }
}
