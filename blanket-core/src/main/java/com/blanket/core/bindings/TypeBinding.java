// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A type file {@code types/<ApiType>.json}, or a glue file {@code glue/<GlueType>.json} (same format,
 * binding-format.md section 4). It holds the representation rules of the type and one rule list per member.
 */
public final class TypeBinding {
    private final String file;
    private final String api;
    private final String anchor;
    private final List<Representation> representation;
    private final Map<String, List<MemberRule>> members;
    private final String note;

    private TypeBinding(String file, String api, String anchor, List<Representation> representation,
                        Map<String, List<MemberRule>> members, String note) {
        this.file = file;
        this.api = api;
        this.anchor = anchor;
        this.representation = representation;
        this.members = members;
        this.note = note;
    }

    /** Reads a type or glue file. {@code file} is used in errors. */
    public static TypeBinding parse(JsonElement root, String file) {
        JsonObject o = Json.root(root, file, "api", "anchor", "representation", "members", "note");
        String api = Json.nonEmpty(o, "api", file, "$");
        String anchor = Json.optStr(o, "anchor", file, "$");
        List<Representation> rep = new ArrayList<Representation>();
        if (o.has("representation")) {
            JsonArray a = Json.arr(o.get("representation"), file, "$.representation");
            for (int i = 0; i < a.size(); i++) {
                rep.add(Representation.parse(a.get(i), file, "$.representation[" + i + "]"));
            }
        }
        Map<String, List<MemberRule>> members = new LinkedHashMap<String, List<MemberRule>>();
        if (o.has("members")) {
            JsonObject m = Json.obj(o.get("members"), file, "$.members");
            for (String key : m.keySet()) {
                if (key.isEmpty()) {
                    throw new BindingException(file, "$.members", "member key must not be empty");
                }
                String at = "$.members." + key;
                JsonArray rules = Json.arr(m.get(key), file, at);
                if (rules.size() == 0) {
                    throw new BindingException(file, at, "rule list is empty");
                }
                List<MemberRule> list = new ArrayList<MemberRule>();
                for (int i = 0; i < rules.size(); i++) {
                    list.add(MemberRule.parse(rules.get(i), file, at + "[" + i + "]"));
                }
                members.put(key, Collections.unmodifiableList(list));
            }
        }
        return new TypeBinding(file, api, anchor, Collections.unmodifiableList(rep),
                Collections.unmodifiableMap(members), Json.optStr(o, "note", file, "$"));
    }

    public String file() {
        return file;
    }

    public String api() {
        return api;
    }

    /** The anchor override of this file, or null for the target-wide anchor. */
    public String anchor() {
        return anchor;
    }

    public List<Representation> representation() {
        return representation;
    }

    public Map<String, List<MemberRule>> members() {
        return members;
    }

    public String note() {
        return note;
    }

    /** Writes this file back to JSON. Optional keys are written only when they were present. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schema", Json.SCHEMA);
        o.addProperty("api", api);
        if (anchor != null) {
            o.addProperty("anchor", anchor);
        }
        if (!representation.isEmpty()) {
            JsonArray a = new JsonArray();
            for (Representation r : representation) {
                a.add(r.toJson());
            }
            o.add("representation", a);
        }
        if (!members.isEmpty()) {
            JsonObject m = new JsonObject();
            for (Map.Entry<String, List<MemberRule>> e : members.entrySet()) {
                JsonArray a = new JsonArray();
                for (MemberRule r : e.getValue()) {
                    a.add(r.toJson());
                }
                m.add(e.getKey(), a);
            }
            o.add("members", m);
        }
        if (note != null) {
            o.addProperty("note", note);
        }
        return o;
    }

    /** A representation rule: the API type is an alias of a Minecraft class, or an owned runtime class. */
    public static final class Representation {
        private final Selector when;
        private final String alias;
        private final String owned;

        private Representation(Selector when, String alias, String owned) {
            this.when = when;
            this.alias = alias;
            this.owned = owned;
        }

        static Representation parse(JsonElement e, String file, String path) {
            JsonObject o = Json.obj(e, file, path);
            Json.checkKeys(o, file, path, new String[] {"when", "alias", "owned"});
            Selector when = Selector.parse(o.get("when"), file, path + ".when");
            boolean hasAlias = o.has("alias");
            boolean hasOwned = o.has("owned");
            if (hasAlias == hasOwned) {
                throw new BindingException(file, path, "a representation rule has exactly one of \"alias\" and \"owned\"");
            }
            if (hasAlias) {
                return new Representation(when, Json.nonEmpty(o, "alias", file, path), null);
            }
            return new Representation(when, null, Json.nonEmpty(o, "owned", file, path));
        }

        public Selector when() {
            return when;
        }

        public String alias() {
            return alias;
        }

        public String owned() {
            return owned;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("when", selectorJson(when));
            if (alias != null) {
                o.addProperty("alias", alias);
            } else {
                o.addProperty("owned", owned);
            }
            return o;
        }
    }

    /** One member rule: when it applies, how it lowers, and the feature it needs if any. */
    public static final class MemberRule {
        private final Selector when;
        private final String requires;
        private final Lowering lower;
        private final String note;

        private MemberRule(Selector when, String requires, Lowering lower, String note) {
            this.when = when;
            this.requires = requires;
            this.lower = lower;
            this.note = note;
        }

        static MemberRule parse(JsonElement e, String file, String path) {
            JsonObject o = Json.obj(e, file, path);
            Json.checkKeys(o, file, path, new String[] {"when", "requires", "lower", "note"});
            if (!o.has("when")) {
                throw new BindingException(file, path, "missing \"when\"");
            }
            if (!o.has("lower")) {
                throw new BindingException(file, path, "missing \"lower\"");
            }
            Selector when = Selector.parse(o.get("when"), file, path + ".when");
            String requires = Json.optStr(o, "requires", file, path);
            if (requires != null && !requires.matches("[A-Z][A-Z0-9_]*")) {
                throw new BindingException(file, path + ".requires", "feature names are upper-case identifiers, found '" + requires + "'");
            }
            Lowering lower = Lowering.parse(o.get("lower"), file, path + ".lower");
            return new MemberRule(when, requires, lower, Json.optStr(o, "note", file, path));
        }

        public Selector when() {
            return when;
        }

        /** The Feature this rule needs, or null. */
        public String requires() {
            return requires;
        }

        public Lowering lower() {
            return lower;
        }

        public String note() {
            return note;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("when", selectorJson(when));
            if (requires != null) {
                o.addProperty("requires", requires);
            }
            o.add("lower", lower.toJson());
            if (note != null) {
                o.addProperty("note", note);
            }
            return o;
        }
    }

    /** Writes a selector back to JSON in the form it was read from (loader list or string, mc string). */
    static JsonObject selectorJson(Selector s) {
        JsonObject o = new JsonObject();
        if (s.loaders() != null) {
            if (s.loaders().size() == 1) {
                o.addProperty("loader", s.loaders().iterator().next());
            } else {
                JsonArray a = new JsonArray();
                for (String l : s.loaders()) {
                    a.add(l);
                }
                o.add("loader", a);
            }
        }
        if (s.mc() != null) {
            o.addProperty("mc", s.mc());
        }
        return o;
    }
}
