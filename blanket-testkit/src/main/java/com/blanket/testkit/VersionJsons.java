// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/** Launcher version-JSON rules: {@code rules} lists, Maven names and the {@code inheritsFrom} merge. */
public final class VersionJsons {
    private VersionJsons() {
    }

    /**
     * Whether a {@code rules} list allows the entry on {@code os} (64-bit x86). The last matching rule wins; a rule with
     * {@code features} never matches (no demo, custom resolution or quick-play features are claimed); an {@code os}
     * rule matches when its name is absent or equal and its arch is absent or {@code x86_64}.
     */
    public static boolean rulesAllow(JsonArray rules, String os) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean ok = false;
        for (JsonElement e : rules) {
            JsonObject r = e.getAsJsonObject();
            boolean match;
            if (r.has("features")) {
                match = false;
            } else if (r.has("os")) {
                JsonObject o = r.getAsJsonObject("os");
                String name = str(o, "name");
                String arch = str(o, "arch");
                match = (name == null || name.equals(os)) && (arch == null || arch.equals("x86_64"));
            } else {
                match = true;
            }
            if (match) {
                ok = "allow".equals(str(r, "action"));
            }
        }
        return ok;
    }

    /** {@code group:artifact:version[:classifier][@ext]} to its repository path. */
    public static String mavenPath(String name) {
        String ext = "jar";
        int at = name.indexOf('@');
        if (at >= 0) {
            ext = name.substring(at + 1);
            name = name.substring(0, at);
        }
        String[] p = name.split(":");
        String classifier = p.length > 3 ? "-" + p[3] : "";
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + classifier + "." + ext;
    }

    /** A library's identity for overriding: the name without its version when it has exactly three parts. */
    static String libraryKey(String name) {
        String[] p = name.split(":", -1);
        return p.length == 3 ? p[0] + ":" + p[1] : name;
    }

    /**
     * Merges a child version JSON over its already merged parent: the child's libraries come first and replace the
     * parent's of the same key; {@code mainClass}, {@code minecraftArguments} and {@code id} come from the child;
     * {@code arguments.game}/{@code jvm} are the parent's followed by the child's; {@code jar} names the parent's
     * client jar. Everything else is the parent's.
     */
    public static JsonObject merge(JsonObject child, JsonObject parent) {
        JsonObject out = parent.deepCopy();
        JsonArray childLibs = child.has("libraries") ? child.getAsJsonArray("libraries") : new JsonArray();
        Set<String> names = new HashSet<>();
        for (JsonElement l : childLibs) {
            names.add(libraryKey(l.getAsJsonObject().get("name").getAsString()));
        }
        JsonArray libs = childLibs.deepCopy();
        if (parent.has("libraries")) {
            for (JsonElement l : parent.getAsJsonArray("libraries")) {
                if (!names.contains(libraryKey(l.getAsJsonObject().get("name").getAsString()))) {
                    libs.add(l.deepCopy());
                }
            }
        }
        out.add("libraries", libs);
        for (String k : new String[] {"mainClass", "minecraftArguments", "id"}) {
            if (child.has(k)) {
                out.add(k, child.get(k).deepCopy());
            }
        }
        if (child.has("arguments")) {
            JsonObject pa = parent.has("arguments") ? parent.getAsJsonObject("arguments") : new JsonObject();
            JsonObject ca = child.getAsJsonObject("arguments");
            JsonObject a = new JsonObject();
            for (String k : new String[] {"game", "jvm"}) {
                JsonArray list = pa.has(k) ? pa.getAsJsonArray(k).deepCopy() : new JsonArray();
                if (ca.has(k)) {
                    list.addAll(ca.getAsJsonArray(k).deepCopy());
                }
                a.add(k, list);
            }
            out.add("arguments", a);
        }
        out.addProperty("jar", parent.has("jar") ? parent.get("jar").getAsString() : parent.get("id").getAsString());
        return out;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
