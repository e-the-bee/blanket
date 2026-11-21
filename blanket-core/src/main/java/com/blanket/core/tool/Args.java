// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.tool;

import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.mapping.MappingDownloads;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code --name value} command lines of the contributor tools ({@code lookup}, {@code scaffold},
 * {@code validateBindings}). A name given twice keeps every value; a name listed as a flag takes no value.
 */
public final class Args {
    private final Map<String, List<String>> values = new LinkedHashMap<>();

    private Args() {}

    /** @throws IllegalArgumentException for a value without a name, or a name without its value */
    public static Args parse(String[] argv, Set<String> flags) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String s = argv[i];
            if (!s.startsWith("--") || s.length() == 2) {
                throw new IllegalArgumentException("unexpected argument '" + s + "'; options look like --name value");
            }
            String name = s.substring(2);
            String value = "true";
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            } else if (!flags.contains(name)) {
                if (i + 1 >= argv.length) throw new IllegalArgumentException("--" + name + " needs a value");
                value = argv[++i];
            }
            a.values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return a;
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    /** The last value of {@code name}, or null. */
    public String get(String name) {
        List<String> v = values.get(name);
        return v == null ? null : v.get(v.size() - 1);
    }

    public String get(String name, String fallback) {
        String v = get(name);
        return v == null ? fallback : v;
    }

    /** The value of a required option. */
    public String require(String name) {
        String v = get(name);
        if (v == null) throw new IllegalArgumentException("--" + name + " is required");
        return v;
    }

    /** Every value of {@code name}, each split at commas. */
    public List<String> all(String name) {
        List<String> out = new ArrayList<>();
        for (String v : values.getOrDefault(name, List.of())) {
            for (String part : v.split(",")) {
                if (!part.trim().isEmpty()) out.add(part.trim());
            }
        }
        return out;
    }

    /**
     * The downloads of the Blanket cache: {@code --cache <dir>}, else the {@code BLANKET_CACHE} environment variable, else
     * {@code ~/.blanket/cache} (mappings-and-versions.md section 9); and {@code --offline}.
     */
    public MappingDownloads downloads() {
        String env = System.getenv("BLANKET_CACHE");
        Path cache = has("cache") ? Path.of(get("cache")) : env != null && !env.isBlank() ? Path.of(env) : ArtifactFetcher.defaultCacheRoot();
        return new MappingDownloads(new ArtifactFetcher(cache, has("offline")));
    }
}
