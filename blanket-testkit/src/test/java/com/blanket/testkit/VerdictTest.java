// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The verdict rules of M3 plan §3.3, one case per way to fail plus the pass. */
class VerdictTest {
    private static final List<String> CLEAN_LOG = List.of("[main/INFO]: Setting user: Dev",
            "[Render thread/INFO]: [blanket] hudbox: started", "[Server thread/INFO]: Dev joined the game");

    private static JsonObject passing() {
        return Fixtures.json("result-pass.json");
    }

    private static void assertFailsWith(Verdict v, String fragment) {
        assertFalse(v.pass(), "expected a failure");
        assertTrue(v.failures().stream().anyMatch(f -> f.contains(fragment)),
                "no failure mentions '" + fragment + "': " + v.failures());
    }

    @Test
    void passes() {
        Verdict v = Verdict.of(passing(), "unused", CLEAN_LOG);
        assertTrue(v.pass(), v.failures().toString());
    }

    @Test
    void missingResultFails() {
        Verdict v = Verdict.of(null, "the game wrote none within 360 s", CLEAN_LOG);
        assertEquals(List.of("no testkit-result.json: the game wrote none within 360 s"), v.failures());
    }

    @Test
    void failedProbeFails() {
        JsonObject r = passing();
        r.getAsJsonArray("probes").get(1).getAsJsonObject().addProperty("pass", false);
        assertFailsWith(Verdict.of(r, "", CLEAN_LOG), "probe \"box\"");
    }

    @Test
    void mixinErrorLineFails() {
        List<String> log = List.of("[main/ERROR]: Mixin apply failed blanket.mixins.json:HudRender$hud_render -> x",
                "org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Critical injection failure");
        assertFailsWith(Verdict.of(passing(), "", log), "Mixin error in the log: org.spongepowered");
    }

    @Test
    void everyMixinErrorKindIsCaught() {
        for (String kind : List.of("InvalidInjectionException", "InjectionError", "MixinApplyError",
                "MixinTransformerError")) {
            assertFailsWith(Verdict.of(passing(), "", List.of("x." + kind + ": y")), "Mixin error");
        }
    }

    @Test
    void listenerFailureLineFails() {
        List<String> log = List.of("[blanket] hudbox: listener for CLIENT_TICK_END failed",
                "java.lang.IllegalStateException: boom");
        assertFailsWith(Verdict.of(passing(), "", log), "listener failure in the log");
    }

    @Test
    void listenerFailureCountFails() {
        JsonObject r = passing();
        r.addProperty("listenerFailures", 2);
        assertFailsWith(Verdict.of(r, "", CLEAN_LOG), "listenerFailures 2 != 0");
    }

    @Test
    void tickMismatchFails() {
        JsonObject r = passing();
        r.addProperty("ticksStart", 141);
        assertFailsWith(Verdict.of(r, "", CLEAN_LOG), "ticksStart 141 != ticksEnd 140");
    }

    @Test
    void tooFewTicksFail() {
        JsonObject r = passing();
        r.addProperty("ticksStart", 99);
        r.addProperty("ticksEnd", 99);
        assertFailsWith(Verdict.of(r, "", CLEAN_LOG), "ticksEnd 99 < 100");
    }

    @Test
    void secondWorldJoinFails() {
        JsonObject r = passing();
        r.addProperty("worldJoins", 2);
        assertFailsWith(Verdict.of(r, "", CLEAN_LOG), "worldJoins 2 != 1");
    }

    @Test
    void resultPassFalseFails() {
        JsonObject r = passing();
        r.addProperty("pass", false);
        assertFailsWith(Verdict.of(r, "", CLEAN_LOG), "result: pass is false");
    }

    @Test
    void keyCheckFails() {
        JsonObject r = passing();
        r.getAsJsonObject("key").addProperty("pass", false);
        assertFailsWith(Verdict.of(r, "", CLEAN_LOG), "key check failed");
    }

    @Test
    void missingCountFails() {
        JsonObject r = passing();
        r.remove("worldJoins");
        assertFailsWith(Verdict.of(r, "", CLEAN_LOG), "worldJoins missing or not a number");
    }

    @Test
    void crashMarkerFails() {
        assertFailsWith(Verdict.of(passing(), "", List.of("---- Minecraft Crash Report ----",
                "#@!@# Game crashed! Crash report saved to: #@!@# crash-reports/x.txt")), "crash marker");
    }

    @Test
    void quotesAtMostFiveLinesPerKind() {
        List<String> log = java.util.Collections.nCopies(8, "MixinApplyError: z");
        Verdict v = Verdict.of(passing(), "", log);
        assertEquals(6, v.failures().size(), v.failures().toString());
        assertTrue(v.failures().get(5).endsWith("3 more line(s)"));
    }
}
