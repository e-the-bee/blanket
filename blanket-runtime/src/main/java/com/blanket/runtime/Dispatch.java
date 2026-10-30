// SPDX-License-Identifier: GPL-3.0-only
// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import com.blanket.api.ClientTick;
import com.blanket.api.Events;
import com.blanket.api.HudRender;
import com.blanket.api.WorldRender;
import com.blanket.runtime.glue.GlueWorld;

/** Entry points for the generated hooks (Mixin handlers and native listeners). Client thread only. */
public final class Dispatch {
    private Dispatch() {}

    private static final Recorder HUD = new Recorder();
    private static final Recorder WORLD = new Recorder();
    private static final HudRender HUD_PAYLOAD = new HudRender(HUD);
    private static final WorldRender WORLD_PAYLOAD = new WorldRender(WORLD);
    private static boolean hudPending;

    public static void tickEnd() {
        Events.CLIENT_TICK_END.fire(ClientTick.INSTANCE);
    }

    /** Immediate GUI eras: record and draw now. */
    public static void hud(float partialTick) {
        hudRecord(partialTick);
        hudReplay();
    }

    /** Deferred GUI eras (1.21.6+): record during extraction. */
    public static void hudRecord(float partialTick) {
        if (Events.HUD_RENDER.isEmpty()) return;
        HUD.clear();
        Events.HUD_RENDER.fire(HUD_PAYLOAD.set(partialTick));
        hudPending = true;
    }

    /** Deferred GUI eras: draw what was recorded, after the game's GUI renderer ran. */
    public static void hudReplay() {
        if (!hudPending) return;
        hudPending = false;
        Executor.drawHud(HUD);
    }

    /** Modern eras: matrices from the hook (column-major), camera position in world coordinates. */
    public static void world(float partialTick, float[] projection, float[] view, double camX, double camY, double camZ) {
        if (Events.WORLD_RENDER.isEmpty()) return;
        WORLD.clear();
        WORLD.camX = camX; WORLD.camY = camY; WORLD.camZ = camZ;
        Events.WORLD_RENDER.fire(WORLD_PAYLOAD.set(partialTick));
        Executor.drawWorld(WORLD, projection, view);
    }

    /** Legacy eras (1.8.9): matrices are read from the fixed-function stacks, camera from the player. */
    public static void worldLegacy(float partialTick) {
        if (Events.WORLD_RENDER.isEmpty()) return;
        world(partialTick, null, null,
                GlueWorld.lastPx() + (GlueWorld.px() - GlueWorld.lastPx()) * partialTick,
                GlueWorld.lastPy() + (GlueWorld.py() - GlueWorld.lastPy()) * partialTick,
                GlueWorld.lastPz() + (GlueWorld.pz() - GlueWorld.lastPz()) * partialTick);
    }
}
