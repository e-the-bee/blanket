// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The run's verdict (M3 plan §3.3): pass only when {@code testkit-result.json} was written, says {@code pass}, every
 * probe and the key check pass, {@code ticksEnd >= 100} and {@code ticksStart == ticksEnd}, {@code worldJoins == 1},
 * {@code listenerFailures == 0}, and the game log has no Mixin error, no failed-listener line and no crash marker.
 */
public record Verdict(List<String> failures) {
    static final int MIN_TICKS = 100;
    static final Pattern MIXIN_ERROR = Pattern.compile(
            "InvalidInjectionException|InjectionError|MixinApplyError|MixinTransformerError");
    static final Pattern LISTENER_FAILED = Pattern.compile("listener for .* failed");
    /** run.py's crash markers. */
    static final Pattern CRASHED = Pattern.compile(
            "has crashed|Game crashed|#@!@#|Crash report saved|Exception in thread \"main\"");
    /** At most this many log lines are quoted per kind. */
    private static final int MAX_LINES = 5;

    public boolean pass() {
        return failures.isEmpty();
    }

    /**
     * @param result the parsed result file, or null when there is none
     * @param missing why there is no result (used only when {@code result} is null)
     * @param log the game's log lines
     */
    public static Verdict of(JsonObject result, String missing, List<String> log) {
        List<String> f = new ArrayList<>();
        if (result == null) {
            f.add("no testkit-result.json: " + missing);
        } else {
            checkResult(result, f);
        }
        scan(log, MIXIN_ERROR, "Mixin error in the log", f);
        scan(log, LISTENER_FAILED, "listener failure in the log", f);
        scan(log, CRASHED, "crash marker in the log", f);
        return new Verdict(List.copyOf(f));
    }

    private static void checkResult(JsonObject r, List<String> f) {
        if (!isTrue(r, "pass")) {
            f.add("result: pass is " + r.get("pass"));
        }
        if (r.has("probes") && r.get("probes").isJsonArray()) {
            for (JsonElement e : r.getAsJsonArray("probes")) {
                JsonObject p = e.getAsJsonObject();
                if (!isTrue(p, "pass")) {
                    f.add("probe " + p.get("name") + " round " + p.get("round") + " failed at (" + p.get("x") + ", "
                            + p.get("y") + "): read " + p.get("rgb"));
                }
            }
        } else {
            f.add("result: no probes array");
        }
        if (r.has("key") && r.get("key").isJsonObject() && !isTrue(r.getAsJsonObject("key"), "pass")) {
            f.add("key check failed: " + r.get("key"));
        }
        Long start = number(r, "ticksStart", f);
        Long end = number(r, "ticksEnd", f);
        if (end != null && end < MIN_TICKS) {
            f.add("ticksEnd " + end + " < " + MIN_TICKS);
        }
        if (start != null && end != null && !start.equals(end)) {
            f.add("ticksStart " + start + " != ticksEnd " + end);
        }
        Long joins = number(r, "worldJoins", f);
        if (joins != null && joins != 1) {
            f.add("worldJoins " + joins + " != 1");
        }
        Long failures = number(r, "listenerFailures", f);
        if (failures != null && failures != 0) {
            f.add("listenerFailures " + failures + " != 0");
        }
    }

    private static boolean isTrue(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static Long number(JsonObject o, String key, List<String> f) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            f.add("result: " + key + " missing or not a number");
            return null;
        }
        return e.getAsLong();
    }

    private static void scan(List<String> log, Pattern p, String what, List<String> f) {
        int n = 0;
        for (String line : log) {
            if (p.matcher(line).find()) {
                if (n < MAX_LINES) {
                    f.add(what + ": " + (line.length() > 300 ? line.substring(0, 300) : line));
                }
                n++;
            }
        }
        if (n > MAX_LINES) {
            f.add(what + ": " + (n - MAX_LINES) + " more line(s)");
        }
    }
}
