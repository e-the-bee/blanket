// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @TempDir
    Path tmp;

    @Test
    void fullCommandLine() throws Exception {
        Path jar = Files.writeString(tmp.resolve("hudbox.jar"), "j");
        Path probes = Files.writeString(tmp.resolve("testkit-probes.json"), "{}");
        Harness.Options o = Main.parse(new String[] {"run", "forge:1.20.1", "--jar", jar.toString(), "--probes",
                probes.toString(), "--out", "o", "--timeout", "200", "--home", "h"}, Map.of());
        assertEquals("forge:1.20.1", o.target().id());
        assertEquals(jar, o.jar());
        assertEquals(probes, o.probes());
        assertEquals(Path.of("o"), o.out());
        assertEquals(200, o.timeoutSeconds());
        assertEquals(Path.of("h"), o.home());
    }

    @Test
    void jarAndProbesAreOptional() throws Exception {
        Harness.Options o = Main.parse(new String[] {"run", "legacy-fabric:1.8.9", "--out", "o"},
                Map.of("BLANKET_TESTKIT_HOME", "/srv/tk"));
        assertNull(o.jar());
        assertNull(o.probes());
        assertEquals(360, o.timeoutSeconds());
        assertEquals(Path.of("/srv/tk"), o.home());
    }

    @Test
    void usageErrors() {
        assertThrows(HarnessException.class, () -> Main.parse(new String[] {"run", "fabric:1.21.1"}, Map.of()));
        assertThrows(HarnessException.class, () -> Main.parse(new String[] {"run", "fabric:9.9", "--out", "o"}, Map.of()));
        assertThrows(HarnessException.class, () -> Main.parse(
                new String[] {"run", "fabric:1.21.1", "--out", "o", "--jar", tmp.resolve("none.jar").toString()}, Map.of()));
        assertThrows(HarnessException.class, () -> Main.parse(
                new String[] {"run", "fabric:1.21.1", "--out", "o", "--timeout", "0"}, Map.of()));
        assertThrows(HarnessException.class, () -> Main.parse(new String[] {"run", "fabric:1.21.1", "--out"}, Map.of()));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[] {"go"}, new PrintStream(b, true, StandardCharsets.UTF_8), Map.of()));
        assertTrue(b.toString(StandardCharsets.UTF_8).contains("usage: blanket-testkit run"));
    }

    @Test
    void targets() throws Exception {
        assertEquals(25589, Target.parse("forge:1.8.9").port());
        assertEquals(Target.Kind.LEGACY_INSTALLER, Target.parse("forge:1.8.9").kind());
        assertEquals(Target.Kind.INSTALLER, Target.parse("neoforge:1.21.1").kind());
        assertEquals("fabric-26.1.2", Target.parse("fabric:26.1.2").dirName());
    }
}
