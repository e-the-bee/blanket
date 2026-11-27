// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.resolve.TargetSpec;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where {@code lookup} finds the loader artifact of a target for a {@code loader:} reference: the Forge or NeoForge
 * universal jar named in {@code loader-artifacts.json} (the same file and the same jars {@code validateBindings} C7
 * reads; build-and-gradle.md section 4).
 */
@FunctionalInterface
public interface LoaderJars {

    /** No target has a loader artifact. */
    LoaderJars NONE = t -> List.of();

    /** The jars to look in for {@code target}, or an empty list when the target has no loader artifact. */
    List<Path> jars(TargetSpec target) throws IOException;

    /**
     * Reads {@code file} (a flat object from target id to artifact version; a missing file means no artifacts) and
     * fetches the jar of a Forge or NeoForge target on first use, through {@code downloads}.
     */
    static LoaderJars fromFile(MappingDownloads downloads, Path file) {
        Map<String, String> versions = new HashMap<>();
        if (Files.isRegularFile(file)) {
            JsonElement root = BindingFiles.readJson(file);
            if (!root.isJsonObject()) throw new IllegalArgumentException(file + ": expected an object from target id to artifact version");
            JsonObject o = root.getAsJsonObject();
            for (String id : o.keySet()) versions.put(id, o.get(id).getAsString());
        }
        return target -> {
            String version = versions.get(target.id());
            boolean forge = target.loader().equals("forge");
            boolean neo = target.loader().equals("neoforge");
            if (version == null || !(forge || neo)) return List.of();
            try {
                return List.of(neo ? downloads.neoForgeUniversal(version) : downloads.forgeUniversal(version));
            } catch (RuntimeException e) {
                throw new IOException("cannot obtain the loader jar of " + target.id() + ": " + e.getMessage(), e);
            }
        };
    }
}
