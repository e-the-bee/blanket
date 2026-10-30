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

/** {@code events/<Event>.json}: the recipes that deliver one event on each target (binding-format.md section 7). */
public final class EventBinding {
    private static final Set<String> BUSES = Json.set("game", "mod", "fml");
    private static final Set<String> SOURCES = Json.set("arg", "this", "getter", "adapter", "const");

    private final String event;
    private final List<Recipe> recipes;

    private EventBinding(String event, List<Recipe> recipes) {
        this.event = event;
        this.recipes = recipes;
    }

    public static EventBinding parse(JsonElement root, String file) {
        JsonObject o = Json.root(root, file, "event", "recipes");
        String event = Json.nonEmpty(o, "event", file, "$");
        JsonArray arr = Json.arr(o.get("recipes"), file, "$.recipes");
        if (arr.size() == 0) {
            throw new BindingException(file, "$.recipes", "an event needs at least one recipe");
        }
        List<Recipe> recipes = new ArrayList<Recipe>();
        for (int i = 0; i < arr.size(); i++) {
            recipes.add(Recipe.parse(arr.get(i), file, "$.recipes[" + i + "]"));
        }
        return new EventBinding(event, Collections.unmodifiableList(recipes));
    }

    public String event() {
        return event;
    }

    public List<Recipe> recipes() {
        return recipes;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schema", Json.SCHEMA);
        o.addProperty("event", event);
        JsonArray a = new JsonArray();
        for (Recipe r : recipes) {
            a.add(r.toJson());
        }
        o.add("recipes", a);
        return o;
    }

    /** One recipe: a Mixin hook or a native loader event, with its payload. */
    public static final class Recipe {
        private final Selector when;
        private final String hook;
        private final String nativeEvent;
        private final String bus;
        private final JsonObject filter;
        private final Map<String, Source> payload;

        private Recipe(Selector when, String hook, String nativeEvent, String bus, JsonObject filter,
                       Map<String, Source> payload) {
            this.when = when;
            this.hook = hook;
            this.nativeEvent = nativeEvent;
            this.bus = bus;
            this.filter = filter;
            this.payload = payload;
        }

        static Recipe parse(JsonElement e, String file, String path) {
            JsonObject o = Json.obj(e, file, path);
            Json.checkKeys(o, file, path, new String[] {"when", "mixin", "native"});
            if (!o.has("when")) {
                throw new BindingException(file, path, "missing \"when\"");
            }
            Selector when = Selector.parse(o.get("when"), file, path + ".when");
            if (o.has("mixin") == o.has("native")) {
                throw new BindingException(file, path, "a recipe has exactly one of \"mixin\" and \"native\"");
            }
            if (o.has("mixin")) {
                String at = path + ".mixin";
                JsonObject m = Json.obj(o.get("mixin"), file, at);
                Json.checkKeys(m, file, at, new String[] {"hook", "payload"});
                return new Recipe(when, Json.nonEmpty(m, "hook", file, at), null, null, null,
                        payload(m, file, at));
            }
            String at = path + ".native";
            JsonObject n = Json.obj(o.get("native"), file, at);
            Json.checkKeys(n, file, at, new String[] {"event", "bus", "filter", "payload"});
            String bus = Json.oneOf(n.get("bus"), BUSES, file, at + ".bus");
            JsonObject filter = null;
            if (n.has("filter")) {
                filter = Json.obj(n.get("filter"), file, at + ".filter");
            }
            return new Recipe(when, null, Json.nonEmpty(n, "event", file, at), bus, filter, payload(n, file, at));
        }

        private static Map<String, Source> payload(JsonObject o, String file, String at) {
            Map<String, Source> out = new LinkedHashMap<String, Source>();
            if (o.has("payload")) {
                JsonObject p = Json.obj(o.get("payload"), file, at + ".payload");
                for (String name : p.keySet()) {
                    out.put(name, Source.parse(p.get(name), file, at + ".payload." + name));
                }
            }
            return Collections.unmodifiableMap(out);
        }

        public Selector when() {
            return when;
        }

        /** The hook id of a Mixin recipe, or null. */
        public String hook() {
            return hook;
        }

        /** The loader event class of a native recipe, or null. */
        public String nativeEvent() {
            return nativeEvent;
        }

        /** The bus of a native recipe ({@code game}, {@code mod} or {@code fml}), or null. */
        public String bus() {
            return bus;
        }

        /** The optional filter object of a native recipe, or null. */
        public JsonObject filter() {
            return filter;
        }

        public Map<String, Source> payload() {
            return payload;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("when", TypeBinding.selectorJson(when));
            JsonObject body = new JsonObject();
            if (hook != null) {
                body.addProperty("hook", hook);
            } else {
                body.addProperty("event", nativeEvent);
                body.addProperty("bus", bus);
                if (filter != null) {
                    body.add("filter", filter);
                }
            }
            if (!payload.isEmpty()) {
                JsonObject p = new JsonObject();
                for (Map.Entry<String, Source> e : payload.entrySet()) {
                    p.add(e.getKey(), e.getValue().toJson());
                }
                body.add("payload", p);
            }
            o.add(hook != null ? "mixin" : "native", body);
            return o;
        }
    }

    /** A payload source: an argument, a field, a getter, an adapter or a constant (binding-format.md section 7). */
    public static final class Source {
        private final String kind;
        private final JsonElement value;

        private Source(String kind, JsonElement value) {
            this.kind = kind;
            this.value = value;
        }

        static Source parse(JsonElement e, String file, String path) {
            JsonObject o = Json.obj(e, file, path);
            if (o.size() != 1) {
                throw new BindingException(file, path, "a payload source has exactly one key (" + SOURCES + "), found " + o.size());
            }
            String key = o.keySet().iterator().next();
            if (!SOURCES.contains(key)) {
                throw new BindingException(file, path + "." + key, "unknown payload source '" + key + "' (expected one of " + SOURCES + ")");
            }
            JsonElement v = o.get(key);
            String at = path + "." + key;
            switch (key) {
                case "arg": {
                    int n = Json.integer(v, file, at);
                    if (n < 0) {
                        throw new BindingException(file, at, "argument index must not be negative");
                    }
                    return new Source(key, new JsonPrimitive(n));
                }
                case "const":
                    if (!v.isJsonPrimitive()) {
                        throw new BindingException(file, at, "const must be true, false, an integer or a string");
                    }
                    return new Source(key, v);
                default:
                    return new Source(key, new JsonPrimitive(Json.nonEmpty(o, key, file, path)));
            }
        }

        /** The source kind: arg, this, getter, adapter or const. */
        public String kind() {
            return kind;
        }

        public JsonElement value() {
            return value;
        }

        JsonElement toJson() {
            JsonObject o = new JsonObject();
            o.add(kind, value);
            return o;
        }
    }
}
