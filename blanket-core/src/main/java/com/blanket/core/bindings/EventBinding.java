// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.blanket.core.resolve.Ref;
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
    private static final Set<String> SOURCES = Json.set("arg", "this", "getter", "adapter", "const", "glue");

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
            Recipe r = Recipe.parse(arr.get(i), file, "$.recipes[" + i + "]");
            if (event.equals(r.via())) {
                throw new BindingException(file, "$.recipes[" + i + "].via", "a via names another event; " + event + " cannot go via itself");
            }
            recipes.add(r);
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

    /**
     * One recipe: Mixin injections (one object or an array, D-057), native loader event bodies (one object or an array),
     * or {@code via}: the runtime synthesizes the event from another event's hook (WORLD_JOIN from CLIENT_TICK_START), so
     * the recipe has no injection of its own and the planner wires the named event's recipe instead. Each injection or
     * native body names the {@code Dispatch} method it calls and gives that method's payload.
     */
    public static final class Recipe {
        /** An event constant name ({@code CLIENT_TICK_START}). */
        private static final java.util.regex.Pattern EVENT = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*");

        private final Selector when;
        private final boolean array;
        private final List<Injection> injections;
        private final String via;

        private Recipe(Selector when, boolean array, List<Injection> injections, String via) {
            this.when = when;
            this.array = array;
            this.injections = injections;
            this.via = via;
        }

        static Recipe parse(JsonElement e, String file, String path) {
            JsonObject o = Json.obj(e, file, path);
            Json.checkKeys(o, file, path, new String[] {"when", "mixin", "native", "via"});
            if (!o.has("when")) {
                throw new BindingException(file, path, "missing \"when\"");
            }
            Selector when = Selector.parse(o.get("when"), file, path + ".when");
            int kinds = (o.has("mixin") ? 1 : 0) + (o.has("native") ? 1 : 0) + (o.has("via") ? 1 : 0);
            if (kinds != 1) {
                throw new BindingException(file, path, "a recipe has exactly one of \"mixin\", \"native\" and \"via\"");
            }
            if (o.has("via")) {
                String via = Json.nonEmpty(o, "via", file, path);
                if (!EVENT.matcher(via).matches()) {
                    throw new BindingException(file, path + ".via", "via names an event constant (CLIENT_TICK_START), found '" + via + "'");
                }
                return new Recipe(when, false, Collections.<Injection>emptyList(), via);
            }
            boolean mixin = o.has("mixin");
            String key = mixin ? "mixin" : "native";
            String at = path + "." + key;
            JsonElement m = o.get(key);
            List<Injection> out = new ArrayList<Injection>();
            if (m.isJsonArray()) {
                JsonArray a = m.getAsJsonArray();
                if (a.size() == 0) {
                    throw new BindingException(file, at, "a " + key + " array needs at least one " + (mixin ? "injection" : "native body"));
                }
                for (int i = 0; i < a.size(); i++) {
                    String p = at + "[" + i + "]";
                    out.add(mixin ? Injection.mixin(a.get(i), file, p) : Injection.nativeBody(a.get(i), file, p));
                }
            } else {
                out.add(mixin ? Injection.mixin(m, file, at) : Injection.nativeBody(m, file, at));
            }
            return new Recipe(when, m.isJsonArray(), Collections.unmodifiableList(out), null);
        }

        public Selector when() {
            return when;
        }

        /** True for a Mixin recipe, false for a native or a via one. */
        public boolean isMixin() {
            return !injections.isEmpty() && injections.get(0).hook() != null;
        }

        /** The event whose recipe delivers this one ({@code "via"}), or null for a Mixin or native recipe. */
        public String via() {
            return via;
        }

        /** The Mixin injections or the native bodies, in order; empty for a via recipe. */
        public List<Injection> injections() {
            return injections;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("when", TypeBinding.selectorJson(when));
            if (via != null) {
                o.addProperty("via", via);
                return o;
            }
            String key = isMixin() ? "mixin" : "native";
            if (array) {
                JsonArray a = new JsonArray();
                for (Injection i : injections) {
                    a.add(i.toJson());
                }
                o.add(key, a);
            } else {
                o.add(key, injections.get(0).toJson());
            }
            return o;
        }
    }

    /** One Mixin injection (a hook) or one native event body: the {@code Dispatch} call and what feeds it. */
    public static final class Injection {
        private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

        private final String hook;
        private final String nativeEvent;
        private final String bus;
        private final Map<String, String> filter;
        private final String dispatch;
        private final Map<String, Source> payload;
        private final List<Source> before;

        private Injection(String hook, String nativeEvent, String bus, Map<String, String> filter, String dispatch,
                          Map<String, Source> payload, List<Source> before) {
            this.hook = hook;
            this.nativeEvent = nativeEvent;
            this.bus = bus;
            this.filter = filter;
            this.dispatch = dispatch;
            this.payload = payload;
            this.before = before;
        }

        static Injection mixin(JsonElement e, String file, String at) {
            JsonObject m = Json.obj(e, file, at);
            Json.checkKeys(m, file, at, new String[] {"hook", "dispatch", "payload", "before"});
            return new Injection(Json.nonEmpty(m, "hook", file, at), null, null, Collections.<String, String>emptyMap(),
                    dispatch(m, file, at), payload(m, file, at), before(m, file, at));
        }

        static Injection nativeBody(JsonElement e, String file, String at) {
            JsonObject n = Json.obj(e, file, at);
            Json.checkKeys(n, file, at, new String[] {"event", "bus", "filter", "dispatch", "payload", "before"});
            String bus = Json.oneOf(n.get("bus"), BUSES, file, at + ".bus");
            return new Injection(null, Json.nonEmpty(n, "event", file, at), bus, filter(n, file, at), dispatch(n, file, at),
                    payload(n, file, at), before(n, file, at));
        }

        /** D-057.1: the required name of the {@code Dispatch} method this body calls. */
        private static String dispatch(JsonObject o, String file, String at) {
            if (!o.has("dispatch")) {
                throw new BindingException(file, at, "missing \"dispatch\" (the Dispatch method this recipe calls, D-057)");
            }
            String d = Json.nonEmpty(o, "dispatch", file, at);
            if (!IDENTIFIER.matcher(d).matches()) {
                throw new BindingException(file, at + ".dispatch", "dispatch is a Dispatch method name, found '" + d + "'");
            }
            return d;
        }

        /**
         * D-057.2: {@code {"<getter or field ref>": "<static field ref>"}}, entries ANDed; the generated listener returns
         * early unless each read is identical to the static field's value.
         */
        private static Map<String, String> filter(JsonObject n, String file, String at) {
            if (!n.has("filter")) {
                return Collections.emptyMap();
            }
            String fat = at + ".filter";
            JsonObject f = Json.obj(n.get("filter"), file, fat);
            if (f.size() == 0) {
                throw new BindingException(file, fat, "a filter needs at least one entry");
            }
            Map<String, String> out = new LinkedHashMap<String, String>();
            for (String key : f.keySet()) {
                String p = fat + "." + key;
                checkGetterRef(key, file, p);
                JsonElement v = f.get(key);
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                    throw new BindingException(file, p, "a filter value is a static field reference (Owner#NAME:desc)");
                }
                String value = v.getAsString();
                if (!Ref.parse(value, file, p).field()) {
                    throw new BindingException(file, p, "a filter value is a static field reference (Owner#NAME:desc), found '" + value + "'");
                }
                out.put(key, value);
            }
            return Collections.unmodifiableMap(out);
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

        /**
         * D-056: the glue calls a recipe runs before its {@code Dispatch} call, in order. Each is a glue source
         * whose member is written with its descriptor and returns {@code void}.
         */
        private static List<Source> before(JsonObject o, String file, String at) {
            if (!o.has("before")) {
                return Collections.emptyList();
            }
            JsonArray a = Json.arr(o.get("before"), file, at + ".before");
            List<Source> out = new ArrayList<Source>();
            for (int i = 0; i < a.size(); i++) {
                String p = at + ".before[" + i + "]";
                Source s = Source.parse(a.get(i), file, p);
                String member = s.kind().equals("glue") ? s.value().getAsString() : "";
                if (!member.contains("(") || !member.endsWith(")V")) {
                    throw new BindingException(file, p, "a before entry is a glue source naming a void member with its descriptor"
                            + " (GlueType#member(...)V), found " + s.toJson());
                }
                out.add(s);
            }
            return Collections.unmodifiableList(out);
        }

        /** The hook id of a Mixin injection, or null. */
        public String hook() {
            return hook;
        }

        /** The loader event class of a native body, or null. */
        public String nativeEvent() {
            return nativeEvent;
        }

        /** The bus of a native body ({@code game}, {@code mod} or {@code fml}), or null. */
        public String bus() {
            return bus;
        }

        /** The filter of a native body, getter or field ref to static field ref (D-057.2); empty when none. */
        public Map<String, String> filter() {
            return filter;
        }

        /** The {@code Dispatch} method name (D-057.1). */
        public String dispatch() {
            return dispatch;
        }

        public Map<String, Source> payload() {
            return payload;
        }

        /** The void glue calls run before the {@code Dispatch} call (D-056), in order; often empty. */
        public List<Source> before() {
            return before;
        }

        JsonObject toJson() {
            JsonObject body = new JsonObject();
            if (hook != null) {
                body.addProperty("hook", hook);
            } else {
                body.addProperty("event", nativeEvent);
                body.addProperty("bus", bus);
                if (!filter.isEmpty()) {
                    JsonObject f = new JsonObject();
                    for (Map.Entry<String, String> e : filter.entrySet()) {
                        f.addProperty(e.getKey(), e.getValue());
                    }
                    body.add("filter", f);
                }
            }
            body.addProperty("dispatch", dispatch);
            if (!before.isEmpty()) {
                JsonArray b = new JsonArray();
                for (Source s : before) {
                    b.add(s.toJson());
                }
                body.add("before", b);
            }
            if (!payload.isEmpty()) {
                JsonObject p = new JsonObject();
                for (Map.Entry<String, Source> e : payload.entrySet()) {
                    p.add(e.getKey(), e.getValue().toJson());
                }
                body.add("payload", p);
            }
            return body;
        }
    }

    /**
     * D-057.3: a getter is a member reference: a method without parameters ({@code Owner#name()T}) or a field
     * ({@code Owner#name:T}, read with GETFIELD).
     */
    static void checkGetterRef(String text, String file, String path) {
        Ref r = Ref.parse(text, file, path);
        if (r.isClass() || (!r.field() && !r.desc().startsWith("()"))) {
            throw new BindingException(file, path, "a getter is a method without parameters (Owner#name()T) or a field"
                    + " (Owner#name:T), found '" + text + "'");
        }
    }

    /**
     * A payload source: an argument, a field, a getter, an adapter, a constant or a glue call (binding-format.md
     * section 7, D-054). A glue source names a glue member and gives the payload sources that are its arguments.
     */
    public static final class Source {
        /** A glue member: {@code GlueType#member}, optionally followed by its descriptor. */
        private static final java.util.regex.Pattern GLUE_MEMBER =
                java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*#[A-Za-z_$][A-Za-z0-9_$]*(\\(.*\\).*)?");

        private final String kind;
        private final JsonElement value;
        private final List<Source> args;

        private Source(String kind, JsonElement value, List<Source> args) {
            this.kind = kind;
            this.value = value;
            this.args = args;
        }

        static Source parse(JsonElement e, String file, String path) {
            JsonObject o = Json.obj(e, file, path);
            if (o.has("glue")) {
                Json.checkKeys(o, file, path, new String[] {"glue", "args"});
                String member = Json.nonEmpty(o, "glue", file, path);
                if (!GLUE_MEMBER.matcher(member).matches()) {
                    throw new BindingException(file, path + ".glue", "a glue member is GlueType#member, found '" + member + "'");
                }
                List<Source> args = new ArrayList<Source>();
                if (o.has("args")) {
                    JsonArray a = Json.arr(o.get("args"), file, path + ".args");
                    for (int i = 0; i < a.size(); i++) {
                        args.add(parse(a.get(i), file, path + ".args[" + i + "]"));
                    }
                }
                return new Source("glue", new JsonPrimitive(member), Collections.unmodifiableList(args));
            }
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
                    return new Source(key, new JsonPrimitive(n), NO_ARGS);
                }
                case "const":
                    if (!v.isJsonPrimitive() && !v.isJsonNull()) {
                        throw new BindingException(file, at, "const must be true, false, a number, a string or null (D-056)");
                    }
                    return new Source(key, v, NO_ARGS);
                case "getter": {
                    String g = Json.nonEmpty(o, key, file, path);
                    if (g.indexOf('#') >= 0) {
                        checkGetterRef(g, file, at);
                    }
                    return new Source(key, new JsonPrimitive(g), NO_ARGS);
                }
                default:
                    return new Source(key, new JsonPrimitive(Json.nonEmpty(o, key, file, path)), NO_ARGS);
            }
        }

        private static final List<Source> NO_ARGS = Collections.emptyList();

        /** The source kind: arg, this, getter, adapter, const or glue. */
        public String kind() {
            return kind;
        }

        public JsonElement value() {
            return value;
        }

        /** The argument sources of a glue call, in order; empty for every other kind. */
        public List<Source> args() {
            return args;
        }

        JsonElement toJson() {
            JsonObject o = new JsonObject();
            o.add(kind, value);
            if (!args.isEmpty()) {
                JsonArray a = new JsonArray();
                for (Source s : args) {
                    a.add(s.toJson());
                }
                o.add("args", a);
            }
            return o;
        }
    }
}
