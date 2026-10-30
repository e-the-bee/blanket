// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Strict JSON access for binding files: every object is checked for unknown keys, every value for its type,
 * and every error names the file and the JSON path (binding-format.md section 1).
 */
final class Json {
    private Json() {}

    static final int SCHEMA = 1;

    /** Checks the top-level object: {@code schema} must be 1, and only {@code allowed} keys may appear. */
    static JsonObject root(JsonElement e, String file, String... allowed) {
        JsonObject o = obj(e, file, "$");
        checkKeys(o, file, "$", allowed, "schema");
        JsonElement s = o.get("schema");
        if (s == null) {
            throw new BindingException(file, "$", "missing \"schema\" (expected 1)");
        }
        if (!isInt(s) || s.getAsInt() != SCHEMA) {
            throw new BindingException(file, "$.schema", "expected schema 1, found " + s);
        }
        return o;
    }

    static JsonObject obj(JsonElement e, String file, String path) {
        if (e == null || !e.isJsonObject()) {
            throw new BindingException(file, path, "expected a JSON object");
        }
        return e.getAsJsonObject();
    }

    static JsonArray arr(JsonElement e, String file, String path) {
        if (e == null || !e.isJsonArray()) {
            throw new BindingException(file, path, "expected a JSON array");
        }
        return e.getAsJsonArray();
    }

    /** Rejects any key of {@code o} that is not in {@code allowed}. {@code allowed} may include "schema". */
    static void checkKeys(JsonObject o, String file, String path, String[] allowed, String... extra) {
        Set<String> ok = new HashSet<String>(Arrays.asList(allowed));
        for (String x : extra) {
            ok.add(x);
        }
        for (String key : o.keySet()) {
            if (!ok.contains(key)) {
                throw new BindingException(file, path + "." + key, "unknown key '" + key + "' (allowed: " + sorted(ok) + ")");
            }
        }
    }

    static String str(JsonObject o, String key, String file, String path) {
        JsonElement e = o.get(key);
        if (e == null) {
            throw new BindingException(file, path + "." + key, "missing \"" + key + "\"");
        }
        return str(e, file, path + "." + key);
    }

    static String str(JsonElement e, String file, String path) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new BindingException(file, path, "expected a string");
        }
        return e.getAsString();
    }

    /** A non-empty string. */
    static String nonEmpty(JsonObject o, String key, String file, String path) {
        String s = str(o, key, file, path);
        if (s.isEmpty()) {
            throw new BindingException(file, path + "." + key, "must not be empty");
        }
        return s;
    }

    static String optStr(JsonObject o, String key, String file, String path) {
        JsonElement e = o.get(key);
        return e == null ? null : str(e, file, path + "." + key);
    }

    static boolean bool(JsonElement e, String file, String path) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new BindingException(file, path, "expected true or false");
        }
        return e.getAsBoolean();
    }

    static int integer(JsonElement e, String file, String path) {
        if (!isInt(e)) {
            throw new BindingException(file, path, "expected an integer");
        }
        return e.getAsInt();
    }

    static boolean isInt(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            Integer.parseInt(e.getAsString());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** One of the given values (exact, case-sensitive). */
    static String oneOf(JsonElement e, Set<String> values, String file, String path) {
        String s = str(e, file, path);
        if (!values.contains(s)) {
            throw new BindingException(file, path, "'" + s + "' is not one of " + sorted(values));
        }
        return s;
    }

    static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    static String sorted(Set<String> values) {
        return new java.util.TreeSet<String>(values).toString();
    }

    static JsonPrimitive prim(String s) {
        return new JsonPrimitive(s);
    }
}
