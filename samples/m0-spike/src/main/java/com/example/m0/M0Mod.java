// SPDX-License-Identifier: GPL-3.0-only
package com.example.m0;

import com.blanket.api.Client;
import com.blanket.api.ClientMod;
import com.blanket.api.Events;
import com.blanket.api.Player;

/** The M0 sample: a magenta HUD rectangle, a green world box, and the player position every 20 ticks. */
public class M0Mod implements ClientMod {
    private int ticks;
    private boolean anchored;
    private double ax, ay, az;

    @Override
    public void init() {
        System.out.println("[m0spike] init");
        Events.CLIENT_TICK_END.register(e -> {
            Player p = Client.get().player();
            if (p == null) {
                anchored = false;
                return;
            }
            if (!anchored) {
                // The box sits 3 blocks south of where the player first appeared (a new player faces south).
                ax = Math.floor(p.x());
                ay = Math.floor(p.y());
                az = Math.floor(p.z()) + 3;
                anchored = true;
                System.out.println("[m0spike] box anchored at " + ax + " " + ay + " " + az);
            }
            if (++ticks % 20 == 0) {
                System.out.println("[m0spike] pos " + p.x() + " " + p.y() + " " + p.z());
            }
        });
        Events.HUD_RENDER.register(e -> e.canvas().fillRect(10, 10, 60, 30, 0xFFFF00FF));
        Events.WORLD_RENDER.register(e -> {
            if (anchored) {
                e.draw().boxFill(ax, ay, az, ax + 1, ay + 1, az + 1, 0xC000FF00);
            }
        });
    }
}
