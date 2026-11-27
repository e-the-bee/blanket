// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Downloads a real instance (vanilla JSON from the launcher manifest, client jar, Legacy Fabric profile, libraries,
 * natives, assets without sounds; about 100 MB). Excluded from {@code build}; run with {@code :blanket-testkit:onlineTest}.
 */
@Tag("online")
class OnlineProvisionTest {
    @TempDir
    Path tmp;

    @Test
    void legacyFabricFromNothing() throws Exception {
        Layout layout = new Layout(tmp);
        Path jdk = Fixtures.fakeJdk(tmp.resolve("jdk8"));
        Instance instance = new Instance(layout, new Fetcher(System.out),
                new Jdks(Map.of("BLANKET_JDK8", jdk.toString()), List.of(), Platform.of("linux")),
                Platform.of("linux"), new PrintStream(System.out));
        String vid = instance.install(Target.parse("legacy-fabric:1.8.9"));
        assertEquals("fabric-loader-0.20.1-1.8.9", vid);
        Instance.Prepared p = instance.prepare(vid);
        for (Path jar : p.classpath()) {
            assertTrue(Files.size(jar) > 0, jar.toString());
        }
        assertTrue(p.classpath().stream().anyMatch(x -> x.getFileName().toString().startsWith("fabric-loader-0.20.1")));
        try (var s = Files.list(layout.natives(vid))) {
            assertTrue(s.anyMatch(x -> x.getFileName().toString().endsWith(".so")));
        }
    }
}
