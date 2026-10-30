// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code hooks.json}: named injection points inside Minecraft (binding-format.md section 7). */
public final class Hooks {
    private final Map<String, List<Rule>> hooks;

    private Hooks(Map<String, List<Rule>> hooks) {
        this.hooks = hooks;
    }

    public static Hooks parse(JsonElement root, String file) {
        JsonObject o = Json.root(root, file, "hooks");
        JsonObject h = Json.obj(o.get("hooks"), file, "$.hooks");
        Map<String, List<Rule>> hooks = new LinkedHashMap<String, List<Rule>>();
        for (String id : h.keySet()) {
            String at = "$.hooks." + id;
            JsonArray rules = Json.arr(h.get(id), file, at);
            if (rules.size() == 0) {
                throw new BindingException(file, at, "rule list is empty");
            }
            List<Rule> list = new ArrayList<Rule>();
            for (int i = 0; i < rules.size(); i++) {
                list.add(Rule.parse(rules.get(i), file, at + "[" + i + "]"));
            }
            hooks.put(id, Collections.unmodifiableList(list));
        }
        return new Hooks(Collections.unmodifiableMap(hooks));
    }

    public Map<String, List<Rule>> hooks() {
        return hooks;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schema", Json.SCHEMA);
        JsonObject h = new JsonObject();
        for (Map.Entry<String, List<Rule>> e : hooks.entrySet()) {
            JsonArray a = new JsonArray();
            for (Rule r : e.getValue()) {
                a.add(r.toJson());
            }
            h.add(e.getKey(), a);
        }
        o.add("hooks", h);
        return o;
    }

    /** One rule of a hook: where it applies, the method and the point inside it. */
    public static final class Rule {
        private final Selector when;
        private final String method;
        private final At at;
        private final String note;

        private Rule(Selector when, String method, At at, String note) {
            this.when = when;
            this.method = method;
            this.at = at;
            this.note = note;
        }

        static Rule parse(JsonElement e, String file, String path) {
            JsonObject o = Json.obj(e, file, path);
            Json.checkKeys(o, file, path, new String[] {"when", "method", "at", "note"});
            if (!o.has("when")) {
                throw new BindingException(file, path, "missing \"when\"");
            }
            Selector when = Selector.parse(o.get("when"), file, path + ".when");
            String method = Json.nonEmpty(o, "method", file, path);
            if (!o.has("at")) {
                throw new BindingException(file, path, "missing \"at\"");
            }
            At at = At.parse(o.get("at"), file, path + ".at");
            return new Rule(when, method, at, Json.optStr(o, "note", file, path));
        }

        public Selector when() {
            return when;
        }

        public String method() {
            return method;
        }

        public At at() {
            return at;
        }

        public String note() {
            return note;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("when", TypeBinding.selectorJson(when));
            o.addProperty("method", method);
            o.add("at", at.toJson());
            if (note != null) {
                o.addProperty("note", note);
            }
            return o;
        }
    }

    /**
     * Where inside the method the hook fires: {@code HEAD}, {@code TAIL}, {@code RETURN}, an {@code invoke} call
     * site (with a shift and an optional ordinal), or an {@code invokeString} call site whose string argument is
     * a constant.
     */
    public static final class At {
        /** The kind of injection point. */
        public enum Kind { HEAD, TAIL, RETURN, INVOKE, INVOKE_STRING }

        private static final Set<String> SHIFTS = Json.set("BEFORE", "AFTER");

        private final Kind kind;
        private final String method;
        private final String shift;
        private final Integer ordinal;
        private final String ldc;

        private At(Kind kind, String method, String shift, Integer ordinal, String ldc) {
            this.kind = kind;
            this.method = method;
            this.shift = shift;
            this.ordinal = ordinal;
            this.ldc = ldc;
        }

        static At parse(JsonElement e, String file, String path) {
            if (e.isJsonPrimitive()) {
                String s = Json.str(e, file, path);
                switch (s) {
                    case "HEAD":
                        return new At(Kind.HEAD, null, null, null, null);
                    case "TAIL":
                        return new At(Kind.TAIL, null, null, null, null);
                    case "RETURN":
                        return new At(Kind.RETURN, null, null, null, null);
                    default:
                        throw new BindingException(file, path, "'" + s + "' is not HEAD, TAIL, RETURN or an object");
                }
            }
            JsonObject o = Json.obj(e, file, path);
            if (o.has("invokeString")) {
                Json.checkKeys(o, file, path, new String[] {"invokeString", "ldc"});
                return new At(Kind.INVOKE_STRING, Json.nonEmpty(o, "invokeString", file, path), null, null,
                        Json.str(o, "ldc", file, path));
            }
            Json.checkKeys(o, file, path, new String[] {"invoke", "shift", "ordinal"});
            String method = Json.nonEmpty(o, "invoke", file, path);
            String shift = Json.oneOf(o.get("shift"), SHIFTS, file, path + ".shift");
            Integer ordinal = null;
            if (o.has("ordinal")) {
                int n = Json.integer(o.get("ordinal"), file, path + ".ordinal");
                if (n < 0) {
                    throw new BindingException(file, path + ".ordinal", "ordinal must not be negative");
                }
                ordinal = n;
            }
            return new At(Kind.INVOKE, method, shift, ordinal, null);
        }

        public Kind kind() {
            return kind;
        }

        /** The method reference of INVOKE or INVOKE_STRING; null otherwise. */
        public String method() {
            return method;
        }

        public String shift() {
            return shift;
        }

        public Integer ordinal() {
            return ordinal;
        }

        public String ldc() {
            return ldc;
        }

        JsonElement toJson() {
            switch (kind) {
                case HEAD:
                case TAIL:
                case RETURN:
                    return new JsonPrimitive(kind.name());
                case INVOKE_STRING: {
                    JsonObject o = new JsonObject();
                    o.addProperty("invokeString", method);
                    o.addProperty("ldc", ldc);
                    return o;
                }
                default: {
                    JsonObject o = new JsonObject();
                    o.addProperty("invoke", method);
                    o.addProperty("shift", shift);
                    if (ordinal != null) {
                        o.addProperty("ordinal", ordinal);
                    }
                    return o;
                }
            }
        }
    }
}
