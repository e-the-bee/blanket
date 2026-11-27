// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The testkit run with a fake game and fake frames: probe evaluation, the key round and testkit-result.json. */
class SessionTest {
    static final String PROBES = "{ \"schema\": 1, \"waitTicks\": 100,\n"
            + "  \"anchor\": \"first CLIENT_TICK_END after WORLD_JOIN with a non-null player; origin = (floor(x), floor(y), floor(z))\",\n"
            + "  \"probes\": [\n"
            + "    { \"name\": \"hud\", \"gui\": [30, 30], \"rgb\": \"#FF00FF\", \"tolerance\": 8 },\n"
            + "    { \"name\": \"box\", \"world\": [0.5, 0.5, 3.5], \"dominant\": \"green\" } ],\n"
            + "  \"key\": { \"afterTicks\": 10, \"probe\": \"hud\", \"expect\": \"changed\" } }";

    /** Perspective-like: clip = (x, y, z, z), so a point straight ahead on +z lands in the middle. */
    static final float[] MVP = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0};
    static final int W = 800, H = 600;
    static final double SCALE = 2;

    static final class FakeGame implements Session.Game {
        double[] player = {10.7, 64.0, -3.2};
        boolean hasKey = true;
        int presses, quits, failures;
        String written;
        final List<String> log = new ArrayList<String>();

        public double[] player() { return player; }
        public boolean pressFirstKey() { if (hasKey) presses++; return hasKey; }
        public int listenerFailures() { return failures; }
        public void write(String json) { written = json; }
        public void quit() { quits++; }
        public void log(String line) { log.add(line); }
    }

    /** A frame: magenta at the HUD probe's pixel while the rectangle is visible, green at the box, grey elsewhere. */
    static final class Frame implements Session.Pixels {
        boolean rectangle = true;
        int[] box;
        final List<int[]> reads = new ArrayList<int[]>();

        public int rgb(int x, int y) {
            reads.add(new int[] {x, y});
            if (rectangle && x == 60 && y == H - 1 - 60) return 0xFA04F7;
            if (box != null && x == box[0] && y == box[1]) return 0x10C020;
            return 0x808080;
        }
    }

    final FakeGame game = new FakeGame();
    final Frame frame = new Frame();
    final double camX = 10.7, camY = 65.62, camZ = -3.2;

    Session session(String probes) {
        return new Session(Spec.parse(probes), null, "hudbox", game);
    }

    void ticks(Session s, int n) {
        for (int k = 0; k < n; k++) {
            s.tickStart();
            s.tickEnd();
        }
    }

    void frame(Session s) {
        s.world(MVP, camX, camY, camZ);
        if (s.wantsHud()) s.hud(0, 0, W, H, SCALE, frame);
    }

    /** Join, anchor at the first tick end with a player, then 100 ticks: the next frame reads the probes. */
    Session joinedAndWaited(String probes) {
        Session s = session(probes);
        ticks(s, 5);
        s.tickStart();
        s.worldJoin();
        s.tickEnd();                      // tick 6: anchor
        ticks(s, 99);
        assertFalse(s.wantsHud(), "99 ticks after the anchor");
        ticks(s, 1);
        assertTrue(s.wantsHud(), "100 ticks after the anchor");
        // the box point: origin (10, 64, -4) + (0.5, 0.5, 3.5), camera-relative
        frame.box = Session.project(MVP, 10.5 - camX, 64.5 - camY, -0.5 - camZ, 0, 0, W, H);
        return s;
    }

    @Test
    void aPassingRunPressesTheKeyAndWritesTheResultAtATickEnd() {
        Session s = joinedAndWaited(PROBES);
        frame(s);
        assertFalse(s.wantsHud(), "one frame per round");
        ticks(s, 1);                      // PRESS
        assertEquals(1, game.presses);
        frame.rectangle = false;          // the mod toggles on the next tick end
        ticks(s, 9);
        frame(s);
        assertFalse(s.wantsHud(), "9 ticks after the press");
        ticks(s, 1);
        assertTrue(s.wantsHud(), "10 ticks after the press");
        frame(s);
        assertNull(game.written, "written at a tick end, not in a frame");
        s.tickStart();
        s.tickEnd();
        assertEquals(1, game.quits);
        int ticks = 5 + 1 + 100 + 1 + 10 + 1;
        assertEquals("{\n"
                + "  \"schema\": 1,\n"
                + "  \"mod\": \"hudbox\",\n"
                + "  \"ticksStart\": " + ticks + ",\n"
                + "  \"ticksEnd\": " + ticks + ",\n"
                + "  \"worldJoins\": 1,\n"
                + "  \"worldLeaves\": 0,\n"
                + "  \"probes\": [\n"
                + "    { \"name\": \"hud\", \"round\": \"before-key\", \"x\": 60, \"y\": 60, \"rgb\": \"#FA04F7\", \"pass\": true },\n"
                + "    { \"name\": \"box\", \"round\": \"before-key\", \"x\": " + frame.box[0] + ", \"y\": " + (H - 1 - frame.box[1])
                + ", \"rgb\": \"#10C020\", \"pass\": true },\n"
                + "    { \"name\": \"hud\", \"round\": \"after-key\", \"x\": 60, \"y\": 60, \"rgb\": \"#808080\", \"pass\": true }\n"
                + "  ],\n"
                + "  \"key\": { \"pressed\": true, \"pass\": true },\n"
                + "  \"listenerFailures\": 0,\n"
                + "  \"pass\": true\n"
                + "}\n", game.written);
        // nothing after the result
        ticks(s, 50);
        frame(s);
        assertEquals(1, game.quits);
    }

    @Test
    void theBoxPointProjectsInFrontOfTheCamera() {
        joinedAndWaited(PROBES);
        // relative point (-0.2, -1.12, 2.7): ndc (-0.074, -0.415)
        assertArrayEquals(new int[] {370, 175}, frame.box);
    }

    @Test
    void anUnchangedProbeAfterTheKeyFails() {
        Session s = joinedAndWaited(PROBES);
        frame(s);
        ticks(s, 11);
        frame(s);
        ticks(s, 1);
        assertTrue(game.written.contains("\"round\": \"after-key\", \"x\": 60, \"y\": 60, \"rgb\": \"#FA04F7\", \"pass\": false"), game.written);
        assertTrue(game.written.contains("\"key\": { \"pressed\": true, \"pass\": false }"), game.written);
        assertTrue(game.written.endsWith("\"pass\": false\n}\n"), game.written);
    }

    @Test
    void aFailedFirstRoundPressesNothing() {
        Session s = joinedAndWaited(PROBES);
        frame.rectangle = false;
        frame(s);
        ticks(s, 1);
        assertEquals(0, game.presses);
        assertEquals(0, game.quits, "the result waits for the next tick end");
        ticks(s, 1);
        assertEquals(1, game.quits);
        assertTrue(game.written.contains("\"name\": \"hud\", \"round\": \"before-key\", \"x\": 60, \"y\": 60, \"rgb\": \"#808080\", \"pass\": false"), game.written);
        assertTrue(game.written.contains("\"key\": { \"pressed\": false, \"pass\": false }"), game.written);
        assertFalse(game.written.contains("after-key"), game.written);
    }

    @Test
    void noRegisteredKeyFailsTheKeyCheck() {
        game.hasKey = false;
        Session s = joinedAndWaited(PROBES);
        frame(s);
        ticks(s, 2);
        assertEquals(1, game.quits);
        assertTrue(game.written.contains("\"key\": { \"pressed\": false, \"pass\": false }"), game.written);
        assertFalse(s.pass());
    }

    @Test
    void aWorldProbeWithoutTheFramesWorldRenderFails() {
        Session s = joinedAndWaited(PROBES);
        s.hud(0, 0, W, H, SCALE, frame);   // no world() in this frame
        assertTrue(game.log.contains("probe box: no WORLD_RENDER in this frame"), game.log.toString());
        ticks(s, 2);
        assertTrue(game.written.contains("{ \"name\": \"box\", \"round\": \"before-key\", \"x\": -1, \"y\": -1, \"rgb\": null, \"pass\": false }"), game.written);
    }

    @Test
    void aPointBehindTheCameraIsNotRead() {
        String behind = PROBES.replace("[0.5, 0.5, 3.5]", "[0.5, 0.5, -3.5]");
        Session s = joinedAndWaited(behind);
        frame(s);
        assertEquals(1, frame.reads.size(), "only the HUD pixel is read");
        ticks(s, 2);
        assertTrue(game.written.contains("\"name\": \"box\", \"round\": \"before-key\", \"x\": -1, \"y\": -1, \"rgb\": null, \"pass\": false"), game.written);
    }

    @Test
    void theAnchorWaitsForAPlayerAfterTheJoin() {
        Session s = session(PROBES);
        game.player = null;
        s.worldJoin();
        ticks(s, 300);
        assertFalse(s.wantsHud(), "no player, no anchor");
        game.player = new double[] {0, 0, 0};
        ticks(s, 101);
        assertTrue(s.wantsHud());
    }

    @Test
    void noHudFrameForTwoHundredTicksFailsTheRound() {
        Session s = joinedAndWaited(PROBES);
        ticks(s, Session.FRAME_WAIT_TICKS);
        ticks(s, 1);
        assertEquals(1, game.quits);
        assertTrue(game.written.contains("\"name\": \"hud\", \"round\": \"before-key\", \"x\": -1, \"y\": -1, \"rgb\": null, \"pass\": false"), game.written);
    }

    @Test
    void listenerFailuresAndLeavesAreReported() {
        Session s = joinedAndWaited(PROBES);
        s.worldLeave();
        game.failures = 3;
        frame.rectangle = false;
        frame(s);
        ticks(s, 2);
        assertTrue(game.written.contains("\"worldLeaves\": 1,"), game.written);
        assertTrue(game.written.contains("\"listenerFailures\": 3,"), game.written);
    }

    @Test
    void withoutAKeyCheckTheFirstRoundDecides() {
        String noKey = PROBES.substring(0, PROBES.indexOf(",\n  \"key\"")) + " }";
        Session s = joinedAndWaited(noKey);
        frame(s);
        ticks(s, 2);
        assertEquals(0, game.presses);
        assertTrue(game.written.contains("\"key\": { \"pressed\": false, \"pass\": true }"), game.written);
        assertTrue(s.pass());
    }

    @Test
    void anUnreadableProbesFileWritesAFailedResultAtTheFirstTickEnd() {
        Session s = new Session(null, "probes.json: JSON: expected a value at offset 0", "hudbox", game);
        assertFalse(s.wantsHud());
        s.tickStart();
        s.tickEnd();
        assertEquals(1, game.quits);
        assertTrue(game.written.contains("\"probes\": [],"), game.written);
        assertTrue(game.written.endsWith("\"pass\": false\n}\n"), game.written);
    }

    @Test
    void projectionMapsNdcToTheViewport() {
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        assertArrayEquals(new int[] {75, 25}, Session.project(identity, 0.5, -0.5, 0, 0, 0, 100, 100));
        assertArrayEquals(new int[] {85, 45}, Session.project(identity, 0.5, -0.5, 0, 10, 20, 100, 100));
        // GL perspective: w = -z, the camera looks down -z
        float[] gl = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, -1, 0, 0, 0, 0};
        assertArrayEquals(new int[] {75, 50}, Session.project(gl, 1, 0, -2, 0, 0, 100, 100));
        assertNull(Session.project(gl, 1, 0, 2, 0, 0, 100, 100));
    }

    @Test
    void hexIsUpperCaseSixDigits() {
        assertEquals("#00FF0A", Session.hex(0x00FF0A));
        assertEquals("#000000", Session.hex(0xFF000000));
    }
}
