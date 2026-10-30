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

/** {@code targets.json}: loader eras, per-version mapping sources and the supported matrix (binding-format.md section 3). */
public final class Targets {
    /** Runtime namespaces of section 2.4. */
    public static final Set<String> RUNTIMES = Json.set("intermediary", "lf", "mojang", "srgmoj", "srg", "obf", "official");
    /** Emitters (loader-emitters.md). */
    public static final Set<String> EMITTERS = Json.set("fabric", "neoforge", "forge-legacy", "forge-modern");
    private static final Set<String> MAPPING_KEYS = Json.set("mojang", "intermediary", "lf", "srg", "official");

    private final String anchor;
    private final Map<String, List<Era>> loaders;
    private final Map<String, VersionRow> versions;
    private final List<String> supported;

    private Targets(String anchor, Map<String, List<Era>> loaders, Map<String, VersionRow> versions, List<String> supported) {
        this.anchor = anchor;
        this.loaders = loaders;
        this.versions = versions;
        this.supported = supported;
    }

    public static Targets parse(JsonElement root, String file) {
        JsonObject o = Json.root(root, file, "anchor", "loaders", "versions", "supported");
        String anchor = Json.nonEmpty(o, "anchor", file, "$");
        if (!Version.isReleaseId(anchor)) {
            throw new BindingException(file, "$.anchor", "'" + anchor + "' is not a release id");
        }

        Map<String, List<Era>> loaders = new LinkedHashMap<String, List<Era>>();
        JsonObject l = Json.obj(o.get("loaders"), file, "$.loaders");
        for (String id : l.keySet()) {
            String at = "$.loaders." + id;
            if (!Selector.LOADERS.contains(id)) {
                throw new BindingException(file, at, "unknown loader '" + id + "'");
            }
            JsonArray rules = Json.arr(l.get(id), file, at);
            List<Era> eras = new ArrayList<Era>();
            for (int i = 0; i < rules.size(); i++) {
                eras.add(Era.parse(rules.get(i), file, at + "[" + i + "]"));
            }
            loaders.put(id, Collections.unmodifiableList(eras));
        }

        Map<String, VersionRow> versions = new LinkedHashMap<String, VersionRow>();
        JsonObject v = Json.obj(o.get("versions"), file, "$.versions");
        for (String id : v.keySet()) {
            String at = "$.versions." + id;
            if (!Version.isReleaseId(id)) {
                throw new BindingException(file, at, "'" + id + "' is not a release id");
            }
            versions.put(id, VersionRow.parse(v.get(id), file, at));
        }

        List<String> supported = new ArrayList<String>();
        if (o.has("supported")) {
            JsonArray s = Json.arr(o.get("supported"), file, "$.supported");
            for (int i = 0; i < s.size(); i++) {
                supported.add(Json.str(s.get(i), file, "$.supported[" + i + "]"));
            }
        }
        return new Targets(anchor, Collections.unmodifiableMap(loaders), Collections.unmodifiableMap(versions),
                Collections.unmodifiableList(supported));
    }

    public String anchor() {
        return anchor;
    }

    /** The era rules of one loader, first match wins. */
    public List<Era> eras(String loader) {
        List<Era> e = loaders.get(loader);
        return e == null ? Collections.<Era>emptyList() : e;
    }

    public Map<String, VersionRow> versions() {
        return versions;
    }

    public List<String> supported() {
        return supported;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schema", Json.SCHEMA);
        o.addProperty("anchor", anchor);
        JsonObject l = new JsonObject();
        for (Map.Entry<String, List<Era>> e : loaders.entrySet()) {
            JsonArray a = new JsonArray();
            for (Era r : e.getValue()) {
                a.add(r.toJson());
            }
            l.add(e.getKey(), a);
        }
        o.add("loaders", l);
        JsonObject vs = new JsonObject();
        for (Map.Entry<String, VersionRow> e : versions.entrySet()) {
            vs.add(e.getKey(), e.getValue().toJson());
        }
        o.add("versions", vs);
        if (!supported.isEmpty()) {
            JsonArray s = new JsonArray();
            for (String x : supported) {
                s.add(x);
            }
            o.add("supported", s);
        }
        return o;
    }

    /** One era rule of a loader: the selector, the runtime namespace and the emitter. */
    public static final class Era {
        private final Selector when;
        private final String runtime;
        private final String emitter;

        private Era(Selector when, String runtime, String emitter) {
            this.when = when;
            this.runtime = runtime;
            this.emitter = emitter;
        }

        static Era parse(JsonElement e, String file, String path) {
            JsonObject o = Json.obj(e, file, path);
            Json.checkKeys(o, file, path, new String[] {"when", "runtime", "emitter"});
            if (!o.has("when")) {
                throw new BindingException(file, path, "missing \"when\"");
            }
            Selector when = Selector.parse(o.get("when"), file, path + ".when");
            String runtime = Json.oneOf(o.get("runtime"), RUNTIMES, file, path + ".runtime");
            String emitter = Json.oneOf(o.get("emitter"), EMITTERS, file, path + ".emitter");
            return new Era(when, runtime, emitter);
        }

        public Selector when() {
            return when;
        }

        public String runtime() {
            return runtime;
        }

        public String emitter() {
            return emitter;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("when", TypeBinding.selectorJson(when));
            o.addProperty("runtime", runtime);
            o.addProperty("emitter", emitter);
            return o;
        }
    }

    /** The row of one release: Java level, mapping sources and the adapter set. */
    public static final class VersionRow {
        private final int java;
        private final Map<String, Object> mappings;
        private final String adapters;

        private VersionRow(int java, Map<String, Object> mappings, String adapters) {
            this.java = java;
            this.mappings = mappings;
            this.adapters = adapters;
        }

        static VersionRow parse(JsonElement e, String file, String path) {
            JsonObject o = Json.obj(e, file, path);
            Json.checkKeys(o, file, path, new String[] {"java", "mappings", "adapters"});
            int java = Json.integer(o.get("java"), file, path + ".java");
            if (java < 8 || java > 25) {
                throw new BindingException(file, path + ".java", "Java level " + java + " is out of range");
            }
            JsonObject m = Json.obj(o.get("mappings"), file, path + ".mappings");
            Map<String, Object> mappings = new LinkedHashMap<String, Object>();
            for (String key : m.keySet()) {
                String at = path + ".mappings." + key;
                if (!MAPPING_KEYS.contains(key)) {
                    throw new BindingException(file, at, "unknown mapping source '" + key + "' (allowed: " + Json.sorted(MAPPING_KEYS) + ")");
                }
                JsonElement v = m.get(key);
                if (key.equals("mojang") || key.equals("official")) {
                    mappings.put(key, Json.bool(v, file, at));
                } else {
                    mappings.put(key, Json.str(v, file, at));
                }
            }
            return new VersionRow(java, Collections.unmodifiableMap(mappings), Json.nonEmpty(o, "adapters", file, path));
        }

        public int java() {
            return java;
        }

        /** Mapping source of a key: a Boolean for mojang and official, a coordinate string otherwise, or null. */
        public Object mapping(String key) {
            return mappings.get(key);
        }

        public String adapters() {
            return adapters;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("java", java);
            JsonObject m = new JsonObject();
            for (Map.Entry<String, Object> e : mappings.entrySet()) {
                if (e.getValue() instanceof Boolean) {
                    m.addProperty(e.getKey(), (Boolean) e.getValue());
                } else {
                    m.add(e.getKey(), new JsonPrimitive((String) e.getValue()));
                }
            }
            o.add("mappings", m);
            o.addProperty("adapters", adapters);
            return o;
        }
    }
}
