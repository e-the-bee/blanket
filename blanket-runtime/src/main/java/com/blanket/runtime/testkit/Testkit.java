// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.testkit;

import com.blanket.api.Client;
import com.blanket.api.ClientTick;
import com.blanket.api.EventSource;
import com.blanket.api.Events;
import com.blanket.api.HudRender;
import com.blanket.api.Listener;
import com.blanket.api.Player;
import com.blanket.api.WorldJoin;
import com.blanket.api.WorldLeave;
import com.blanket.api.WorldRender;
import com.blanket.runtime.Executor;
import com.blanket.runtime.Gl;
import com.blanket.runtime.KeyQueue;
import com.blanket.runtime.glue.GlueTestkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The in-game testkit (testing-strategy.md section 4, M3 plan section 3.3). Only {@code --testkit} builds ship it: the
 * generated entry calls {@link #start} right after {@code Boot.init}. Without the system properties
 * {@code blanket.testkit.probes} and {@code blanket.testkit.out} it does nothing. Its own subscriptions wire the events
 * it needs, so it works whatever the mod subscribes to.
 */
public final class Testkit {
    private Testkit() {}

    static final String PROBES = "blanket.testkit.probes", OUT = "blanket.testkit.out";

    private static Session session;

    public static void start() {
        String probes = System.getProperty(PROBES), out = System.getProperty(OUT);
        if (probes == null || out == null || session != null) return;
        Spec spec = null;
        String error = null;
        try {
            spec = Spec.parse(new String(Files.readAllBytes(Paths.get(probes)), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            error = probes + ": " + e.getMessage();
        }
        final Session s = new Session(spec, error, KeyQueue.modId(), new InGame(Paths.get(out)));
        session = s;
        Events.CLIENT_TICK_START.register(new Listener<ClientTick>() {
            public void on(ClientTick e) { s.tickStart(); }
        });
        Events.CLIENT_TICK_END.register(new Listener<ClientTick>() {
            public void on(ClientTick e) { s.tickEnd(); }
        });
        Events.WORLD_JOIN.register(new Listener<WorldJoin>() {
            public void on(WorldJoin e) { s.worldJoin(); }
        });
        Events.WORLD_LEAVE.register(new Listener<WorldLeave>() {
            public void on(WorldLeave e) { s.worldLeave(); }
        });
        // empty subscriptions: they wire the HUD and world hooks, so the executor runs (and taps) every frame
        Events.HUD_RENDER.register(new Listener<HudRender>() {
            public void on(HudRender e) {}
        });
        Events.WORLD_RENDER.register(new Listener<WorldRender>() {
            public void on(WorldRender e) {}
        });
        final GlPixels pixels = new GlPixels();
        Executor.tap(new Executor.Tap() {
            public void world(float[] mvp, double camX, double camY, double camZ) { s.world(mvp, camX, camY, camZ); }

            public boolean wantsHud() { return s.wantsHud(); }

            public void hud(Gl gl, boolean core, int x, int y, int width, int height) {
                s.hud(x, y, width, height, Client.get().guiScale(), pixels.at(gl, core));
            }
        });
        System.out.println("[blanket-testkit] on: probes " + probes + ", result " + out);
    }

    /** The game side of a session. */
    private static final class InGame implements Session.Game {
        private final Path out;

        InGame(Path out) {
            this.out = out;
        }

        public double[] player() {
            Player p = Client.get().player();
            return p == null ? null : new double[] {p.x(), p.y(), p.z()};
        }

        public boolean pressFirstKey() {
            List<Object> keys = KeyQueue.registered();
            if (keys.isEmpty()) return false;
            GlueTestkit.press(keys.get(0));
            return true;
        }

        public int listenerFailures() {
            return EventSource.failures();
        }

        /** Written beside the target and moved into place, so the harness never sees half a file. */
        public void write(String json) throws IOException {
            Path dir = out.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        public void quit() {
            GlueTestkit.quit();
        }

        public void log(String line) {
            System.out.println("[blanket-testkit] " + line);
        }
    }
}
