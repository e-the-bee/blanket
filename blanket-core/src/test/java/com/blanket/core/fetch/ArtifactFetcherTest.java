// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.fetch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Downloads, checksums and the offline cache, against a local HTTP server. */
class ArtifactFetcherTest {
    private static final byte[] BODY = "blanket fixture artifact\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path cache;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile byte[] served = BODY;
    private URI uri;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/artifact.jar", exchange -> {
            hits.incrementAndGet();
            byte[] b = served;
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(b);
            }
        });
        server.createContext("/missing.jar", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/artifact.jar");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String hex(String algorithm, byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance(algorithm).digest(data);
        StringBuilder b = new StringBuilder();
        for (byte x : d) {
            b.append(String.format("%02x", x & 0xff));
        }
        return b.toString();
    }

    @Test
    void downloadsAndVerifiesSha1() throws Exception {
        ArtifactFetcher f = new ArtifactFetcher(cache, false);
        Path p = f.fetch(uri, hex("SHA-1", BODY));
        assertArrayEquals(BODY, Files.readAllBytes(p));
        assertEquals(1, hits.get());
    }

    @Test
    void downloadsAndVerifiesSha256() throws Exception {
        ArtifactFetcher f = new ArtifactFetcher(cache, false);
        Path p = f.fetch(uri, hex("SHA-256", BODY));
        assertArrayEquals(BODY, Files.readAllBytes(p));
    }

    @Test
    void checksumMismatchFailsAndCachesNothing() throws Exception {
        ArtifactFetcher f = new ArtifactFetcher(cache, false);
        String wrong = hex("SHA-1", "something else".getBytes(StandardCharsets.UTF_8));
        FetchException e = assertThrows(FetchException.class, () -> f.fetch(uri, wrong));
        assertTrue(e.getMessage().contains("checksum mismatch"), e.getMessage());
        assertTrue(e.getMessage().contains(wrong), e.getMessage());
        assertTrue(!Files.exists(cache.resolve("downloads").resolve("sha1").resolve(wrong)));
    }

    @Test
    void cachedFileIsUsedWithoutAnotherRequest() throws Exception {
        ArtifactFetcher f = new ArtifactFetcher(cache, false);
        String sum = hex("SHA-1", BODY);
        Path first = f.fetch(uri, sum);
        Path second = f.fetch(uri, sum);
        assertEquals(first, second);
        assertEquals(1, hits.get());
    }

    @Test
    void corruptCacheEntryIsReplaced() throws Exception {
        ArtifactFetcher f = new ArtifactFetcher(cache, false);
        String sum = hex("SHA-1", BODY);
        Path p = f.fetch(uri, sum);
        Files.write(p, "tampered".getBytes(StandardCharsets.UTF_8));
        Path again = f.fetch(uri, sum);
        assertArrayEquals(BODY, Files.readAllBytes(again));
        assertEquals(2, hits.get());
    }

    @Test
    void offlineHitReturnsTheCachedFile() throws Exception {
        String sum = hex("SHA-1", BODY);
        new ArtifactFetcher(cache, false).fetch(uri, sum);
        ArtifactFetcher offline = new ArtifactFetcher(cache, true);
        Path p = offline.fetch(uri, sum);
        assertArrayEquals(BODY, Files.readAllBytes(p));
        assertEquals(1, hits.get());
    }

    @Test
    void offlineMissFailsClearlyAndDoesNotRequest() throws Exception {
        ArtifactFetcher offline = new ArtifactFetcher(cache, true);
        FetchException e = assertThrows(FetchException.class, () -> offline.fetch(uri, hex("SHA-1", BODY)));
        assertTrue(e.getMessage().startsWith("offline mode: '" + uri + "' is not in the cache"), e.getMessage());
        assertEquals(0, hits.get());
    }

    @Test
    void httpErrorFails() {
        ArtifactFetcher f = new ArtifactFetcher(cache, false);
        URI missing = URI.create(uri.toString().replace("artifact.jar", "missing.jar"));
        FetchException e = assertThrows(FetchException.class, () -> f.fetch(missing, null));
        assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
    }

    @Test
    void uncheckedDownloadIsCachedByUrl() throws Exception {
        ArtifactFetcher f = new ArtifactFetcher(cache, false);
        Path p = f.fetch(uri, null);
        assertArrayEquals(BODY, Files.readAllBytes(p));
        assertTrue(p.startsWith(cache.resolve("downloads").resolve("url")));
    }

    @Test
    void digestIsInferredFromLength() {
        assertEquals(ArtifactFetcher.Digest.SHA1, ArtifactFetcher.Digest.of("a".repeat(40)));
        assertEquals(ArtifactFetcher.Digest.SHA256, ArtifactFetcher.Digest.of("a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> ArtifactFetcher.Digest.of("abc"));
    }
}
