// SPDX-License-Identifier: GPL-3.0-only
package com.example.hudbox;

import com.blanket.api.Box;
import com.blanket.api.Client;
import com.blanket.api.ClientMod;
import com.blanket.api.Events;
import com.blanket.api.Key;
import com.blanket.api.KeyBinding;
import com.blanket.api.Player;

/**
 * The M3 hud-box sample (testing-strategy section 4): a magenta 40x40 HUD rectangle at (10, 10) and an opaque green
 * world box three blocks south of where the player first stood on the ground after a join. Key {@code H} ("toggle") hides and
 * shows the rectangle. Every log line starts with {@code [hudbox]}. There is no text: Canvas2D has no text until M4.
 */
public class HudBox implements ClientMod {
    private int ticksStart;
    private int ticksEnd;
    private boolean joined;
    private boolean anchored;
    private boolean visible = true;
    private double ax, ay, az;
    private KeyBinding toggle;

    @Override
    public void init() {
        log("init");
        toggle = KeyBinding.register("toggle", Key.H, "hudbox");
        Events.CLIENT_TICK_START.register(e -> ticksStart++);
        Events.WORLD_JOIN.register(e -> {
            joined = true;
            anchored = false;
            log("join");
        });
        Events.CLIENT_TICK_END.register(e -> {
            ticksEnd++;
            Player p = Client.get().player();
            if (p == null) {
                return;
            }
            if (joined && !anchored && p.onGround()) {
                // The box sits 3 blocks south of where the player first stood (a new player faces south). On the
                // ground only: on 1.8.9 the first tick after a join can show the position from before the server's
                // position packet (M3 gate round 2).
                ax = Math.floor(p.x());
                ay = Math.floor(p.y());
                az = Math.floor(p.z()) + 3;
                anchored = true;
                log("box anchored at " + ax + " " + ay + " " + az);
            }
            if (ticksEnd % 20 == 0) {
                log("pos " + p.x() + " " + p.y() + " " + p.z());
            }
            while (toggle.consumePress()) {
                visible = !visible;
                log("toggle visible=" + visible);
            }
        });
        Events.HUD_RENDER.register(e -> {
            if (visible) {
                e.canvas().fillRect(10, 10, 40, 40, 0xFFFF00FF);
            }
        });
        Events.WORLD_RENDER.register(e -> {
            if (anchored) {
                e.draw().boxFill(Box.of(ax, ay, az, ax + 1, ay + 1, az + 1), 0xFF00FF00);
            }
        });
    }

    private static void log(String line) {
        System.out.println("[hudbox] " + line);
    }
}
