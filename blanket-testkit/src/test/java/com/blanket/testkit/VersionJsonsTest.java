// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionJsonsTest {
    private static JsonArray rules(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static List<String> names(JsonObject v) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : v.getAsJsonArray("libraries")) {
            out.add(e.getAsJsonObject().get("name").getAsString());
        }
        return out;
    }

    @Test
    void rules() {
        assertTrue(VersionJsons.rulesAllow(null, "linux"));
        assertTrue(VersionJsons.rulesAllow(new JsonArray(), "linux"));
        JsonArray notOsx = rules("[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"osx\"}}]");
        assertTrue(VersionJsons.rulesAllow(notOsx, "linux"));
        assertFalse(VersionJsons.rulesAllow(notOsx, "osx"));
        JsonArray onlyLinux = rules("[{\"action\":\"allow\",\"os\":{\"name\":\"linux\"}}]");
        assertTrue(VersionJsons.rulesAllow(onlyLinux, "linux"));
        assertFalse(VersionJsons.rulesAllow(onlyLinux, "windows"));
        // 32-bit and ARM rules never apply (64-bit x86 only); x86_64 does
        assertFalse(VersionJsons.rulesAllow(rules("[{\"action\":\"allow\",\"os\":{\"arch\":\"x86\"}}]"), "linux"));
        assertFalse(VersionJsons.rulesAllow(
                rules("[{\"action\":\"allow\",\"os\":{\"name\":\"linux\",\"arch\":\"arm64\"}}]"), "linux"));
        assertTrue(VersionJsons.rulesAllow(
                rules("[{\"action\":\"allow\",\"os\":{\"name\":\"linux\",\"arch\":\"x86_64\"}}]"), "linux"));
        // features (demo, custom resolution, quick play) are never claimed
        assertFalse(VersionJsons.rulesAllow(
                rules("[{\"action\":\"allow\",\"features\":{\"is_demo_user\":true}}]"), "linux"));
    }

    @Test
    void mavenPaths() {
        assertEquals("org/lwjgl/lwjgl/lwjgl-platform/2.9.4+legacyfabric.17/lwjgl-platform-2.9.4+legacyfabric.17"
                + "-natives-linux.jar", VersionJsons.mavenPath("org.lwjgl.lwjgl:lwjgl-platform:2.9.4+legacyfabric.17"
                + ":natives-linux"));
        assertEquals("net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9.jar",
                VersionJsons.mavenPath("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9"));
        assertEquals("a/b/c/1/c-1.zip", VersionJsons.mavenPath("a.b:c:1@zip"));
        assertEquals("a/b/c/1/c-1-x.zip", VersionJsons.mavenPath("a.b:c:1:x@zip"));
    }

    @Test
    void legacyFabricReplacesVanillaLwjgl() {
        JsonObject m = VersionJsons.merge(Fixtures.version(Fixtures.LF_189), Fixtures.version("1.8.9"));
        List<String> n = names(m);
        // the profile's libraries come first
        assertEquals("org.ow2.asm:asm:9.10.1", n.get(0));
        assertTrue(n.contains("org.lwjgl.lwjgl:lwjgl-platform:2.9.4+legacyfabric.17"));
        // same group:artifact replaces every vanilla version of it (2.9.4-nightly and the osx-only 2.9.2-nightly)
        assertFalse(n.stream().anyMatch(s -> s.contains("nightly")), n.toString());
        assertTrue(n.contains("net.java.jinput:jinput-platform:2.0.5"));
        assertEquals("fabric-loader-0.20.1-1.8.9", m.get("id").getAsString());
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", m.get("mainClass").getAsString());
        assertEquals("1.8.9", m.get("jar").getAsString());
        assertEquals(8, m.getAsJsonObject("javaVersion").get("majorVersion").getAsInt());
        assertTrue(m.has("minecraftArguments"));
        assertFalse(m.has("arguments"));
    }

    @Test
    void fabricArgumentsAreAppended() {
        JsonObject vanilla = Fixtures.version("1.21.1");
        JsonObject m = VersionJsons.merge(Fixtures.version(Fixtures.FABRIC_1211), vanilla);
        JsonArray jvm = m.getAsJsonObject("arguments").getAsJsonArray("jvm");
        JsonArray vJvm = vanilla.getAsJsonObject("arguments").getAsJsonArray("jvm");
        assertEquals(vJvm.size() + 1, jvm.size());
        assertEquals(vJvm.get(0), jvm.get(0));
        assertEquals("-DFabricMcEmu= net.minecraft.client.main.Main ", jvm.get(jvm.size() - 1).getAsString());
        assertEquals(vanilla.getAsJsonObject("arguments").getAsJsonArray("game"),
                m.getAsJsonObject("arguments").getAsJsonArray("game"));
        assertEquals(21, m.getAsJsonObject("javaVersion").get("majorVersion").getAsInt());
        assertEquals("1.21.1", m.get("jar").getAsString());
    }

    @Test
    void forgeLegacyKeepsVanillaJarAndOwnArguments() {
        JsonObject m = VersionJsons.merge(Fixtures.version(Fixtures.FORGE_189), Fixtures.version("1.8.9"));
        assertEquals("net.minecraft.launchwrapper.Launch", m.get("mainClass").getAsString());
        assertTrue(m.get("minecraftArguments").getAsString()
                .endsWith("--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker"));
        assertEquals("1.8.9", m.get("jar").getAsString());
        assertEquals(Fixtures.FORGE_189, m.get("id").getAsString());
    }

    @Test
    void javaPerMinecraftVersion() {
        assertEquals(25, VersionJsons.merge(Fixtures.version(Fixtures.FABRIC_2612), Fixtures.version("26.1.2"))
                .getAsJsonObject("javaVersion").get("majorVersion").getAsInt());
        assertEquals(21, VersionJsons.merge(Fixtures.version(Fixtures.NEO_1211), Fixtures.version("1.21.1"))
                .getAsJsonObject("javaVersion").get("majorVersion").getAsInt());
    }
}
