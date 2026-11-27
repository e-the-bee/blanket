// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LaunchCommandTest {
    private static final Path ROOT = Path.of("/h/run/.mc");
    private static final List<String> PROPS = List.of("-Dblanket.testkit.probes=/p/probes.json",
            "-Dblanket.testkit.out=/o/testkit-result.json");

    private static LaunchCommand.Inputs inputs(String vid) {
        return new LaunchCommand.Inputs(Path.of("/jdk/bin/java"), vid, Path.of("/h/run/game"), ROOT.resolve("assets"),
                "17", ROOT.resolve("natives").resolve(vid), ROOT.resolve("libraries"),
                List.of(Path.of("/l/a.jar"), Path.of("/l/b.jar")), Platform.of("linux"));
    }

    @Test
    void modernNeoForge() {
        JsonObject m = VersionJsons.merge(Fixtures.version(Fixtures.NEO_1211), Fixtures.version("1.21.1"));
        List<String> join = LaunchCommand.joinArgs(m, 25591);
        assertEquals(List.of("--quickPlayMultiplayer", "127.0.0.1:25591"), join);
        List<String> cmd = LaunchCommand.build(m, inputs(Fixtures.NEO_1211), PROPS, join);
        assertEquals("/jdk/bin/java", cmd.get(0));
        assertEquals("-Xmx2G", cmd.get(1));
        // NeoForge's ignoreList names the client jar by the launched id (R-EXP-07)
        assertTrue(cmd.contains("-DignoreList=client-extra,neoforge-21.1.265.jar"), cmd.toString());
        int main = cmd.indexOf("cpw.mods.bootstraplauncher.BootstrapLauncher");
        assertEquals(PROPS, cmd.subList(main - 2, main));
        assertTrue(cmd.indexOf("-cp") < main);
        assertEquals("/l/a.jar:/l/b.jar", cmd.get(cmd.indexOf("-cp") + 1));
        assertTrue(cmd.contains("-Djava.library.path=" + ROOT.resolve("natives").resolve(Fixtures.NEO_1211)));
        // windows/osx-only and arch-x86 JVM arguments are left out; no ${...} remains except in unknown names
        assertFalse(cmd.contains("-XstartOnFirstThread"));
        assertFalse(cmd.contains("-Xss1M"));
        assertFalse(cmd.stream().anyMatch(s -> s.contains("${")), cmd.toString());
        assertEquals("msa", cmd.get(cmd.indexOf("--userType") + 1));
        assertEquals("Dev", cmd.get(cmd.indexOf("--username") + 1));
        assertEquals("--launchTarget", cmd.get(cmd.size() - 4));
        assertEquals(join, cmd.subList(cmd.size() - 2, cmd.size()));
        // quick-play feature arguments are not expanded
        assertFalse(cmd.contains("--quickPlayPath"));
        assertFalse(cmd.contains("--demo"));
    }

    @Test
    void legacyFabric() {
        JsonObject m = VersionJsons.merge(Fixtures.version(Fixtures.LF_189), Fixtures.version("1.8.9"));
        List<String> join = LaunchCommand.joinArgs(m, 25589);
        assertEquals(List.of("--server", "127.0.0.1", "--port", "25589"), join);
        List<String> cmd = LaunchCommand.build(m, inputs(Fixtures.LF_189), PROPS, join);
        assertEquals(List.of("/jdk/bin/java", "-Xmx2G",
                "-Djava.library.path=" + ROOT.resolve("natives").resolve(Fixtures.LF_189), "-cp", "/l/a.jar:/l/b.jar",
                PROPS.get(0), PROPS.get(1), "net.fabricmc.loader.impl.launch.knot.KnotClient", "--username", "Dev",
                "--version", Fixtures.LF_189), cmd.subList(0, 12));
        assertEquals("legacy", cmd.get(cmd.indexOf("--userType") + 1));
        assertEquals("17", cmd.get(cmd.indexOf("--assetIndex") + 1));
        assertEquals(join, cmd.subList(cmd.size() - 4, cmd.size()));
    }

    @Test
    void forgeLegacyKeepsTweakClass() {
        JsonObject m = VersionJsons.merge(Fixtures.version(Fixtures.FORGE_189), Fixtures.version("1.8.9"));
        List<String> cmd = LaunchCommand.build(m, inputs(Fixtures.FORGE_189), List.of(), LaunchCommand.joinArgs(m, 1));
        assertEquals("net.minecraftforge.fml.common.launcher.FMLTweaker",
                cmd.get(cmd.indexOf("--tweakClass") + 1));
        assertEquals("net.minecraft.launchwrapper.Launch", cmd.get(5));
    }

    @Test
    void fabric26NeedsNoTestkitProperties() {
        JsonObject m = VersionJsons.merge(Fixtures.version(Fixtures.FABRIC_2612), Fixtures.version("26.1.2"));
        List<String> cmd = LaunchCommand.build(m, inputs(Fixtures.FABRIC_2612), List.of(),
                LaunchCommand.joinArgs(m, 25592));
        assertTrue(cmd.contains("--enable-native-access=ALL-UNNAMED"));
        assertFalse(cmd.stream().anyMatch(s -> s.startsWith("-Dblanket.testkit")));
        assertEquals(List.of("--quickPlayMultiplayer", "127.0.0.1:25592"), cmd.subList(cmd.size() - 2, cmd.size()));
    }

    @Test
    void unknownVariablesStay() {
        assertEquals("a-${nope}-x", LaunchCommand.sub("a-${nope}-${v}", Map.of("v", "x")));
        assertEquals("C:\\$1", LaunchCommand.sub("${v}", Map.of("v", "C:\\$1")));
    }
}
