// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import com.blanket.core.bindings.BindingException;
import com.blanket.core.bindings.BindingFiles;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code build/adapters/<set>/adapter-refs.json}, written by {@code remapAdapters} beside the remapped classes of one
 * adapter set (M2 plan section 3). Every name is a shipping name ({@link #namespace()}), internal form.
 *
 * <ul>
 * <li>{@code declaring}: member references in the set's classes whose bytecode owner is not the class that carries
 *     the member's mapping row, keyed {@code owner#name desc} (methods) or {@code owner#name:desc} (fields), valued
 *     with that class. A mapping file has rows only on declaring classes (Fabric intermediary: only on the root of a
 *     method's override family), so a resolver looks the member up on the value, not on the owner. Only references
 *     where the owner or the declaring class is a game class are listed.</li>
 * <li>{@code overrides}: adapter method declarations that override a game method, keyed
 *     {@code com/blanket/adapters/<set>/<Class>#name desc}, valued with the game class that carries the overridden
 *     method's mapping row. At generate time the declaration is renamed like a reference to that class.</li>
 * </ul>
 */
public record AdapterRefs(String set, String namespace, Map<String, String> declaring, Map<String, String> overrides) {
    public static final int SCHEMA = 1;
    public static final String FILE = "adapter-refs.json";

    private static final Set<String> KEYS = Set.of("schema", "set", "namespace", "declaring", "overrides");

    public AdapterRefs {
        declaring = Collections.unmodifiableMap(new TreeMap<>(declaring));
        overrides = Collections.unmodifiableMap(new TreeMap<>(overrides));
    }

    /** {@code owner#name desc} for a method. */
    public static String methodKey(String owner, String name, String desc) {
        return owner + '#' + name + desc;
    }

    /** {@code owner#name:desc} for a field. */
    public static String fieldKey(String owner, String name, String desc) {
        return owner + '#' + name + ':' + desc;
    }

    /** The JSON text: keys in the order of the contract, maps sorted, so equal refs give equal bytes. */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("set", set);
        root.addProperty("namespace", namespace);
        root.add("declaring", object(declaring));
        root.add("overrides", object(overrides));
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    private static JsonObject object(Map<String, String> map) {
        JsonObject o = new JsonObject();
        map.forEach(o::addProperty);
        return o;
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
    }

    /** Reads a file written by {@link #write}; an unknown key, another schema or a non-string value is an error naming the file. */
    public static AdapterRefs read(Path file) {
        String f = file.toString();
        JsonElement root = BindingFiles.readJson(file);
        if (!root.isJsonObject()) throw new BindingException(f, "$", "expected an object");
        JsonObject o = root.getAsJsonObject();
        for (String key : o.keySet()) {
            if (!KEYS.contains(key)) throw new BindingException(f, "$." + key, "unknown key; allowed: " + new TreeSet<>(KEYS));
        }
        JsonElement schema = o.get("schema");
        if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber() || schema.getAsInt() != SCHEMA) {
            throw new BindingException(f, "$.schema", "expected " + SCHEMA);
        }
        return new AdapterRefs(text(o, "set", f), text(o, "namespace", f), map(o, "declaring", f), map(o, "overrides", f));
    }

    private static String text(JsonObject o, String key, String file) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new BindingException(file, "$." + key, "expected a string");
        }
        return e.getAsString();
    }

    private static Map<String, String> map(JsonObject o, String key, String file) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonObject()) throw new BindingException(file, "$." + key, "expected an object");
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
            JsonElement v = entry.getValue();
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                throw new BindingException(file, "$." + key + "." + entry.getKey(), "expected a string");
            }
            out.put(entry.getKey(), v.getAsString());
        }
        return out;
    }
}
