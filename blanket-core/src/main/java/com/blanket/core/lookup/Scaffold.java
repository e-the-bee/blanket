// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.bindings.Hooks;
import com.blanket.core.bindings.EventBinding;
import com.blanket.core.bindings.Targets;
import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.bindings.Version;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code scaffold}: writes the skeleton of a contribution (contributing.md sections 2 and 3). Every file is built in
 * memory, validated with the binding parsers, and only then written, so a refused or invalid request changes nothing.
 * Existing hand-formatted JSON is edited by text insertion, so a diff shows only the added lines. Whatever is not
 * known is written as a visible {@code TODO} that keeps the file parseable, except type aliases, which are
 * deliberately not references until filled in.
 */
public final class Scaffold {
    private static final Pattern UPPER_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern TYPE_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*");
    private static final Pattern SIG = Pattern.compile("^(static\\s+)?([A-Za-z_]\\w*(?:\\[\\])*)\\s+([A-Za-z_]\\w*)\\s*\\((.*)\\)$");
    private static final Pattern PARAM = Pattern.compile("^(.+?)\\s+([A-Za-z_]\\w*)$");
    private static final String API_PREFIX = "Lcom/blanket/api/";
    private static final String SPDX = "// SPDX-License-Identifier: GPL-3.0-only\n";

    private final Path bindings;
    private final Path api;
    private final Targets targets;
    private final Lookup lookup;
    private final Set<Path> created = new LinkedHashSet<>();

    /**
     * @param bindingsDir the folder with targets.json, hooks.json, features.json, types/ and events/
     * @param apiSourceDir the package folder of the API sources (blanket-api/src/main/java/com/blanket/api)
     */
    public Scaffold(Path bindingsDir, Path apiSourceDir, Targets targets, Lookup lookup) {
        this.bindings = bindingsDir;
        this.api = apiSourceDir;
        this.targets = targets;
        this.lookup = lookup;
    }

    /** The files the last call created (the others it returned were changed). */
    public Set<Path> created() {
        return created;
    }

    private record Write(Path path, String text) {}

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }

    // ------------------------------------------------------------------------------------------------ member

    /** A parsed signature: {@code [static] ret name(type p, ...)}. */
    private record Sig(boolean isStatic, String ret, String name, List<String[]> params, String desc, boolean primOnly,
                       String retDesc) {
        String declaration() {
            StringBuilder b = new StringBuilder("public ");
            if (isStatic) b.append("static ");
            b.append(ret).append(' ').append(name).append('(');
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(params.get(i)[0]).append(' ').append(params.get(i)[1]);
            }
            return b.append(')').toString();
        }
    }

    /** Adds {@code sig} to {@code <Type>.java} and a rule list to {@code types/<Type>.json}. */
    public List<Path> member(String type, String sig) {
        created.clear();
        if (type == null || !TYPE_NAME.matcher(type).matches()) {
            throw bad("pass --type as an API type name such as Player, found '" + type + "'");
        }
        Path apiFile = api.resolve(type + ".java");
        Path typeFile = bindings.resolve("types").resolve(type + ".json");
        if (!Files.isRegularFile(apiFile) || !Files.isRegularFile(typeFile)) {
            throw bad("run `scaffold --kind type --name " + type + " --alias <class>` first: " + apiFile + " and "
                    + typeFile + " must both exist");
        }
        Sig s = parseSig(sig);
        String json = read(typeFile);
        TypeBinding tb = parseType(json, typeFile);
        Set<String> keys = tb.members().keySet();
        boolean overloaded = false;
        for (String k : keys) {
            if (k.equals(s.name) || k.startsWith(s.name + "(")) overloaded = true;
        }
        String key = overloaded ? s.name + s.desc : s.name;
        if (keys.contains(key)) {
            throw bad("pick another signature: " + typeFile + " already has member " + key);
        }
        String apiText = read(apiFile);
        if (apiText.contains(s.declaration())) {
            throw bad("pick another signature: " + apiFile + " already declares " + s.declaration());
        }

        List<String> rules = memberRules(tb, type, s);
        String newApi = insertStub(apiText, apiFile, s);
        String newJson = insertMember(json, typeFile, key, rules);
        parseType(newJson, typeFile);
        return commit(List.of(new Write(apiFile, newApi), new Write(typeFile, newJson)));
    }

    private Sig parseSig(String sig) {
        Matcher m = SIG.matcher(sig == null ? "" : sig.trim());
        if (!m.matches()) {
            throw bad("write --sig as \"[static] <return> <name>(<type> <param>, ...)\", found '" + sig + "'");
        }
        List<String[]> params = new ArrayList<>();
        StringBuilder desc = new StringBuilder("(");
        boolean primOnly = true;
        String args = m.group(4).trim();
        if (!args.isEmpty()) {
            for (String part : args.split(",", -1)) {
                Matcher p = PARAM.matcher(part.trim());
                if (!p.matches()) {
                    throw bad("give every parameter a type and a name in --sig, found '" + part.trim() + "'");
                }
                String t = p.group(1).trim();
                String d = descriptor(t, false);
                if (d.contains(API_PREFIX)) primOnly = false;
                desc.append(d);
                params.add(new String[] {t, p.group(2)});
            }
        }
        String retDesc = descriptor(m.group(2), true);
        if (retDesc.contains(API_PREFIX)) primOnly = false;
        desc.append(')').append(retDesc);
        return new Sig(m.group(1) != null, m.group(2), m.group(3), params, desc.toString(), primOnly, retDesc);
    }

    private String descriptor(String type, boolean isReturn) {
        int dims = 0;
        String t = type;
        while (t.endsWith("[]")) {
            dims++;
            t = t.substring(0, t.length() - 2);
        }
        String d;
        switch (t) {
            case "boolean": d = "Z"; break;
            case "byte": d = "B"; break;
            case "char": d = "C"; break;
            case "short": d = "S"; break;
            case "int": d = "I"; break;
            case "long": d = "J"; break;
            case "float": d = "F"; break;
            case "double": d = "D"; break;
            case "void":
                if (!isReturn || dims > 0) throw bad("use void only as a return type in --sig");
                d = "V";
                break;
            case "String": d = "Ljava/lang/String;"; break;
            default:
                if (!TYPE_NAME.matcher(t).matches()) {
                    throw bad("write --sig types as Java primitives, String, an API type name or an array of those, found '" + type + "'");
                }
                if (!Files.isRegularFile(api.resolve(t + ".java"))) {
                    throw bad("write --sig types as Java primitives, String or an API type; " + api.resolve(t + ".java")
                            + " does not exist (create it with --kind type)");
                }
                d = API_PREFIX + t + ";";
        }
        return "[".repeat(dims) + d;
    }

    private String insertStub(String text, Path file, Sig s) {
        String nl = newline(text);
        int close = text.lastIndexOf('}');
        if (close < 0) throw bad("fix " + file + ": it has no closing brace");
        int lineStart = text.lastIndexOf('\n', close) + 1;
        boolean ownLine = text.substring(lineStart, close).isBlank();
        int at = ownLine ? lineStart : close;
        String ins = (ownLine ? "" : "\n") + "\n    /** TODO: describe {@code " + s.name + "}. */\n    " + s.declaration()
                + " { throw new UnsupportedOperationException(\"compile-time stub\"); }\n";
        return text.substring(0, at) + ins.replace("\n", nl) + text.substring(at);
    }

    /** The rule list of a new member: found names where {@code lookup} resolves, TODO rules for the rest. */
    private List<String> memberRules(TypeBinding tb, String type, Sig s) {
        String cls = aliasClass(tb);
        Found f = cls == null ? null : find(cls, s);
        List<String> rules = new ArrayList<>();
        if (f == null) {
            rules.add(rule("{}", "unsupported", "TODO: bind " + type + "." + s.name
                    + " on every target; try `lookup --ref " + (cls == null ? "<Class>#<member>" : cls + "#" + s.name) + "`"));
            return rules;
        }
        for (Lookup.Row row : f.rows) {
            if (row.ok()) continue;
            rules.add(rule(whenOf(row.target()), "unsupported",
                    "TODO " + row.target() + ": " + row.step() + ": " + row.message()));
        }
        rules.add(rule("{}", f.field ? "get" : "invoke", f.ref));
        return rules;
    }

    private static String rule(String when, String kind, String value) {
        return "{ \"when\": " + when + ", \"lower\": { " + quote(kind) + ": " + quote(value) + " } }";
    }

    private static String whenOf(String target) {
        int colon = target.indexOf(':');
        return "{ \"loader\": " + quote(target.substring(0, colon)) + ", \"mc\": " + quote(target.substring(colon + 1)) + " }";
    }

    private static JsonObject whenJson(String target) {
        int colon = target.indexOf(':');
        JsonObject w = new JsonObject();
        w.addProperty("loader", target.substring(0, colon));
        w.addProperty("mc", target.substring(colon + 1));
        return w;
    }

    /** The class the member is looked up on: the catch-all alias, else the first alias that is not lf/official. */
    private static String aliasClass(TypeBinding tb) {
        for (TypeBinding.Representation r : tb.representation()) {
            if (r.alias() != null && r.when().loaders() == null && r.when().mc() == null) return r.alias();
        }
        for (TypeBinding.Representation r : tb.representation()) {
            if (r.alias() != null && !r.alias().startsWith("lf:") && !r.alias().startsWith("official:")) return r.alias();
        }
        return null;
    }

    private record Found(boolean field, String ref, List<Lookup.Row> rows) {}

    private Found find(String cls, Sig s) {
        String cap = Character.toUpperCase(s.name.charAt(0)) + s.name.substring(1);
        for (String cand : List.of(s.name, "get" + cap, "is" + cap)) {
            List<Lookup.Result> results;
            try {
                results = lookup.lookup(cls + "#" + cand, List.of());
            } catch (RuntimeException e) {
                continue;
            }
            for (Lookup.Result r : results) {
                boolean anyOk = false;
                for (Lookup.Row row : r.rows()) {
                    if (row.ok()) anyOk = true;
                }
                if (!anyOk) continue;
                String ref = dotted(r.ref());
                int hash = ref.indexOf('#');
                if (hash < 0) continue;
                String member = ref.substring(hash + 1);
                int paren = member.indexOf('(');
                boolean field = paren < 0;
                String desc;
                if (field) {
                    int colon = member.indexOf(':');
                    if (colon < 0) continue;
                    desc = member.substring(colon + 1);
                } else {
                    desc = member.substring(paren);
                }
                if (matches(s, field, desc)) return new Found(field, ref, r.rows());
            }
        }
        return null;
    }

    private static boolean matches(Sig s, boolean field, String desc) {
        if (field) return s.params.isEmpty() && (!s.primOnly || desc.equals(s.retDesc));
        if (s.primOnly) return desc.equals(s.desc);
        int close = desc.indexOf(')');
        return close > 0 && paramCount(desc.substring(1, close)) == s.params.size();
    }

    private static int paramCount(String args) {
        int n = 0;
        for (int i = 0; i < args.length(); i++) {
            while (args.charAt(i) == '[') i++;
            if (args.charAt(i) == 'L') i = args.indexOf(';', i);
            n++;
        }
        return n;
    }

    /** Writes the class part of a Mojang-style reference with dots, as the binding files do. */
    private static String dotted(String ref) {
        int hash = ref.indexOf('#');
        String head = hash < 0 ? ref : ref.substring(0, hash);
        String tail = hash < 0 ? "" : ref.substring(hash);
        int colon = head.indexOf(':');
        String ns = colon < 0 ? "mojang" : head.substring(0, colon);
        int at = ns.indexOf('@');
        if (at >= 0) ns = ns.substring(0, at);
        if (ns.equals("mojang") || ns.equals("official") || ns.equals("srgmoj")) head = head.replace('/', '.');
        return head + tail;
    }

    private String insertMember(String text, Path file, String key, List<String> rules) {
        int root = rootOpen(text, file);
        int members = valueSpan(text, root, "members");
        if (members >= 0) {
            if (text.charAt(members) != '{') throw bad("fix " + file + ": \"members\" must be an object");
            return insertEntry(text, members, ind -> memberEntry(ind, key, rules));
        }
        return insertEntry(text, root, ind -> ind + "\"members\": {\n" + memberEntry(ind + "  ", key, rules) + "\n" + ind + "}");
    }

    private static String memberEntry(String ind, String key, List<String> rules) {
        StringBuilder b = new StringBuilder(ind).append(quote(key)).append(": [\n");
        for (int i = 0; i < rules.size(); i++) {
            b.append(ind).append("  ").append(rules.get(i)).append(i + 1 < rules.size() ? ",\n" : "\n");
        }
        return b.append(ind).append(']').toString();
    }

    // ------------------------------------------------------------------------------------------------ type

    /** Creates {@code <Name>.java} and {@code types/<Name>.json}. Pass an alias or {@code owned}, not both. */
    public List<Path> type(String name, String alias, boolean owned) {
        created.clear();
        if (name == null || !TYPE_NAME.matcher(name).matches()) {
            throw bad("pass --name as an API type name such as Foo, found '" + name + "'");
        }
        boolean hasAlias = alias != null && !alias.isBlank();
        if (hasAlias == owned) throw bad("pass exactly one of --alias <class> and --owned");
        Path apiFile = api.resolve(name + ".java");
        Path typeFile = bindings.resolve("types").resolve(name + ".json");
        if (Files.exists(apiFile) || Files.exists(typeFile)) {
            throw bad("pick another --name: " + (Files.exists(apiFile) ? apiFile : typeFile) + " already exists");
        }
        JsonObject o = new JsonObject();
        o.addProperty("schema", 1);
        o.addProperty("api", "com.blanket.api." + name);
        String doc;
        if (owned) {
            o.addProperty("note", "TODO: add the \"representation\" rules with an owned class per era (binding-format.md 4.1).");
            doc = "/** TODO: describe {@code " + name + "}. Owned type. */";
        } else {
            List<Lookup.Row> rows;
            String ref;
            try {
                List<Lookup.Result> rs = lookup.lookup(alias.trim(), List.of());
                if (rs.size() != 1) {
                    StringBuilder b = new StringBuilder();
                    for (Lookup.Result r : rs) b.append(' ').append(r.ref());
                    throw bad("write the full class name in --alias; '" + alias + "' matches " + rs.size() + " classes:" + b);
                }
                rows = rs.get(0).rows();
                ref = dotted(rs.get(0).ref());
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException && e.getClass() == IllegalArgumentException.class) throw e;
                throw bad("fix --alias: " + Lookup.describe(e));
            }
            for (Lookup.Row row : rows) {
                if (row.target().equals("*")) throw bad("fix --alias: " + row.step() + ": " + row.message());
            }
            JsonArray rep = new JsonArray();
            List<String> failing = new ArrayList<>();
            int okCount = 0;
            for (Lookup.Row row : rows) {
                if (row.ok()) {
                    okCount++;
                    continue;
                }
                failing.add(row.target());
                JsonObject r = new JsonObject();
                r.add("when", whenJson(row.target()));
                r.addProperty("alias", "TODO:" + row.target());
                rep.add(r);
            }
            if (okCount > 0) {
                JsonObject all = new JsonObject();
                all.add("when", new JsonObject());
                all.addProperty("alias", ref);
                rep.add(all);
            }
            o.add("representation", rep);
            String needs = String.join(", ", failing);
            if (!failing.isEmpty()) {
                o.addProperty("note", "TODO: fill the alias of " + needs + " (" + ref + " does not resolve there; try `lookup --ref ...`).");
            }
            doc = "/** TODO: describe {@code " + name + "}. Aliased to {@code " + ref + "}; this body exists only for compilation."
                    + (failing.isEmpty() ? "" : " Targets needing an alias: " + needs + ".") + " */";
        }
        parseType(BindingFiles.format(o), typeFile, o);
        String java = SPDX + "package com.blanket.api;\n\n" + doc + "\npublic final class " + name + " {\n    private " + name
                + "() {}\n}\n";
        return commit(List.of(new Write(apiFile, java), new Write(typeFile, BindingFiles.format(o))));
    }

    // ------------------------------------------------------------------------------------------------ event

    /** Creates {@code events/<UpperCamel>.json} and appends a hook skeleton to {@code hooks.json}. */
    public List<Path> event(String name) {
        created.clear();
        requireUpper(name, "--name");
        String camel = upperCamel(name);
        String hookId = name.toLowerCase().replace('_', '.');
        Path eventFile = bindings.resolve("events").resolve(camel + ".json");
        Path hooksFile = bindings.resolve("hooks.json");
        if (Files.exists(eventFile)) throw bad("pick another --name: " + eventFile + " already exists");
        if (!Files.isRegularFile(hooksFile)) throw bad("create " + hooksFile + " first ({\"schema\": 1, \"hooks\": {}})");

        JsonObject o = new JsonObject();
        o.addProperty("schema", 1);
        o.addProperty("event", name);
        JsonArray recipes = new JsonArray();
        JsonObject recipe = new JsonObject();
        recipe.add("when", new JsonObject());
        JsonObject mixin = new JsonObject();
        mixin.addProperty("hook", hookId);
        // D-057: the Dispatch method this hook calls; the contributor adds it to Dispatch with the payload parameters
        mixin.addProperty("dispatch", Character.toLowerCase(camel.charAt(0)) + camel.substring(1));
        recipe.add("mixin", mixin);
        recipes.add(recipe);
        o.add("recipes", recipes);
        try {
            EventBinding.parse(o, eventFile.toString());
        } catch (RuntimeException e) {
            throw bad("fix the event skeleton: " + Lookup.describe(e));
        }

        String text = read(hooksFile);
        Hooks existing;
        try {
            existing = Hooks.parse(JsonParser.parseString(text), hooksFile.toString());
        } catch (RuntimeException e) {
            throw bad("fix " + hooksFile + " first: " + Lookup.describe(e));
        }
        if (existing.hooks().containsKey(hookId)) {
            throw bad("pick another --name: " + hooksFile + " already has hook " + hookId);
        }
        int root = rootOpen(text, hooksFile);
        int hooks = valueSpan(text, root, "hooks");
        if (hooks < 0 || text.charAt(hooks) != '{') throw bad("fix " + hooksFile + ": it needs a \"hooks\" object");
        String note = "TODO: name the injection point per era (contributing.md 3.3)";
        String newHooks = insertEntry(text, hooks, ind -> ind + quote(hookId) + ": [\n" + ind + "  { \"when\": {}, \"method\": "
                + quote("TODO:" + hookId) + ", \"at\": \"HEAD\", \"note\": " + quote(note) + " }\n" + ind + "]");
        try {
            Hooks.parse(JsonParser.parseString(newHooks), hooksFile.toString());
        } catch (RuntimeException e) {
            throw bad("fix " + hooksFile + " by hand; the inserted hook did not parse: " + Lookup.describe(e));
        }
        return commit(List.of(new Write(eventFile, BindingFiles.format(o)), new Write(hooksFile, newHooks)));
    }

    private static String upperCamel(String name) {
        StringBuilder b = new StringBuilder();
        for (String part : name.split("_")) {
            if (part.isEmpty()) continue;
            b.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------------ feature

    /** Adds the constant to {@code Feature.java} and a row to {@code features.json}. */
    public List<Path> feature(String name) {
        created.clear();
        requireUpper(name, "--name");
        Path enumFile = api.resolve("Feature.java");
        Path featuresFile = bindings.resolve("features.json");

        String enumText;
        if (Files.isRegularFile(enumFile)) {
            enumText = addConstant(read(enumFile), enumFile, name);
        } else {
            enumText = SPDX + "package com.blanket.api;\n\n"
                    + "/** Capabilities a game version may lack (binding-format.md section 6). */\n"
                    + "public enum Feature {\n    " + name + "\n}\n";
        }

        String row = "{ \"available\": [], \"note\": " + quote("TODO: list the eras where it is available (binding-format.md 6)") + " }";
        String json;
        if (Files.isRegularFile(featuresFile)) {
            String text = read(featuresFile);
            JsonElement parsed = parseJson(text, featuresFile);
            if (!parsed.isJsonObject()) throw bad("fix " + featuresFile + ": the root must be an object");
            JsonElement features = parsed.getAsJsonObject().get("features");
            if (features != null && (!features.isJsonObject())) throw bad("fix " + featuresFile + ": \"features\" must be an object");
            if (features != null && features.getAsJsonObject().has(name)) {
                throw bad("pick another --name: " + featuresFile + " already has feature " + name);
            }
            int root = rootOpen(text, featuresFile);
            int at = valueSpan(text, root, "features");
            if (at >= 0) {
                json = insertEntry(text, at, ind -> ind + quote(name) + ": " + row);
            } else {
                json = insertEntry(text, root, ind -> ind + "\"features\": {\n" + ind + "  " + quote(name) + ": " + row + "\n" + ind + "}");
            }
            parseJson(json, featuresFile);
        } else {
            JsonObject o = new JsonObject();
            o.addProperty("schema", 1);
            JsonObject features = new JsonObject();
            features.add(name, JsonParser.parseString(row));
            o.add("features", features);
            json = BindingFiles.format(o);
        }
        return commit(List.of(new Write(enumFile, enumText), new Write(featuresFile, json)));
    }

    private String addConstant(String text, Path file, String name) {
        Matcher m = Pattern.compile("\\benum\\s+Feature\\b").matcher(text);
        if (!m.find()) throw bad("fix " + file + ": it has no enum Feature");
        int open = text.indexOf('{', m.end());
        if (open < 0) throw bad("fix " + file + ": enum Feature has no body");
        int close = matchEnd(text, open, file);
        int limit = close;
        int depth = 0;
        for (int i = open + 1; i < close; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = endOfString(text, i);
            } else if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                depth--;
            } else if (c == ';' && depth == 0) {
                limit = i;
                break;
            }
        }
        if (Pattern.compile("(?<![A-Za-z0-9_])" + name + "(?![A-Za-z0-9_])").matcher(text.substring(open + 1, close)).find()) {
            throw bad("pick another --name: " + file + " already has constant " + name);
        }
        String nl = newline(text);
        int p = limit - 1;
        while (p > open && Character.isWhitespace(text.charAt(p))) p--;
        boolean comma = p > open && text.charAt(p) == ',';
        String ins = (p == open || comma ? "" : ",") + nl + "    " + name + (comma ? "," : "");
        if (!text.substring(p + 1, limit).contains("\n")) ins += nl;
        return text.substring(0, p + 1) + ins + text.substring(p + 1);
    }

    // ------------------------------------------------------------------------------------------------ target

    /** Adds the {@code versions} row of one release to targets.json. {@code supported} is left to updateTargets. */
    public List<Path> target(String mc, int java) {
        created.clear();
        Version v;
        try {
            v = Version.parse(mc);
        } catch (IllegalArgumentException e) {
            throw bad("pass --mc as a release id such as 1.21.4: " + e.getMessage());
        }
        Path file = bindings.resolve("targets.json");
        Targets current = Targets.parse(parseJson(read(file), file), file.toString());   // the file may have changed since this object was made
        if (current.versions().containsKey(mc)) {
            throw bad("pick another --mc: targets.json already has a row for " + mc);
        }
        if (current.versions().isEmpty()) throw bad("add one versions row to targets.json by hand first; there is no row to copy adapters from");

        String below = null;
        Version belowV = null;
        String above = null;
        Version aboveV = null;
        for (String id : current.versions().keySet()) {
            Version o = Version.parse(id);
            if (o.compareTo(v) < 0) {
                if (belowV == null || o.compareTo(belowV) > 0) {
                    below = id;
                    belowV = o;
                }
            } else if (aboveV == null || o.compareTo(aboveV) < 0) {
                above = id;
                aboveV = o;
            }
        }
        String adapters = current.versions().get(below != null ? below : above).adapters();

        StringBuilder maps = new StringBuilder();
        if (v.compareTo(Version.parse("1.13.2")) <= 0) {
            maps.append("\"lf\": ").append(quote("net.legacyfabric:intermediary:" + mc + ":v2"));
        } else if (v.compareTo(Version.parse("26.1")) >= 0) {
            maps.append("\"official\": true");
        } else {
            if (v.compareTo(Version.parse("1.14.4")) >= 0) maps.append("\"mojang\": true, ");
            maps.append("\"intermediary\": ").append(quote("net.fabricmc:intermediary:" + mc + ":v2"));
        }
        String row = "{ \"java\": " + java + ", \"mappings\": { " + maps + " }, \"adapters\": " + quote(adapters) + " }";

        String text = read(file);
        int root = rootOpen(text, file);
        int versions = valueSpan(text, root, "versions");
        if (versions < 0 || text.charAt(versions) != '{') throw bad("fix " + file + ": it needs a \"versions\" object");
        String out = insertEntry(text, versions, ind -> ind + quote(mc) + ": " + row);
        try {
            Targets.parse(JsonParser.parseString(out), file.toString());
        } catch (RuntimeException e) {
            throw bad("pass a --java level of 8 to 25 and a release id: " + Lookup.describe(e));
        }
        return commit(List.of(new Write(file, out)));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void requireUpper(String name, String option) {
        if (name == null || !UPPER_NAME.matcher(name).matches()) {
            throw bad("pass " + option + " as UPPER_SNAKE_CASE such as HUD_RENDER, found '" + name + "'");
        }
    }

    private List<Path> commit(List<Write> writes) {
        List<Path> out = new ArrayList<>();
        try {
            for (Write w : writes) {
                boolean existed = Files.exists(w.path);
                if (w.path.getParent() != null) Files.createDirectories(w.path.getParent());
                Files.writeString(w.path, w.text, StandardCharsets.UTF_8);
                if (!existed) created.add(w.path);
                out.add(w.path);
            }
        } catch (IOException e) {
            throw bad("make the folder writable and rerun: " + e.getMessage());
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw bad("make " + file + " readable: " + e.getMessage());
        }
    }

    private static JsonElement parseJson(String text, Path file) {
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw bad("fix the JSON syntax of " + file + ": " + e.getMessage());
        }
    }

    private static TypeBinding parseType(String text, Path file) {
        return parseType(text, file, parseJson(text, file));
    }

    private static TypeBinding parseType(String text, Path file, JsonElement tree) {
        try {
            return TypeBinding.parse(tree, file.toString());
        } catch (RuntimeException e) {
            throw bad("fix " + file + ": " + Lookup.describe(e));
        }
    }

    private static String newline(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    // ------------------------------------------------------------------------------------------------ JSON text scanner

    private static int rootOpen(String text, Path file) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= text.length() || text.charAt(i) != '{') throw bad("fix " + file + ": the root must be an object");
        return i;
    }

    /** Index of the closing quote of the string that opens at {@code open}. */
    private static int endOfString(String text, int open) {
        int j = open + 1;
        while (j < text.length()) {
            char c = text.charAt(j);
            if (c == '\\') {
                j += 2;
            } else if (c == '"') {
                return j;
            } else {
                j++;
            }
        }
        throw bad("fix the JSON: unterminated string");
    }

    /** Index of the bracket that closes the one at {@code open}; strings are skipped. */
    private static int matchEnd(String text, int open, Path file) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = endOfString(text, i);
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw bad("fix " + file + ": unbalanced brackets");
    }

    /** Start of the value of the property {@code key} of the object at {@code open}, or -1 when it has none. */
    private static int valueSpan(String text, int open, String key) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = endOfString(text, i);
                if (depth == 1) {
                    int k = end + 1;
                    while (k < text.length() && Character.isWhitespace(text.charAt(k))) k++;
                    if (k < text.length() && text.charAt(k) == ':' && text.substring(i + 1, end).equals(key)) {
                        k++;
                        while (k < text.length() && Character.isWhitespace(text.charAt(k))) k++;
                        return k;
                    }
                }
                i = end;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) return -1;
            }
        }
        return -1;
    }

    /**
     * Appends one property to the object at {@code open}, after its last property, leaving every existing character in
     * place. {@code entry} gets the indent of the new line and returns the property text (lines separated by "\n").
     */
    private static String insertEntry(String text, int open, Function<String, String> entry) {
        int close = matchEnd(text, open, Path.of("JSON"));
        int lineStart = text.lastIndexOf('\n', open) + 1;
        int w = lineStart;
        while (w < text.length() && (text.charAt(w) == ' ' || text.charAt(w) == '\t')) w++;
        String ind = text.substring(lineStart, w);
        String nl = newline(text);
        String e = entry.apply(ind + "  ").replace("\n", nl);
        int p = close - 1;
        while (p > open && Character.isWhitespace(text.charAt(p))) p--;
        if (p == open) {
            return text.substring(0, open + 1) + nl + e + nl + ind + text.substring(close);
        }
        return text.substring(0, p + 1) + "," + nl + e + text.substring(p + 1);
    }
}
