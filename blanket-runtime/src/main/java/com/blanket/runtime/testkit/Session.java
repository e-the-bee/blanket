// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.testkit;

import java.util.ArrayList;
import java.util.List;

/**
 * One testkit run (M3 plan section 3.3), without game types: counts the events, anchors at the first CLIENT_TICK_END
 * after WORLD_JOIN with a player on the ground, waits {@code waitTicks}, reads the probes in the next frame's HUD readback, presses
 * the first registered key and re-reads the key probe after {@code afterTicks}, then writes the result at a
 * CLIENT_TICK_END and quits. {@link Testkit} feeds it from the events and the executor's tap.
 */
final class Session {
    /** What the session asks of the game. */
    interface Game {
        /** The player's position once it stands on the ground, or null (no player, or still in the air). */
        double[] player();

        /** Presses the first key mapping the mod registered; false when it registered none. */
        boolean pressFirstKey();

        int listenerFailures();

        void write(String json) throws java.io.IOException;

        void quit();

        void log(String line);
    }

    /** The framebuffer being read: {@code 0xRRGGBB} at GL window coordinates (origin bottom left). */
    interface Pixels {
        int rgb(int x, int y);
    }

    /** A requested readback that gets no frame for this many ticks fails its probes (a screen hides the HUD, say). */
    static final int FRAME_WAIT_TICKS = 200;
    static final String BEFORE = "before-key", AFTER = "after-key";

    private enum Phase { ANCHOR, WAIT, READ_BEFORE, PRESS, WAIT_KEY, READ_AFTER, WRITE, DONE }

    /** One probe reading; {@code x}, {@code y} are framebuffer pixels from the top left, -1 when the point was off screen. */
    static final class Reading {
        final String name, round;
        final int x, y;
        /** {@code #RRGGBB}, or null when nothing was read. */
        final String rgb;
        final boolean pass;

        Reading(String name, String round, int x, int y, String rgb, boolean pass) {
            this.name = name;
            this.round = round;
            this.x = x;
            this.y = y;
            this.rgb = rgb;
            this.pass = pass;
        }
    }

    private final Spec spec;
    private final String error;
    private final String mod;
    private final Game game;

    private int ticksStart, ticksEnd, worldJoins, worldLeaves;
    private boolean joined;
    private Phase phase;
    private int phaseTick;
    private double ox, oy, oz;
    private float[] worldMvp;
    private double camX, camY, camZ;
    private final List<Reading> readings = new ArrayList<Reading>();
    private boolean keyPressed, keyPass;

    /** A session for {@code spec}; with {@code error} (an unreadable probes file) it writes a failed result at the first tick end. */
    Session(Spec spec, String error, String mod, Game game) {
        this.spec = spec;
        this.error = error;
        this.mod = mod;
        this.game = game;
        this.phase = error != null ? Phase.WRITE : Phase.ANCHOR;
        if (error != null) game.log("cannot run: " + error);
    }

    void tickStart() {
        ticksStart++;
    }

    void worldJoin() {
        worldJoins++;
        joined = true;
    }

    void worldLeave() {
        worldLeaves++;
    }

    void tickEnd() {
        ticksEnd++;
        switch (phase) {
            case ANCHOR:
                if (!joined) return;
                double[] p = game.player();
                if (p == null) return;
                ox = Math.floor(p[0]);
                oy = Math.floor(p[1]);
                oz = Math.floor(p[2]);
                game.log("anchor " + (long) ox + " " + (long) oy + " " + (long) oz + " at tick " + ticksEnd);
                enter(Phase.WAIT);
                return;
            case WAIT:
                if (ticksEnd - phaseTick >= spec.waitTicks) enter(Phase.READ_BEFORE);
                return;
            case READ_BEFORE:
                if (ticksEnd - phaseTick >= FRAME_WAIT_TICKS) {
                    game.log("no HUD frame for " + FRAME_WAIT_TICKS + " ticks: the " + BEFORE + " probes fail");
                    for (Spec.Probe pr : spec.probes) readings.add(new Reading(pr.name, BEFORE, -1, -1, null, false));
                    finish();
                }
                return;
            case PRESS:
                if (spec.key == null || !allPass()) {
                    finish();
                    return;
                }
                keyPressed = game.pressFirstKey();
                if (!keyPressed) {
                    game.log("the mod registered no key: the key check fails");
                    finish();
                    return;
                }
                game.log("pressed the first registered key at tick " + ticksEnd);
                enter(Phase.WAIT_KEY);
                return;
            case WAIT_KEY:
                if (ticksEnd - phaseTick >= spec.key.afterTicks) enter(Phase.READ_AFTER);
                return;
            case READ_AFTER:
                if (ticksEnd - phaseTick >= FRAME_WAIT_TICKS) {
                    game.log("no HUD frame for " + FRAME_WAIT_TICKS + " ticks: the " + AFTER + " probe fails");
                    readings.add(new Reading(spec.key.probe.name, AFTER, -1, -1, null, false));
                    finish();
                }
                return;
            case WRITE:
                write();
                return;
            default:
        }
    }

    /** Whether the next HUD frame should be read back. */
    boolean wantsHud() {
        return phase == Phase.READ_BEFORE || phase == Phase.READ_AFTER;
    }

    /** The WORLD_RENDER transform of the frame being drawn. */
    void world(float[] mvp, double camX, double camY, double camZ) {
        if (!wantsHud()) return;
        this.worldMvp = mvp.clone();
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
    }

    /** Called after Blanket's HUD drawing with the viewport it drew into and the GUI scale. */
    void hud(int vx, int vy, int vw, int vh, double guiScale, Pixels pixels) {
        if (phase == Phase.READ_BEFORE) {
            for (Spec.Probe p : spec.probes) readings.add(read(p, BEFORE, vx, vy, vw, vh, guiScale, pixels));
            enter(Phase.PRESS);
        } else if (phase == Phase.READ_AFTER) {
            Reading r = read(spec.key.probe, AFTER, vx, vy, vw, vh, guiScale, pixels);
            // "changed": the probe no longer matches; a point off screen proves nothing
            keyPass = r.rgb != null && !r.pass;
            readings.add(new Reading(r.name, r.round, r.x, r.y, r.rgb, keyPass));
            finish();
        }
    }

    private Reading read(Spec.Probe p, String round, int vx, int vy, int vw, int vh, double guiScale, Pixels pixels) {
        int x, glY;
        if (p.gui != null) {
            x = vx + (int) Math.floor(p.gui[0] * guiScale);
            glY = vy + vh - 1 - (int) Math.floor(p.gui[1] * guiScale);
        } else {
            if (worldMvp == null) {
                game.log("probe " + p.name + ": no WORLD_RENDER in this frame");
                return new Reading(p.name, round, -1, -1, null, false);
            }
            int[] at = project(worldMvp, ox + p.world[0] - camX, oy + p.world[1] - camY, oz + p.world[2] - camZ, vx, vy, vw, vh);
            if (at == null) {
                game.log("probe " + p.name + ": the point is behind the camera");
                return new Reading(p.name, round, -1, -1, null, false);
            }
            x = at[0];
            glY = at[1];
        }
        if (x < vx || x >= vx + vw || glY < vy || glY >= vy + vh) {
            game.log("probe " + p.name + ": pixel " + x + "," + glY + " is outside the viewport");
            return new Reading(p.name, round, -1, -1, null, false);
        }
        int rgb = pixels.rgb(x, glY) & 0xFFFFFF;
        return new Reading(p.name, round, x - vx, vy + vh - 1 - glY, hex(rgb), p.matches(rgb));
    }

    /**
     * The GL window pixel of a camera-relative point under a column-major transform, or null when the point is on or
     * behind the camera plane.
     */
    static int[] project(float[] m, double x, double y, double z, int vx, int vy, int vw, int vh) {
        double cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        double cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        double cw = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (cw <= 1e-6) return null;
        double nx = cx / cw, ny = cy / cw;
        return new int[] {vx + (int) Math.floor((nx * 0.5 + 0.5) * vw), vy + (int) Math.floor((ny * 0.5 + 0.5) * vh)};
    }

    private void enter(Phase next) {
        phase = next;
        phaseTick = ticksEnd;
        if (next == Phase.READ_BEFORE || next == Phase.READ_AFTER) worldMvp = null;
    }

    /** The result is written at the next CLIENT_TICK_END. */
    private void finish() {
        enter(Phase.WRITE);
    }

    private void write() {
        phase = Phase.DONE;
        boolean pass = pass();
        try {
            game.write(result());
            game.log("result written: " + (pass ? "pass" : "FAIL"));
        } catch (java.io.IOException | RuntimeException e) {
            game.log("cannot write the result: " + e);
        }
        game.quit();
    }

    private boolean allPass() {
        if (readings.isEmpty()) return false;
        for (Reading r : readings) if (!r.pass) return false;
        return true;
    }

    private boolean keyOk() {
        return spec.key == null || keyPressed && keyPass;
    }

    boolean pass() {
        return error == null && allPass() && readings.size() >= spec.probes.size() && keyOk();
    }

    /** {@code testkit-result.json} (M3 plan section 3.3). */
    String result() {
        StringBuilder b = new StringBuilder("{\n");
        b.append("  \"schema\": 1,\n");
        b.append("  \"mod\": ").append(Json.quote(mod)).append(",\n");
        b.append("  \"ticksStart\": ").append(ticksStart).append(",\n");
        b.append("  \"ticksEnd\": ").append(ticksEnd).append(",\n");
        b.append("  \"worldJoins\": ").append(worldJoins).append(",\n");
        b.append("  \"worldLeaves\": ").append(worldLeaves).append(",\n");
        b.append("  \"probes\": [");
        for (int k = 0; k < readings.size(); k++) {
            Reading r = readings.get(k);
            b.append(k == 0 ? "\n" : ",\n");
            b.append("    { \"name\": ").append(Json.quote(r.name)).append(", \"round\": ").append(Json.quote(r.round))
                    .append(", \"x\": ").append(r.x).append(", \"y\": ").append(r.y)
                    .append(", \"rgb\": ").append(r.rgb == null ? "null" : Json.quote(r.rgb))
                    .append(", \"pass\": ").append(r.pass).append(" }");
        }
        b.append(readings.isEmpty() ? "],\n" : "\n  ],\n");
        b.append("  \"key\": { \"pressed\": ").append(keyPressed).append(", \"pass\": ").append(error == null && keyOk()).append(" },\n");
        b.append("  \"listenerFailures\": ").append(game.listenerFailures()).append(",\n");
        b.append("  \"pass\": ").append(pass()).append("\n}\n");
        return b.toString();
    }

    static String hex(int rgb) {
        String h = Integer.toHexString(0x1000000 | (rgb & 0xFFFFFF)).toUpperCase(java.util.Locale.ROOT);
        return "#" + h.substring(1);
    }
}
