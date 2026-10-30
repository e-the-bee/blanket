// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A target selector, the {@code when} of binding-format.md section 2.1 and of {@code blanket-libs.json}:
 * {@code { "loader": "fabric" | [...], "mc": "<version-match>" }}. An omitted key matches anything, and all
 * given keys must match.
 */
public final class Selector {
    /** Loader ids (loader-emitters.md). */
    public static final Set<String> LOADERS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList("fabric", "legacy-fabric", "neoforge", "forge")));

    /** The selector that matches every target. */
    public static final Selector ANY = new Selector(null, null);

    private final Set<String> loaders;
    private final VersionMatch mc;

    private Selector(Set<String> loaders, VersionMatch mc) {
        this.loaders = loaders;
        this.mc = mc;
    }

    /**
     * Parses a {@code when} object. {@code file} and {@code jsonPath} (for example {@code $.types.Player.members.x[0].when})
     * are used in the error message, which has the form {@code file: jsonPath: reason}.
     */
    public static Selector parse(JsonElement element, String file, String jsonPath) {
        if (element == null || !element.isJsonObject()) {
            throw new BindingException(file, jsonPath, "when must be a JSON object");
        }
        JsonObject o = element.getAsJsonObject();
        Set<String> loaders = null;
        VersionMatch mc = null;
        for (String key : o.keySet()) {
            String at = jsonPath + "." + key;
            JsonElement v = o.get(key);
            if (key.equals("loader")) {
                loaders = parseLoaders(v, file, at);
            } else if (key.equals("mc")) {
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                    throw new BindingException(file, at, "mc must be a string");
                }
                mc = VersionMatch.parse(v.getAsString(), file, at);
            } else {
                throw new BindingException(file, at, "unknown key '" + key + "' (allowed: loader, mc)");
            }
        }
        if (loaders == null && mc == null) {
            return ANY;
        }
        return new Selector(loaders, mc);
    }

    /** Parses a selector from JSON text. */
    public static Selector parse(String json, String file, String jsonPath) {
        JsonElement e;
        try {
            e = com.google.gson.JsonParser.parseString(json);
        } catch (RuntimeException ex) {
            throw new BindingException(file, jsonPath, "not valid JSON: " + ex.getMessage(), ex);
        }
        return parse(e, file, jsonPath);
    }

    private static Set<String> parseLoaders(JsonElement v, String file, String at) {
        Set<String> out = new LinkedHashSet<String>();
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
            out.add(checkLoader(v.getAsString(), file, at));
        } else if (v.isJsonArray()) {
            JsonArray arr = v.getAsJsonArray();
            if (arr.size() == 0) {
                throw new BindingException(file, at, "loader list is empty");
            }
            for (int i = 0; i < arr.size(); i++) {
                JsonElement item = arr.get(i);
                String p = at + "[" + i + "]";
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                    throw new BindingException(file, p, "loader must be a string");
                }
                out.add(checkLoader(item.getAsString(), file, p));
            }
        } else {
            throw new BindingException(file, at, "loader must be a string or a list of strings");
        }
        return Collections.unmodifiableSet(out);
    }

    private static String checkLoader(String id, String file, String at) {
        if (!LOADERS.contains(id)) {
            throw new BindingException(file, at, "unknown loader '" + id + "' (expected one of " + LOADERS + ")");
        }
        return id;
    }

    /** True if the target {@code loader} and release {@code mc} satisfy this selector. */
    public boolean matches(String loader, Version mcVersion) {
        if (loaders != null && !loaders.contains(loader)) {
            return false;
        }
        return mc == null || mc.matches(mcVersion);
    }

    /** Loader ids this selector names, or null for any loader. */
    public Set<String> loaders() {
        return loaders;
    }

    @Override
    public String toString() {
        if (this == ANY || (loaders == null && mc == null)) {
            return "{}";
        }
        StringBuilder b = new StringBuilder("{");
        if (loaders != null) {
            b.append("loader=").append(loaders);
        }
        if (mc != null) {
            if (loaders != null) {
                b.append(' ');
            }
            b.append("mc=").append(mc);
        }
        return b.append('}').toString();
    }
}
