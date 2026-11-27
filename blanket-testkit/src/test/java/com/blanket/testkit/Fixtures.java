// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test resources under {@code com/blanket/testkit/}. The version JSONs are the real launcher, Fabric meta and
 * installer JSONs of the six targets (from the M0 spike's instances), trimmed to a few libraries.
 */
final class Fixtures {
    static final String LF_189 = "fabric-loader-0.20.1-1.8.9";
    static final String FABRIC_1211 = "fabric-loader-0.20.1-1.21.1";
    static final String FABRIC_2612 = "fabric-loader-0.20.1-26.1.2";
    static final String NEO_1211 = "neoforge-21.1.265";
    static final String FORGE_189 = "1.8.9-forge1.8.9-11.15.1.2318-1.8.9";

    private Fixtures() {
    }

    static String text(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("no test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonObject json(String name) {
        return JsonParser.parseString(text(name)).getAsJsonObject();
    }

    static JsonObject version(String id) {
        return json("versions/" + id + ".json");
    }

    /** Copies the fixture version JSONs into {@code layout}'s versions folder. */
    static void installVersions(Layout layout, String... ids) throws IOException {
        for (String id : ids) {
            Path p = layout.versionJson(id);
            Files.createDirectories(p.getParent());
            Files.writeString(p, text("versions/" + id + ".json"));
        }
    }

    static void zip(Path file, Map<String, String> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    /** A fake JDK home with {@code bin/java} (and {@code bin/java.exe}). */
    static Path fakeJdk(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("bin").resolve("java"), "");
        Files.writeString(dir.resolve("bin").resolve("java.exe"), "");
        return dir;
    }
}
