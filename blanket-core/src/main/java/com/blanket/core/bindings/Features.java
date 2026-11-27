// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.blanket.core.resolve.TargetSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code features.json}: per Feature constant, where it is available (binding-format.md section 6). */
public final class Features {
    private static final Features NONE = new Features(Collections.emptyMap());

    private final Map<String, List<Selector>> available;

    private Features(Map<String, List<Selector>> available) {
        this.available = available;
    }

    /** No features: the result of a bindings folder without features.json. */
    public static Features empty() {
        return NONE;
    }

    public static Features parse(JsonElement root, String file) {
        JsonObject o = Json.root(root, file, "features");
        JsonObject all = Json.obj(o.get("features"), file, "$.features");
        Map<String, List<Selector>> out = new LinkedHashMap<String, List<Selector>>();
        for (String name : all.keySet()) {
            String at = "$.features." + name;
            JsonObject f = Json.obj(all.get(name), file, at);
            Json.checkKeys(f, file, at, new String[] {"available", "note"});
            if (!f.has("available")) {
                throw new BindingException(file, at, "missing \"available\"");
            }
            JsonArray rules = Json.arr(f.get("available"), file, at + ".available");
            List<Selector> when = new ArrayList<Selector>();
            for (int i = 0; i < rules.size(); i++) {
                String ruleAt = at + ".available[" + i + "]";
                JsonObject rule = Json.obj(rules.get(i), file, ruleAt);
                Json.checkKeys(rule, file, ruleAt, new String[] {"when"});
                if (!rule.has("when")) {
                    throw new BindingException(file, ruleAt, "missing \"when\"");
                }
                when.add(Selector.parse(rule.get("when"), file, ruleAt + ".when"));
            }
            if (f.has("note")) {
                Json.str(f.get("note"), file, at + ".note");
            }
            out.put(name, Collections.unmodifiableList(when));
        }
        return new Features(Collections.unmodifiableMap(out));
    }

    /** Whether any {@code available} rule of {@code feature} matches {@code target}. */
    public boolean available(String feature, TargetSpec target) {
        List<Selector> rules = available.get(feature);
        if (rules == null) {
            throw new IllegalArgumentException("unknown feature '" + feature + "' (features.json has: " + Json.sorted(names()) + ")");
        }
        Version v = Version.parse(target.mc());
        for (Selector s : rules) {
            if (s.matches(target.loader(), v)) return true;
        }
        return false;
    }

    /**
     * Where {@code feature} is available, as text for messages: each {@code available} rule as its loaders and
     * {@code mc} range ({@code mc 1.9..}, {@code fabric, forge mc 1.14..}), rules joined by {@code " or "}; a rule
     * without keys is {@code every target}. Null for a feature this file does not list.
     */
    public String range(String feature) {
        List<Selector> rules = available.get(feature);
        if (rules == null) return null;
        StringBuilder b = new StringBuilder();
        for (Selector s : rules) {
            if (b.length() > 0) b.append(" or ");
            StringBuilder one = new StringBuilder();
            if (s.loaders() != null) one.append(String.join(", ", s.loaders()));
            if (s.mc() != null) one.append(one.length() > 0 ? " " : "").append("mc ").append(s.mc());
            b.append(one.length() > 0 ? one : "every target");
        }
        return b.toString();
    }

    /** The Feature constants, in file order. */
    public Set<String> names() {
        return available.keySet();
    }
}
