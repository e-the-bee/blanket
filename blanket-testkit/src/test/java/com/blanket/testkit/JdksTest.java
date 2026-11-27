// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdksTest {
    @TempDir
    Path tmp;

    @Test
    void linuxFolderNamesFromAdoptium() throws Exception {
        Path root = tmp.resolve("opt-blanket-jdk");
        // folder names as the Adoptium tarballs unpack (linux-setup.sh) and as Corretto installs on Windows
        Fixtures.fakeJdk(root.resolve("jdk8u472-b08"));
        Fixtures.fakeJdk(root.resolve("jdk-17.0.17+10"));
        Fixtures.fakeJdk(root.resolve("jdk-21.0.9+10"));
        Fixtures.fakeJdk(root.resolve("jdk-25.0.1+8"));
        Jdks j = new Jdks(Map.of(), List.of(root), Platform.of("linux"));
        assertEquals(root.resolve("jdk8u472-b08/bin/java"), j.javaFor(8));
        assertEquals(root.resolve("jdk-17.0.17+10/bin/java"), j.javaFor(17));
        assertEquals(root.resolve("jdk-21.0.9+10/bin/java"), j.javaFor(21));
        assertEquals(root.resolve("jdk-25.0.1+8/bin/java"), j.javaFor(25));
        // jdk-21 is not JDK 2, jdk-25 is not JDK 2 either
        HarnessException e = assertThrows(HarnessException.class, () -> j.javaFor(2));
        assertTrue(e.getMessage().contains("BLANKET_JDK2"), e.getMessage());
    }

    @Test
    void windowsFolderNamesFromCorretto() throws Exception {
        Path root = tmp.resolve("corretto");
        Fixtures.fakeJdk(root.resolve("jdk1.8.0_504"));
        Fixtures.fakeJdk(root.resolve("jdk17.0.20_10"));
        Fixtures.fakeJdk(root.resolve("jdk21.0.12_9"));
        Jdks j = new Jdks(Map.of(), List.of(tmp.resolve("missing"), root), Platform.of("windows"));
        assertEquals(root.resolve("jdk1.8.0_504/bin/java.exe"), j.javaFor(8));
        assertEquals(root.resolve("jdk17.0.20_10/bin/java.exe"), j.javaFor(17));
        assertEquals(root.resolve("jdk21.0.12_9/bin/java.exe"), j.javaFor(21));
    }

    @Test
    void newestMatchingFolderWins() throws Exception {
        Path root = tmp.resolve("r");
        Fixtures.fakeJdk(root.resolve("jdk-21.0.5+11"));
        Fixtures.fakeJdk(root.resolve("jdk-21.0.9+10"));
        assertEquals(root.resolve("jdk-21.0.9+10/bin/java"),
                new Jdks(Map.of(), List.of(root), Platform.of("linux")).javaFor(21));
    }

    @Test
    void folderWithoutJavaIsSkipped() throws Exception {
        Path root = tmp.resolve("r");
        java.nio.file.Files.createDirectories(root.resolve("jdk-21.0.9+10"));
        Fixtures.fakeJdk(root.resolve("jdk-21.0.5+11"));
        assertEquals(root.resolve("jdk-21.0.5+11/bin/java"),
                new Jdks(Map.of(), List.of(root), Platform.of("linux")).javaFor(21));
    }

    @Test
    void environmentOverrides() throws Exception {
        Path root = tmp.resolve("r");
        Fixtures.fakeJdk(root.resolve("jdk-21.0.9+10"));
        Path custom = Fixtures.fakeJdk(tmp.resolve("custom21"));
        Jdks j = new Jdks(Map.of("BLANKET_JDK21", custom.toString()), List.of(root), Platform.of("linux"));
        assertEquals(custom.resolve("bin/java"), j.javaFor(21));
    }

    @Test
    void environmentWithoutJavaFails() {
        Jdks j = new Jdks(Map.of("BLANKET_JDK8", tmp.resolve("nothing").toString()), List.of(), Platform.of("linux"));
        HarnessException e = assertThrows(HarnessException.class, () -> j.javaFor(8));
        assertTrue(e.getMessage().startsWith("BLANKET_JDK8="), e.getMessage());
    }

    @Test
    void defaultRoots() {
        assertEquals(List.of(Path.of("/opt/blanket-jdk")), Jdks.defaultRoots(Platform.of("linux")));
        assertEquals(2, Jdks.defaultRoots(Platform.of("windows")).size());
    }
}
