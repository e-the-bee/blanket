// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;

/**
 * Downloads public game and loader artifacts into the Blanket cache ({@code ~/.blanket/cache/downloads}), verified
 * against a SHA-1 or SHA-256 checksum where one is given (build-and-gradle.md section 4).
 *
 * <p>A cached file whose checksum matches is used without a request. In offline mode a missing or mismatched file is
 * an error that names the URL and the cache path.
 */
public final class ArtifactFetcher {

    /** The digest a download is checked against. */
    public enum Digest {
        SHA1("SHA-1", 40), SHA256("SHA-256", 64);

        private final String javaName;
        private final int hexLength;

        Digest(String javaName, int hexLength) {
            this.javaName = javaName;
            this.hexLength = hexLength;
        }

        /** Infers the digest from the length of a hex string. */
        public static Digest of(String hex) {
            for (Digest d : values()) {
                if (d.hexLength == hex.length()) {
                    return d;
                }
            }
            throw new IllegalArgumentException("not a SHA-1 or SHA-256 hex digest: '" + hex + "'");
        }
    }

    private final Path cacheRoot;
    private final boolean offline;
    private final HttpClient client;

    public ArtifactFetcher(Path cacheRoot, boolean offline) {
        this.cacheRoot = cacheRoot;
        this.offline = offline;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** The default cache: {@code ~/.blanket/cache}. */
    public static Path defaultCacheRoot() {
        return Paths.get(System.getProperty("user.home"), ".blanket", "cache");
    }

    public Path cacheRoot() {
        return cacheRoot;
    }

    public boolean offline() {
        return offline;
    }

    /**
     * Returns the local file for {@code uri}, downloading it if needed. {@code expectedHex} may be null, in which case
     * the file is cached by URL and not verified.
     */
    public Path fetch(URI uri, String expectedHex) {
        Digest digest = expectedHex == null ? null : Digest.of(expectedHex.toLowerCase(Locale.ROOT));
        String expected = expectedHex == null ? null : expectedHex.toLowerCase(Locale.ROOT);
        Path target = cachePath(uri, digest, expected);

        if (Files.isRegularFile(target)) {
            if (expected == null || expected.equals(hex(target, digest))) {
                return target;
            }
            deleteQuietly(target); // corrupt cache entry: fall through and treat as missing
        }
        if (offline) {
            throw new FetchException("offline mode: '" + uri + "' is not in the cache at " + target
                    + ". Run once without offline mode to download it.");
        }
        download(uri, digest, expected, target);
        return target;
    }

    private Path cachePath(URI uri, Digest digest, String expected) {
        Path dir = cacheRoot.resolve("downloads");
        if (digest != null) {
            return dir.resolve(digest.name().toLowerCase(Locale.ROOT)).resolve(expected);
        }
        return dir.resolve("url").resolve(sha256Hex(uri.toString()));
    }

    private void download(URI uri, Digest digest, String expected, Path target) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).GET().build();
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), "download-", ".part");
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new FetchException("download of '" + uri + "' failed: HTTP " + response.statusCode());
            }
            String actual;
            try (InputStream in = response.body()) {
                MessageDigest md = newDigest(digest);
                try (OutputStream out = Files.newOutputStream(tmp); InputStream din = new DigestInputStream(in, md)) {
                    din.transferTo(out);
                }
                actual = toHex(md.digest());
            }
            if (expected != null && !expected.equals(actual)) {
                throw new FetchException("checksum mismatch for '" + uri + "': expected " + expected + ", got " + actual
                        + ". The file was not cached.");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FetchException e) {
            deleteQuietly(tmp);
            throw e;
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new FetchException("download of '" + uri + "' failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(tmp);
            throw new FetchException("download of '" + uri + "' was interrupted", e);
        }
    }

    private static MessageDigest newDigest(Digest digest) {
        try {
            return MessageDigest.getInstance(digest == null ? "SHA-256" : digest.javaName);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(Path file, Digest digest) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = newDigest(digest);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (IOException e) {
            throw new FetchException("cannot read cached file " + file + ": " + e.getMessage(), e);
        }
    }

    static String sha256Hex(String s) {
        return toHex(newDigest(null).digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) {
            b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return b.toString();
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort: a leftover .part file is harmless and is replaced next time
        }
    }
}
