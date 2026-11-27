// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.testkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code testkit-probes.json} (M3 plan section 3.3): how long to wait after the anchor, the pixel probes, and the key
 * check. {@link #parse} rejects anything it does not understand, so a typo fails the run instead of passing it.
 */
final class Spec {
    /** A pixel probe: a GUI point or a world point, and the colour test. */
    static final class Probe {
        final String name;
        /** GUI-scaled x, y from the top left; null for a world probe. */
        final double[] gui;
        /** Offset x, y, z from the anchor origin; null for a GUI probe. */
        final double[] world;
        /** {@code 0xRRGGBB} with {@link #tolerance} per channel, or -1 for {@link #dominantGreen}. */
        final int rgb;
        final int tolerance;
        /** G exceeds R and B by at least {@link #DOMINANCE}. */
        final boolean dominantGreen;

        static final int DOMINANCE = 40;

        Probe(String name, double[] gui, double[] world, int rgb, int tolerance, boolean dominantGreen) {
            this.name = name;
            this.gui = gui;
            this.world = world;
            this.rgb = rgb;
            this.tolerance = tolerance;
            this.dominantGreen = dominantGreen;
        }

        boolean matches(int pixel) {
            int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
            if (dominantGreen) return g - r >= DOMINANCE && g - b >= DOMINANCE;
            return Math.abs(r - ((rgb >> 16) & 255)) <= tolerance
                    && Math.abs(g - ((rgb >> 8) & 255)) <= tolerance
                    && Math.abs(b - (rgb & 255)) <= tolerance;
        }
    }

    /** After the first round passes: press the first registered key, wait, re-read {@link #probe}; it must no longer match. */
    static final class KeyCheck {
        final int afterTicks;
        final Probe probe;

        KeyCheck(int afterTicks, Probe probe) {
            this.afterTicks = afterTicks;
            this.probe = probe;
        }
    }

    final int waitTicks;
    final List<Probe> probes;
    /** Null when the file has no {@code key}. */
    final KeyCheck key;

    private Spec(int waitTicks, List<Probe> probes, KeyCheck key) {
        this.waitTicks = waitTicks;
        this.probes = probes;
        this.key = key;
    }

    static Spec parse(String text) {
        Map<String, Object> root = object(Json.parse(text), "the file");
        known(root, "the file", "schema", "waitTicks", "anchor", "probes", "key");
        if (integer(root.get("schema"), "schema") != 1) throw new IllegalArgumentException("schema must be 1");
        int wait = integer(root.get("waitTicks"), "waitTicks");
        if (wait < 0) throw new IllegalArgumentException("waitTicks must not be negative");
        Object list = root.get("probes");
        if (!(list instanceof List)) throw new IllegalArgumentException("probes must be an array");
        List<Probe> probes = new ArrayList<Probe>();
        for (Object o : (List<?>) list) {
            Probe p = probe(object(o, "a probe"));
            for (Probe q : probes) if (q.name.equals(p.name)) throw new IllegalArgumentException("probe name '" + p.name + "' repeats");
            probes.add(p);
        }
        if (probes.isEmpty()) throw new IllegalArgumentException("probes is empty");
        KeyCheck key = null;
        if (root.get("key") != null) {
            Map<String, Object> k = object(root.get("key"), "key");
            known(k, "key", "afterTicks", "probe", "expect");
            int after = integer(k.get("afterTicks"), "key.afterTicks");
            if (after < 1) throw new IllegalArgumentException("key.afterTicks must be at least 1");
            String name = string(k.get("probe"), "key.probe");
            Probe target = null;
            for (Probe p : probes) if (p.name.equals(name)) target = p;
            if (target == null) throw new IllegalArgumentException("key.probe '" + name + "' is not a probe");
            if (!"changed".equals(string(k.get("expect"), "key.expect"))) throw new IllegalArgumentException("key.expect must be \"changed\"");
            key = new KeyCheck(after, target);
        }
        return new Spec(wait, Collections.unmodifiableList(probes), key);
    }

    private static Probe probe(Map<String, Object> o) {
        known(o, "a probe", "name", "gui", "world", "rgb", "tolerance", "dominant");
        String name = string(o.get("name"), "probe name");
        String at = "probe '" + name + "'";
        double[] gui = o.containsKey("gui") ? numbers(o.get("gui"), 2, at + " gui") : null;
        double[] world = o.containsKey("world") ? numbers(o.get("world"), 3, at + " world") : null;
        if ((gui == null) == (world == null)) throw new IllegalArgumentException(at + " needs exactly one of gui and world");
        if (o.containsKey("dominant")) {
            if (o.containsKey("rgb") || o.containsKey("tolerance")) throw new IllegalArgumentException(at + " has both dominant and rgb");
            if (!"green".equals(string(o.get("dominant"), at + " dominant"))) throw new IllegalArgumentException(at + " dominant must be \"green\"");
            return new Probe(name, gui, world, -1, 0, true);
        }
        String rgb = string(o.get("rgb"), at + " rgb");
        if (!rgb.matches("#[0-9A-Fa-f]{6}")) throw new IllegalArgumentException(at + " rgb must be #RRGGBB");
        int tolerance = o.containsKey("tolerance") ? integer(o.get("tolerance"), at + " tolerance") : 0;
        if (tolerance < 0) throw new IllegalArgumentException(at + " tolerance must not be negative");
        return new Probe(name, gui, world, Integer.parseInt(rgb.substring(1), 16), tolerance, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object o, String what) {
        if (!(o instanceof Map)) throw new IllegalArgumentException(what + " must be an object");
        return (Map<String, Object>) o;
    }

    private static void known(Map<String, Object> o, String what, String... keys) {
        outer:
        for (String k : o.keySet()) {
            for (String allowed : keys) if (allowed.equals(k)) continue outer;
            throw new IllegalArgumentException(what + " has an unknown member \"" + k + "\"");
        }
    }

    private static int integer(Object o, String what) {
        if (!(o instanceof Double)) throw new IllegalArgumentException(what + " must be a number");
        double d = (Double) o;
        if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) throw new IllegalArgumentException(what + " must be an integer");
        return (int) d;
    }

    private static String string(Object o, String what) {
        if (!(o instanceof String)) throw new IllegalArgumentException(what + " must be a string");
        return (String) o;
    }

    private static double[] numbers(Object o, int n, String what) {
        if (!(o instanceof List) || ((List<?>) o).size() != n) throw new IllegalArgumentException(what + " must be an array of " + n + " numbers");
        double[] out = new double[n];
        for (int k = 0; k < n; k++) {
            Object v = ((List<?>) o).get(k);
            if (!(v instanceof Double)) throw new IllegalArgumentException(what + " must be an array of " + n + " numbers");
            out[k] = (Double) v;
        }
        return out;
    }
}
