// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.mapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blanket.core.fetch.ArtifactFetcher;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The .sha1 sidecar flow and the version manifest cache, against a local HTTP server. */
class MappingDownloadsTest {
    private static final byte[] JAR = "fixture jar bytes\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path cache;

    private HttpServer server;
    private String base;
    private volatile String sidecar;
    private volatile String manifestBody;
    private final AtomicInteger manifestHits = new AtomicInteger();
    private final AtomicInteger jarHits = new AtomicInteger();
    private final java.util.Map<String, byte[]> versionJsons = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        serve("/lib.jar", () -> {
            jarHits.incrementAndGet();
            return JAR;
        });
        serve("/lib.jar.sha1", () -> sidecar.getBytes(StandardCharsets.UTF_8));
        server.createContext("/v/", exchange -> {
            byte[] b = versionJsons.get(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(b == null ? 404 : 200, b == null ? -1 : b.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (b != null) {
                    out.write(b);
                }
            }
        });
        serve("/manifest.json", () -> {
            manifestHits.incrementAndGet();
            return manifestBody.getBytes(StandardCharsets.UTF_8);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(String path, java.util.function.Supplier<byte[]> body) {
        server.createContext(path, exchange -> {
            byte[] b = body.get();
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(b);
            }
        });
    }

    private static String sha1(byte[] data) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-1").digest(data)) {
            b.append(String.format("%02x", x & 0xff));
        }
        return b.toString();
    }

    @Test
    void sidecarSha1VerifiesTheArtifact() throws Exception {
        sidecar = sha1(JAR) + "\n";
        MappingDownloads d = new MappingDownloads(new ArtifactFetcher(cache, false));
        Path p = d.fetchMavenChecked(base + "/lib.jar");
        assertArrayEquals(JAR, Files.readAllBytes(p));
        assertTrue(p.endsWith(sha1(JAR)), p.toString());
    }

    @Test
    void sidecarWithTabSeparatedFileNameIsSplit() throws Exception {
        sidecar = sha1(JAR) + "\tlib.jar\n";
        MappingDownloads d = new MappingDownloads(new ArtifactFetcher(cache, false));
        assertArrayEquals(JAR, Files.readAllBytes(d.fetchMavenChecked(base + "/lib.jar")));
    }

    @Test
    void sidecarForADifferentFileFailsTheChecksum() throws Exception {
        sidecar = sha1("other".getBytes(StandardCharsets.UTF_8)) + "\n";
        MappingDownloads d = new MappingDownloads(new ArtifactFetcher(cache, false));
        assertThrows(RuntimeException.class, () -> d.fetchMavenChecked(base + "/lib.jar"));
    }

    private String manifestWith(String... ids) throws Exception {
        StringBuilder b = new StringBuilder("{\"versions\":[");
        for (int i = 0; i < ids.length; i++) {
            String info = "{\"javaVersion\":{\"majorVersion\":" + (8 + i) + "}}";
            byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
            versionJsons.put("/v/" + ids[i] + ".json", infoBytes);
            b.append(i > 0 ? "," : "").append("{\"id\":\"").append(ids[i]).append("\",\"sha1\":\"")
                    .append(sha1(infoBytes)).append("\",\"url\":\"").append(base).append("/v/").append(ids[i])
                    .append(".json\"}");
        }
        return b.append("]}").toString();
    }

    @Test
    void manifestIsRefetchedOnceWhenAVersionIsMissing() throws Exception {
        manifestBody = manifestWith("1.0");
        URI manifest = URI.create(base + "/manifest.json");
        MappingDownloads d = new MappingDownloads(new ArtifactFetcher(cache, false), manifest);
        assertEquals(8, d.javaVersion("1.0"));
        assertEquals(1, manifestHits.get());

        manifestBody = manifestWith("1.0", "2.0");
        assertEquals(9, d.javaVersion("2.0"));
        assertEquals(2, manifestHits.get());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> d.javaVersion("3.0"));
        assertTrue(e.getMessage().contains("3.0"), e.getMessage());
        assertEquals(3, manifestHits.get());
    }

    @Test
    void staleCachedManifestIsRefetchedByANewRun() throws Exception {
        manifestBody = manifestWith("1.0");
        URI manifest = URI.create(base + "/manifest.json");
        new MappingDownloads(new ArtifactFetcher(cache, false), manifest).javaVersion("1.0");

        manifestBody = manifestWith("1.0", "2.0");
        MappingDownloads second = new MappingDownloads(new ArtifactFetcher(cache, false), manifest);
        assertEquals(9, second.javaVersion("2.0"));
        assertEquals(2, manifestHits.get());
    }

    @Test
    void offlineDoesNotRefetchTheManifest() throws Exception {
        manifestBody = manifestWith("1.0");
        URI manifest = URI.create(base + "/manifest.json");
        new MappingDownloads(new ArtifactFetcher(cache, false), manifest).javaVersion("1.0");

        MappingDownloads offline = new MappingDownloads(new ArtifactFetcher(cache, true), manifest);
        assertThrows(IllegalArgumentException.class, () -> offline.javaVersion("2.0"));
        assertEquals(1, manifestHits.get());
    }
}
