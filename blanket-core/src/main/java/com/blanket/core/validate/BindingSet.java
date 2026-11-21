// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.bindings.EventBinding;
import com.blanket.core.bindings.Hooks;
import com.blanket.core.bindings.Targets;
import com.blanket.core.bindings.TypeBinding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Every binding file of one bindings folder, read and parsed. Keys are the paths the files are named by in messages
 * ({@code types/Player.json}, {@code glue/GlueFrame.json}, {@code events/ClientTick.json}).
 *
 * @param hooks hooks.json, or null when the folder has none
 */
public record BindingSet(Path dir, Targets targets, Map<String, TypeBinding> types, Map<String, TypeBinding> glue,
                         Hooks hooks, Map<String, EventBinding> events) {

    /** Reads {@code targets.json}, {@code hooks.json} (optional) and every file under {@code types/}, {@code glue/}, {@code events/}. */
    public static BindingSet load(Path dir) throws IOException {
        Targets targets = Targets.parse(BindingFiles.readJson(dir.resolve("targets.json")), "targets.json");
        Path hooksFile = dir.resolve("hooks.json");
        Hooks hooks = Files.isRegularFile(hooksFile) ? Hooks.parse(BindingFiles.readJson(hooksFile), "hooks.json") : null;
        Map<String, TypeBinding> types = new LinkedHashMap<>();
        for (Path p : json(dir.resolve("types"))) {
            String name = "types/" + p.getFileName();
            types.put(name, TypeBinding.parse(BindingFiles.readJson(p), name));
        }
        Map<String, TypeBinding> glue = new LinkedHashMap<>();
        for (Path p : json(dir.resolve("glue"))) {
            String name = "glue/" + p.getFileName();
            glue.put(name, TypeBinding.parse(BindingFiles.readJson(p), name));
        }
        Map<String, EventBinding> events = new LinkedHashMap<>();
        for (Path p : json(dir.resolve("events"))) {
            String name = "events/" + p.getFileName();
            events.put(name, EventBinding.parse(BindingFiles.readJson(p), name));
        }
        return new BindingSet(dir, targets, types, glue, hooks, events);
    }

    private static List<Path> json(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> s = Files.list(folder)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    /** Type files and glue files together, in that order. */
    public Map<String, TypeBinding> typesAndGlue() {
        Map<String, TypeBinding> all = new LinkedHashMap<>(types);
        all.putAll(glue);
        return all;
    }
}
