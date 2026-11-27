// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blanket.runtime.Gl;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** testkit-probes.json parsing, the colour tests, the JSON reader and the GL pixel read. */
class SpecTest {

    static String withProbe(String probe) {
        return "{ \"schema\": 1, \"waitTicks\": 5, \"probes\": [ " + probe + " ] }";
    }

    static void rejects(String text, String message) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Spec.parse(text));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    @Test
    void thePlanExampleParses() {
        Spec s = Spec.parse(SessionTest.PROBES);
        assertEquals(100, s.waitTicks);
        assertEquals(2, s.probes.size());
        Spec.Probe hud = s.probes.get(0);
        assertEquals("hud", hud.name);
        assertArrayEquals(new double[] {30, 30}, hud.gui, 0);
        assertNull(hud.world);
        assertEquals(0xFF00FF, hud.rgb);
        assertEquals(8, hud.tolerance);
        Spec.Probe box = s.probes.get(1);
        assertArrayEquals(new double[] {0.5, 0.5, 3.5}, box.world, 0);
        assertTrue(box.dominantGreen);
        assertEquals(10, s.key.afterTicks);
        assertSame(hud, s.key.probe);
    }

    @Test
    void rgbMatchesWithinTheTolerancePerChannel() {
        Spec.Probe p = Spec.parse(withProbe("{ \"name\": \"p\", \"gui\": [1, 1], \"rgb\": \"#FF00FF\", \"tolerance\": 8 }")).probes.get(0);
        assertTrue(p.matches(0xFF00FF));
        assertTrue(p.matches(0xF708F7));
        assertFalse(p.matches(0xF600FF));
        assertFalse(p.matches(0xFF09FF));
        Spec.Probe exact = Spec.parse(withProbe("{ \"name\": \"p\", \"gui\": [1, 1], \"rgb\": \"#102030\" }")).probes.get(0);
        assertTrue(exact.matches(0x102030));
        assertFalse(exact.matches(0x102031));
    }

    @Test
    void dominantGreenNeedsFortyOverRedAndBlue() {
        Spec.Probe p = Spec.parse(withProbe("{ \"name\": \"p\", \"world\": [0, 0, 0], \"dominant\": \"green\" }")).probes.get(0);
        assertTrue(p.matches(0x00FF00));
        assertTrue(p.matches(0x285028));
        assertFalse(p.matches(0x295028));
        assertFalse(p.matches(0x285029));
        assertFalse(p.matches(0x808080));
    }

    @Test
    void mistakesAreRejected() {
        rejects("{ \"schema\": 2, \"waitTicks\": 5, \"probes\": [] }", "schema must be 1");
        rejects("{ \"schema\": 1, \"waitTicks\": 5, \"probes\": [] }", "probes is empty");
        rejects("{ \"schema\": 1, \"waitTicks\": 5.5, \"probes\": [] }", "waitTicks must be an integer");
        rejects("{ \"schema\": 1, \"waitTick\": 5, \"probes\": [] }", "unknown member \"waitTick\"");
        rejects(withProbe("{ \"name\": \"p\", \"gui\": [1, 1], \"world\": [1, 1, 1], \"rgb\": \"#FFFFFF\" }"), "exactly one of gui and world");
        rejects(withProbe("{ \"name\": \"p\", \"gui\": [1], \"rgb\": \"#FFFFFF\" }"), "gui must be an array of 2 numbers");
        rejects(withProbe("{ \"name\": \"p\", \"gui\": [1, 1], \"rgb\": \"FFFFFF\" }"), "rgb must be #RRGGBB");
        rejects(withProbe("{ \"name\": \"p\", \"gui\": [1, 1], \"dominant\": \"red\" }"), "dominant must be \"green\"");
        rejects(withProbe("{ \"name\": \"p\", \"gui\": [1, 1], \"rgb\": \"#FFFFFF\" }, { \"name\": \"p\", \"gui\": [2, 2], \"rgb\": \"#FFFFFF\" }"), "repeats");
        String probe = "{ \"name\": \"p\", \"gui\": [1, 1], \"rgb\": \"#FFFFFF\" }";
        rejects("{ \"schema\": 1, \"waitTicks\": 5, \"probes\": [ " + probe + " ], \"key\": { \"afterTicks\": 10, \"probe\": \"q\", \"expect\": \"changed\" } }",
                "key.probe 'q' is not a probe");
        rejects("{ \"schema\": 1, \"waitTicks\": 5, \"probes\": [ " + probe + " ], \"key\": { \"afterTicks\": 10, \"probe\": \"p\", \"expect\": \"same\" } }",
                "key.expect must be \"changed\"");
        rejects("{ \"schema\": 1, \"waitTicks\": 5, \"probes\": [ " + probe + " ] } x", "text after the value");
    }

    @Test
    void jsonReadsEveryKindAndQuotes() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse(" { \"a\": [1, -2.5e1, true, false, null], \"b\": \"q\\\"\\u0041\\n\" } ");
        assertEquals(Arrays.asList(1.0, -25.0, true, false, null), m.get("a"));
        assertEquals("q\"A\n", m.get("b"));
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Json.quote("a\"b\\c\n\u0001"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1, \"a\": 2}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"open"));
    }

    /** A Gl that records calls; readPixels writes RGBA (0x11, 0x22, 0x33, 0x44), the pixel-pack binding is {@code pack}. */
    static Gl recordingGl(final int pack, final List<String> calls) {
        return (Gl) Proxy.newProxyInstance(SpecTest.class.getClassLoader(), new Class<?>[] {Gl.class}, new InvocationHandler() {
            public Object invoke(Object proxy, Method m, Object[] a) {
                calls.add(m.getName() + (a == null ? "" : Arrays.toString(a.length == 7 ? Arrays.copyOf(a, 6) : a)));
                if (m.getName().equals("getInteger")) return pack;
                if (m.getName().equals("readPixels")) {
                    ByteBuffer b = (ByteBuffer) a[6];
                    b.put(0, (byte) 0x11).put(1, (byte) 0x22).put(2, (byte) 0x33).put(3, (byte) 0x44);
                }
                return null;
            }
        });
    }

    @Test
    void glPixelsReadsOnePixelAndUnbindsAPackBufferOnCoreProfiles() {
        List<String> calls = new ArrayList<String>();
        assertEquals(0x112233, new GlPixels().at(recordingGl(7, calls), true).rgb(4, 5));
        assertEquals(Arrays.asList("getInteger[35053]", "bindBuffer[35051, 0]", "readPixels[4, 5, 1, 1, 6408, 5121]", "bindBuffer[35051, 7]"), calls);

        calls.clear();
        assertEquals(0x112233, new GlPixels().at(recordingGl(7, calls), false).rgb(4, 5));
        assertEquals(Arrays.asList("readPixels[4, 5, 1, 1, 6408, 5121]"), calls, "legacy: no pack-buffer query");
    }
}
