// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Provisioning from a filled cache, offline: no file here needs a download. */
class InstanceTest {
    @TempDir
    Path tmp;
    private Layout layout;
    private Instance instance;
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        layout = new Layout(tmp.resolve("home"));
        PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
        Path jdk8 = Fixtures.fakeJdk(tmp.resolve("jdk8"));
        Jdks jdks = new Jdks(Map.of("BLANKET_JDK8", jdk8.toString()), List.of(), Platform.of("linux"));
        instance = new Instance(layout, new Fetcher(log), jdks, Platform.of("linux"), log);
        Files.createDirectories(layout.cache());
        Files.writeString(layout.cache().resolve("1.8.9.json"), Fixtures.text("versions/1.8.9.json"));
        Files.writeString(layout.cache().resolve("client-1.8.9.jar"), "client");
    }

    @Test
    void fabricProfileFromCache() throws Exception {
        Path profile = layout.cache().resolve("profiles").resolve("legacy-fabric-1.8.9.json");
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, Fixtures.text("versions/" + Fixtures.LF_189 + ".json"));
        String vid = instance.install(Target.parse("legacy-fabric:1.8.9"));
        assertEquals(Fixtures.LF_189, vid);
        assertTrue(Files.exists(layout.versionJson(vid)));
        // the vanilla JSON and client jar are copied from the cache into versions/1.8.9/
        assertTrue(Files.exists(layout.versionJson("1.8.9")));
        assertEquals("client", Files.readString(layout.versionJar("1.8.9")));
        JsonObject m = instance.merged(vid);
        assertEquals("1.8.9", m.get("jar").getAsString());
    }

    @Test
    void missingVersionJsonNamesBothPlaces() {
        HarnessException e = assertThrows(HarnessException.class, () -> instance.versionJson("nope"));
        assertTrue(e.getMessage().contains("nope.json"), e.getMessage());
    }

    @Test
    void legacyForgeInstaller() throws Exception {
        Path installer = tmp.resolve("forge-installer.jar");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("install_profile.json", Fixtures.text("legacy-install_profile.json"));
        entries.put("forge-1.8.9-11.15.1.2318-1.8.9-universal.jar", "universal");
        Fixtures.zip(installer, entries);
        String vid = Instance.installLegacy(installer, layout);
        assertEquals(Fixtures.FORGE_189, vid);
        assertEquals("net.minecraft.launchwrapper.Launch",
                Instance.readJson(layout.versionJson(vid)).get("mainClass").getAsString());
        assertEquals("universal", Files.readString(layout.libraries().resolve(
                "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9.jar")));
    }

    @Test
    void modernInstallerVersionId() throws Exception {
        Path installer = tmp.resolve("neo-installer.jar");
        Fixtures.zip(installer, Map.of("version.json", Fixtures.text("versions/" + Fixtures.NEO_1211 + ".json")));
        assertEquals(Fixtures.NEO_1211, Instance.modernVersionId(installer));
    }

    /**
     * Every library, native jar and asset already on disk: prepare downloads nothing, extracts the natives, and puts
     * the client jar last on the classpath at versions/&lt;launched id&gt;/&lt;launched id&gt;.jar (R-EXP-07).
     */
    @Test
    void prepareFromFilledCache() throws Exception {
        Fixtures.installVersions(layout, Fixtures.LF_189, "1.8.9");
        Files.writeString(layout.versionJar("1.8.9"), "client");
        JsonObject merged = instance.merged(Fixtures.LF_189);
        Libraries.Plan plan = Libraries.plan(merged, "linux", layout.libraries());
        for (Fetcher.Download d : plan.downloads()) {
            if (plan.nativeJars().contains(d.path())) {
                Fixtures.zip(d.path(), Map.of("lib" + d.path().getFileName() + ".so", "so", "META-INF/x", "x"));
            } else {
                Files.createDirectories(d.path().getParent());
                Files.writeString(d.path(), "jar");
            }
        }
        Path index = layout.assets().resolve("indexes").resolve("1.8.json");
        Files.createDirectories(index.getParent());
        Files.writeString(index, Fixtures.text("assets-1.8.json"));
        for (Fetcher.Download d : Assets.objects(Fixtures.json("assets-1.8.json"), layout.assets())) {
            Files.createDirectories(d.path().getParent());
            Files.writeString(d.path(), "png");
        }

        Instance.Prepared p = instance.prepare(Fixtures.LF_189);
        Path client = layout.versionJar(Fixtures.LF_189);
        assertEquals(client, p.classpath().get(p.classpath().size() - 1));
        assertEquals("client", Files.readString(client));
        assertEquals(plan.classpath().size() + 1, p.classpath().size());
        assertEquals("1.8", p.assetIndex());
        assertEquals(tmp.resolve("jdk8").resolve("bin").resolve("java"), p.java());
        try (var s = Files.list(layout.natives(Fixtures.LF_189))) {
            List<String> names = s.map(x -> x.getFileName().toString()).sorted().toList();
            assertTrue(names.contains("liblwjgl-platform-2.9.4+legacyfabric.17-natives-linux.jar.so"), names.toString());
            assertTrue(!names.contains("META-INF"), names.toString());
        }
        assertEquals("", logBytes.toString(StandardCharsets.UTF_8));
    }
}
