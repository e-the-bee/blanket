// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.resolve;

import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.mapping.MappingDownloads;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Presence, interface-versus-class and static-versus-instance answers. The 1.21.1 client jar is obfuscated (Minecraft is
 * fgo, getInstance is Q: binding-format.md section 8), and 26.1 ships unobfuscated, so each test uses the jar whose names
 * it states.
 */
class ClassIndexTest {

    private static Path clientJar(String mc) {
        try {
            return new MappingDownloads(new ArtifactFetcher(ArtifactFetcher.defaultCacheRoot(), false)).clientJar(mc);
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "client jar " + mc + " unavailable (offline?): " + e.getMessage());
            return null;
        }
    }

    @Test
    void presentAndAbsentClassesInAnObfuscatedJar() throws IOException {
        try (ClassIndex idx = ClassIndex.open(List.of(clientJar("1.21.1")), true)) {
            assertTrue(idx.exists("fgo"), "Minecraft is fgo in the 1.21.1 jar");
            assertFalse(idx.exists("net/minecraft/client/Minecraft"), "Mojang names are not in the obfuscated jar");
            assertFalse(idx.exists("net/minecraft/NoSuchClassAnywhere"));
            assertTrue(idx.exists("java/lang/String"), "the running JDK is the last source");
        }
    }

    @Test
    void staticMethodOfAnObfuscatedClass() throws IOException {
        try (ClassIndex idx = ClassIndex.open(List.of(clientJar("1.21.1")), false)) {
            Integer getInstance = idx.get("fgo").orElseThrow().methods().get("Q()Lfgo;");
            assertTrue(getInstance != null && (getInstance & Opcodes.ACC_STATIC) != 0, "getInstance (Q) is static");
        }
    }

    @Test
    void interfaceAndClassOwners() throws IOException {
        try (ClassIndex idx = ClassIndex.open(List.of(clientJar("26.1.2")), true)) {
            assertTrue(idx.get("net/minecraft/network/chat/Component").orElseThrow().isInterface());
            assertFalse(idx.get("net/minecraft/client/Minecraft").orElseThrow().isInterface());
            assertTrue(idx.get("java/util/List").orElseThrow().isInterface());
        }
    }

    @Test
    void staticAndInstanceMembers() throws IOException {
        try (ClassIndex idx = ClassIndex.open(List.of(clientJar("26.1.2")), false)) {
            ClassIndex.Info mc = idx.get("net/minecraft/client/Minecraft").orElseThrow();
            Integer getInstance = mc.methods().get("getInstance()Lnet/minecraft/client/Minecraft;");
            assertTrue(getInstance != null && (getInstance & Opcodes.ACC_STATIC) != 0, "getInstance is static");
            Integer tick = mc.methods().get("tick()V");
            assertTrue(tick != null && (tick & Opcodes.ACC_STATIC) == 0, "tick is an instance method");
            Integer player = mc.fields().get("player:Lnet/minecraft/client/player/LocalPlayer;");
            assertEquals(true, player != null && (player & Opcodes.ACC_STATIC) == 0, "player is an instance field");
        }
    }
}
