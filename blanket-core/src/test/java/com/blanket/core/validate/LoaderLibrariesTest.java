// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The loader's own libraries ({@link DownloadedTargetData#loaderArtifacts}) and the package lookup over their jars
 * ({@link DownloadedTargetData#packages}). The entries are copied from the real installers
 * ({@code unzip -p neoforge-21.1.265-installer.jar version.json},
 * {@code unzip -p forge-1.8.9-11.15.1.2318-1.8.9-installer.jar install_profile.json}); the SHA-1 values are placeholders,
 * the parser only carries them. The class locations are from {@code unzip -l} on the real jars: NeoForge's
 * {@code net/neoforged/bus/api/Event.class} is in {@code bus-8.0.5.jar}, Forge 1.20.1's
 * {@code net/minecraftforge/api/distmarker/Dist.class} in {@code mergetool-1.1.5-api.jar}, whose Maven name does not
 * tell.
 */
class LoaderLibrariesTest {
    static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path tmp;

    /** NeoForge 21.1.265's version.json, two of its libraries, plus an artifact the installer makes locally (empty URL). */
    static final String NEOFORGE = """
            { "id": "neoforge-21.1.265", "inheritsFrom": "1.21.1", "libraries": [
              { "name": "net.neoforged:bus:8.0.5", "downloads": { "artifact": {
                "url": "https://maven.neoforged.net/releases/net/neoforged/bus/8.0.5/bus-8.0.5.jar", "sha1": "%1$s" } } },
              { "name": "net.neoforged:mergetool:2.0.0:api", "downloads": { "artifact": {
                "url": "https://maven.neoforged.net/releases/net/neoforged/mergetool/2.0.0/mergetool-2.0.0-api.jar", "sha1": "%1$s" } } },
              { "name": "net.neoforged:neoforge:21.1.265:client", "downloads": { "artifact": { "url": "", "sha1": "%1$s" } } } ] }""".formatted(SHA);

    /** Forge 1.8.9's install_profile.json: a library on Forge's maven and one on the launcher's default repository. */
    static final String FORGE_189 = """
            { "install": {}, "versionInfo": { "id": "1.8.9-forge1.8.9-11.15.1.2318-1.8.9", "libraries": [
              { "name": "net.minecraft:launchwrapper:1.12", "serverreq": true },
              { "name": "com.typesafe:config:1.2.1", "url": "https://maven.minecraftforge.net/", "checksums": [ "%s" ] } ] } }""".formatted(SHA);

    @Test
    void versionJsonEntriesKeepUrlAndSha1() {
        assertEquals(List.of(
                new DownloadedTargetData.Artifact("net.neoforged:bus:8.0.5",
                        "https://maven.neoforged.net/releases/net/neoforged/bus/8.0.5/bus-8.0.5.jar", SHA),
                new DownloadedTargetData.Artifact("net.neoforged:mergetool:2.0.0:api",
                        "https://maven.neoforged.net/releases/net/neoforged/mergetool/2.0.0/mergetool-2.0.0-api.jar", SHA)),
                DownloadedTargetData.loaderArtifacts(JsonParser.parseString(NEOFORGE).getAsJsonObject()),
                "the locally made client artifact (empty URL) is left out");
    }

    @Test
    void oldInstallProfileEntriesBecomeMavenUrlsCheckedBySidecar() {
        assertEquals(List.of(
                new DownloadedTargetData.Artifact("net.minecraft:launchwrapper:1.12",
                        "https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar", null),
                new DownloadedTargetData.Artifact("com.typesafe:config:1.2.1",
                        "https://maven.minecraftforge.net/com/typesafe/config/1.2.1/config-1.2.1.jar", null)),
                DownloadedTargetData.loaderArtifacts(JsonParser.parseString(FORGE_189).getAsJsonObject()));
    }

    private Path jar(String name, String... classes) throws IOException {
        Path p = tmp.resolve(name);
        try (OutputStream out = Files.newOutputStream(p); ZipOutputStream z = new ZipOutputStream(out)) {
            for (String c : classes) {
                z.putNextEntry(new ZipEntry(c + ".class"));
                z.closeEntry();
            }
        }
        return p;
    }

    @Test
    void packagesComeFromTheJarsContentFirstJarWins() throws IOException {
        Path bus = jar("bus-8.0.5.jar", "net/neoforged/bus/api/Event", "net/neoforged/bus/api/IEventBus", "META-INF/versions/9/x/Y", "module-info");
        Path merge = jar("mergetool-1.1.5-api.jar", "net/minecraftforge/api/distmarker/Dist", "net/neoforged/bus/api/Event");
        Map<String, Path> packages = DownloadedTargetData.packages(List.of(bus, merge));
        assertEquals(Map.of("net/neoforged/bus/api/", bus, "net/minecraftforge/api/distmarker/", merge), packages);
        assertNull(packages.get("net/neoforged/neoforge/client/event/"), "a package no library holds is found nowhere");
    }
}
