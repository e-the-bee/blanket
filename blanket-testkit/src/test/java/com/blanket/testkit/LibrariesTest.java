// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibrariesTest {
    private static final Path LIB = Path.of("libs");

    @TempDir
    Path tmp;

    private static List<String> urls(Libraries.Plan p) {
        return p.downloads().stream().map(Fetcher.Download::url).toList();
    }

    private static List<String> cp(Libraries.Plan p) {
        return p.classpath().stream().map(x -> LIB.relativize(x).toString().replace('\\', '/')).toList();
    }

    @Test
    void legacyFabricNativesAreMavenStyle() {
        JsonObject m = VersionJsons.merge(Fixtures.version(Fixtures.LF_189), Fixtures.version("1.8.9"));
        Libraries.Plan p = Libraries.plan(m, "linux", LIB);
        assertTrue(urls(p).contains("https://maven.legacyfabric.net/org/lwjgl/lwjgl/lwjgl-platform/"
                + "2.9.4+legacyfabric.17/lwjgl-platform-2.9.4+legacyfabric.17-natives-linux.jar"), urls(p).toString());
        assertTrue(p.nativeJars().contains(LIB.resolve("org/lwjgl/lwjgl/lwjgl-platform/2.9.4+legacyfabric.17/"
                + "lwjgl-platform-2.9.4+legacyfabric.17-natives-linux.jar")));
        // a natives-only entry is not on the classpath; LWJGL itself is, from the profile's Maven repository
        assertFalse(cp(p).stream().anyMatch(s -> s.contains("lwjgl-platform")), cp(p).toString());
        assertTrue(cp(p).contains("org/lwjgl/lwjgl/lwjgl/2.9.4+legacyfabric.17/lwjgl-2.9.4+legacyfabric.17.jar"));
        assertTrue(urls(p).contains("https://maven.legacyfabric.net/org/lwjgl/lwjgl/lwjgl/2.9.4+legacyfabric.17/"
                + "lwjgl-2.9.4+legacyfabric.17.jar"));
        // vanilla's jinput natives come from the version JSON's classifiers
        assertTrue(p.nativeJars().stream().anyMatch(x -> x.toString().replace('\\', '/')
                .endsWith("jinput-platform-2.0.5-natives-linux.jar")));
        // twitch natives exist only for osx/windows
        assertFalse(urls(p).stream().anyMatch(s -> s.contains("twitch")));
    }

    @Test
    void vanillaLegacyNativesPerOs() {
        JsonObject v = Fixtures.version("1.8.9");
        Libraries.Plan linux = Libraries.plan(v, "linux", LIB);
        assertTrue(cp(linux).contains("org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar"));
        // the osx-only 2.9.2 build is skipped on linux
        assertFalse(cp(linux).stream().anyMatch(s -> s.contains("2.9.2-nightly")));
        assertTrue(linux.nativeJars().stream().anyMatch(x -> x.toString().replace('\\', '/')
                .endsWith("lwjgl-platform-2.9.4-nightly-20150209-natives-linux.jar")));
        // windows: ${arch} becomes 64
        Libraries.Plan win = Libraries.plan(v, "windows", LIB);
        assertTrue(win.nativeJars().stream().anyMatch(x -> x.toString().replace('\\', '/')
                .endsWith("twitch-external-platform-4.5-natives-windows-64.jar")), win.nativeJars().toString());
    }

    @Test
    void modernNativesAreRuleSelectedJars() {
        Libraries.Plan p = Libraries.plan(Fixtures.version("1.21.1"), "linux", LIB);
        assertTrue(cp(p).contains("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar"), cp(p).toString());
        assertTrue(cp(p).contains("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar"));
        assertFalse(cp(p).stream().anyMatch(s -> s.contains("natives-windows") || s.contains("natives-macos")));
        assertTrue(p.nativeJars().isEmpty());
    }

    @Test
    void forgeLegacyLibraries() {
        JsonObject m = VersionJsons.merge(Fixtures.version(Fixtures.FORGE_189), Fixtures.version("1.8.9"));
        Libraries.Plan p = Libraries.plan(m, "linux", LIB);
        // "clientreq": false is server-only
        assertFalse(cp(p).stream().anyMatch(s -> s.startsWith("jline/")));
        assertTrue(urls(p).contains("https://maven.minecraftforge.net/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/"
                + "forge-1.8.9-11.15.1.2318-1.8.9.jar"));
        // no url: the launcher's library repository
        assertTrue(urls(p).contains("https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/"
                + "launchwrapper-1.12.jar"));
        assertEquals("net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9.jar",
                cp(p).get(0));
    }

    @Test
    void downloadsAreUniqueAndClasspathKeepsOrder() {
        JsonObject m = VersionJsons.merge(Fixtures.version(Fixtures.FABRIC_1211), Fixtures.version("1.21.1"));
        Libraries.Plan p = Libraries.plan(m, "linux", LIB);
        assertEquals(p.downloads().size(), p.downloads().stream().map(Fetcher.Download::path).distinct().count());
        assertEquals(p.classpath().size(), p.classpath().stream().distinct().count());
        assertEquals("org/ow2/asm/asm/9.10.1/asm-9.10.1.jar", cp(p).get(0));
    }

    @Test
    void extractSkipsMetaInf() throws Exception {
        Path jar = tmp.resolve("n.jar");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "x");
        entries.put("liblwjgl64.so", "so");
        entries.put("sub/", "");
        Fixtures.zip(jar, entries);
        Path out = tmp.resolve("natives");
        Files.createDirectories(out);
        Libraries.extract(jar, out);
        assertEquals("so", Files.readString(out.resolve("liblwjgl64.so")));
        assertFalse(Files.exists(out.resolve("META-INF")));
    }
}
