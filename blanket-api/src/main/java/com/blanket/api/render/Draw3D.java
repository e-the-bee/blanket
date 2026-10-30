// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api.render;

/**
 * 3D drawing in world coordinates. M0 takes the box as six numbers; the real API takes an aliased
 * {@code Box} (rendering-api.md section 2).
 */
public interface Draw3D {
    void boxFill(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int argb);
}
