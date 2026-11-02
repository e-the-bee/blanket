// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.mapping;

import com.blanket.core.fetch.ArtifactFetcher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real mapping files (Mojang 1.21.1 ProGuard, Fabric intermediary 1.21.1). Downloaded once into the Blanket cache;
 * the test is skipped when the files cannot be obtained (offline).
 */
class MappingRealDataTest {

    private static MappingDownloads downloads() {
        return new MappingDownloads(new ArtifactFetcher(ArtifactFetcher.defaultCacheRoot(), false));
    }

    private static Path obtain(java.util.function.Supplier<Path> fetch) {
        try {
            return fetch.get();
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "mapping file unavailable (offline?): " + e.getMessage());
            return null;
        }
    }

    @Test
    void mojangProGuardAndIntermediaryAgreeOnMinecraftAt1211() throws Exception {
        MappingDownloads d = downloads();
        Path proguard = obtain(() -> d.clientMappings("1.21.1"));
        Path intermediary = obtain(() -> d.intermediary("1.21.1", false));

        MappingFile pg = MappingFile.open(proguard, "named", "obf");
        MappingIndex pgIdx = pg.read(Set.of(pg.key("named", "net/minecraft/client/Minecraft", "getInstance")));
        // binding-format.md section 8, example 1: Mojang Minecraft is obf fgo, getInstance is obf Q (verified)
        assertEquals("fgo", pgIdx.mapClass("named", "net/minecraft/client/Minecraft", "obf"));
        MappingIndex.MemberRow getInstance = pgIdx.member("named", "net/minecraft/client/Minecraft", "getInstance",
                "()Lnet/minecraft/client/Minecraft;");
        assertNotNull(getInstance);
        assertEquals("Q", getInstance.name(1));

        MappingFile in = MappingFile.open(intermediary, "obf", "id");
        MappingIndex inIdx = in.read(Set.of(in.key("obf", "fgo", "Q")));
        assertEquals("net/minecraft/class_310", inIdx.mapClass("obf", "fgo", "id"));
        MappingIndex.MemberRow id = inIdx.member("obf", "fgo", "Q", "()Lfgo;");
        assertNotNull(id);
        assertEquals("method_1551", id.name(1));
    }
}
