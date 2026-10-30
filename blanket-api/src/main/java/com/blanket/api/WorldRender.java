// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

import com.blanket.api.render.Draw3D;

public final class WorldRender {
    private final Draw3D draw;
    private float partialTick;

    public WorldRender(Draw3D draw) { this.draw = draw; }

    public Draw3D draw() { return draw; }
    public float partialTick() { return partialTick; }

    /** Runtime use only. */
    public WorldRender set(float partialTick) { this.partialTick = partialTick; return this; }
}
