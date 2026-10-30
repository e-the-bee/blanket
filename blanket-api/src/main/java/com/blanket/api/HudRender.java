// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

import com.blanket.api.render.Canvas2D;

public final class HudRender {
    private final Canvas2D canvas;
    private float partialTick;

    public HudRender(Canvas2D canvas) { this.canvas = canvas; }

    public Canvas2D canvas() { return canvas; }
    public float partialTick() { return partialTick; }

    /** Runtime use only. */
    public HudRender set(float partialTick) { this.partialTick = partialTick; return this; }
}
