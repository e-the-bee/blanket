// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import com.blanket.api.Client;
import com.blanket.api.ClientTick;
import com.blanket.api.Events;
import com.blanket.api.HudRender;
import com.blanket.api.WorldJoin;
import com.blanket.api.WorldLeave;
import com.blanket.api.WorldRender;
import com.blanket.runtime.glue.GlueWorld;

/** Entry points for the generated hooks (Mixin handlers and native listeners). Client thread only. */
public final class Dispatch {
    private Dispatch() {}

    private static final Recorder HUD = new Recorder();
    private static final Recorder WORLD = new Recorder();
    private static final HudRender HUD_PAYLOAD = new HudRender(HUD);
    private static final WorldRender WORLD_PAYLOAD = new WorldRender(WORLD);
    private static final WorldChange WORLDS = new WorldChange();
    private static boolean hudPending;

    /** Start of a client tick: WORLD_LEAVE and WORLD_JOIN when the world changed (D-015), then CLIENT_TICK_START. */
    public static void tickStart() {
        int change = WORLDS.update(Client.get().world());
        if ((change & WorldChange.LEAVE) != 0) Events.WORLD_LEAVE.fire(WorldLeave.INSTANCE);
        if ((change & WorldChange.JOIN) != 0) Events.WORLD_JOIN.fire(WorldJoin.INSTANCE);
        Events.CLIENT_TICK_START.fire(ClientTick.INSTANCE);
    }

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

    /**
     * Key hand-over into the options' key array (events-and-hooks 4.7): Fabric and Legacy Fabric at the head of the
     * options' load (the options are {@code this} there, before {@code options.txt} is read), Forge 1.8.9 at
     * {@code FMLInitializationEvent} (FML reloads {@code options.txt} after mod init). Once: the queue is drained.
     */
    public static void keysOptions(Object options) {
        java.util.List<Object> queued = KeyQueue.drain();
        if (queued.isEmpty()) return;
        com.blanket.runtime.glue.GlueKeys.setMappings(options,
                KeyHandover.append(com.blanket.runtime.glue.GlueKeys.mappings(options), queued));
    }

    /** Key hand-over through the loader's {@code RegisterKeyMappingsEvent} (Forge 1.20.1, NeoForge), on the mod bus. */
    public static void keysRegister(Object event) {
        for (Object mapping : KeyQueue.drain()) com.blanket.runtime.glue.GlueKeys.register(event, mapping);
    }
}
