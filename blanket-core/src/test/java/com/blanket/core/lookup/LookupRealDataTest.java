// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.bindings.Targets;
import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.mapping.MappingDownloads;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Lookup} against the real mapping files, compared with M0's {@code tools/names.py}. The expected rows are what
 * {@code python blanket-emit/m0-spike/tools/names.py <target> net.minecraft.world.entity.Entity#getX} printed on
 * 2026-11-20 for {@code fabric1211}, {@code neo1211} and {@code forge1201}: the overload {@code getX()D} maps to
 * {@code class_1297.method_23317}, {@code Entity.getX} and {@code Entity.m_20185_}, and {@code getX(D)D} to
 * {@code method_23316}, {@code getX} and {@code m_20165_}.
 */
class LookupRealDataTest {

    @Test
    void entityGetXMatchesNamesPyOnThreeTargets() {
        Targets targets = BindingFiles.readTargets(Path.of("..", "blanket-bindings", "targets.json"));
        MappingDownloads downloads = new MappingDownloads(new ArtifactFetcher(ArtifactFetcher.defaultCacheRoot(), false));
        List<Lookup.Result> results;
        try {
            results = new Lookup(targets, MappingSources.files(downloads, targets))
                    .lookup("net.minecraft.world.entity.Entity#getX", List.of("fabric:1.21.1", "neoforge:1.21.1", "forge:1.20.1"));
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "mapping data unavailable (offline?): " + e.getMessage());
            return;
        }
        Map<String, String> got = results.stream().flatMap(r -> r.rows().stream().map(row -> r.ref() + " " + row.target() + " "
                + (row.ok() ? row.owner() + "." + row.name() + " " + row.desc() : "FAIL " + row.message())))
                .collect(Collectors.toMap(x -> x, x -> x));
        for (String expected : List.of(
                "net/minecraft/world/entity/Entity#getX()D fabric:1.21.1 net/minecraft/class_1297.method_23317 ()D",
                "net/minecraft/world/entity/Entity#getX(D)D fabric:1.21.1 net/minecraft/class_1297.method_23316 (D)D",
                "net/minecraft/world/entity/Entity#getX()D neoforge:1.21.1 net/minecraft/world/entity/Entity.getX ()D",
                "net/minecraft/world/entity/Entity#getX(D)D neoforge:1.21.1 net/minecraft/world/entity/Entity.getX (D)D",
                "net/minecraft/world/entity/Entity#getX()D forge:1.20.1 net/minecraft/world/entity/Entity.m_20185_ ()D",
                "net/minecraft/world/entity/Entity#getX(D)D forge:1.20.1 net/minecraft/world/entity/Entity.m_20165_ (D)D")) {
            assertTrue(got.containsKey(expected), expected + " is missing from " + got.keySet());
        }
        assertEquals(6, got.size(), got.keySet().toString());
    }
}
