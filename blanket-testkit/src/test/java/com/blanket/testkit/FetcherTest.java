// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FetcherTest {
    @TempDir
    Path tmp;

    @Test
    void oldForgeMavenIsRewritten() {
        assertEquals("https://maven.minecraftforge.net/a/b.jar",
                Fetcher.rewrite("http://files.minecraftforge.net/maven/a/b.jar"));
    }

    @Test
    void forgeFallbacks() {
        String rel = "org/scala-lang/scala-library/2.11.1/scala-library-2.11.1.jar";
        assertEquals(List.of("https://repo1.maven.org/maven2/" + rel, "https://libraries.minecraft.net/" + rel),
                Fetcher.fallbacks("https://maven.minecraftforge.net/" + rel));
        assertEquals(List.of("https://repo1.maven.org/maven2/" + rel, "https://libraries.minecraft.net/" + rel),
                Fetcher.fallbacks("http://files.minecraftforge.net/maven/" + rel));
        assertEquals(List.of(), Fetcher.fallbacks("https://maven.fabricmc.net/" + rel));
    }

    @Test
    void existingFileIsNotFetched() throws Exception {
        Path f = Files.writeString(tmp.resolve("x.jar"), "here");
        // an unreachable URL proves no request is made
        assertEquals(f, new Fetcher(new PrintStream(PrintStream.nullOutputStream())).fetch("http://127.0.0.1:9/x", f));
        assertEquals("here", Files.readString(f));
    }
}
