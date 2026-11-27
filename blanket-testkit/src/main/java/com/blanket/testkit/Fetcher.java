// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Downloads into the cache; a file that exists is never fetched again. */
public final class Fetcher {
    /** A file to fetch. */
    public record Download(String url, Path path) {
    }

    private static final String OLD_FORGE_MAVEN = "http://files.minecraftforge.net/maven/";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    /** Where a library missing from Forge's Maven is found instead (run.py's fallback for Forge 1.8.9's Scala jars). */
    private static final List<String> FORGE_FALLBACKS = List.of("https://repo1.maven.org/maven2/",
            "https://libraries.minecraft.net/");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();
    private final PrintStream log;

    public Fetcher(PrintStream log) {
        this.log = log;
    }

    /** Fetches {@code url} to {@code path} unless the file exists (written as {@code .part}, then moved). */
    public Path fetch(String url, Path path) throws IOException {
        if (Files.exists(path)) {
            return path;
        }
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(rewrite(url))).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "blanket-testkit").build();
        try {
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) {
                    throw new IOException("HTTP " + r.statusCode() + " for " + url);
                }
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + url, e);
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        return path;
    }

    /** The bytes at {@code url} (not cached). */
    public String text(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "blanket-testkit").build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IOException("HTTP " + r.statusCode() + " for " + url);
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + url, e);
        }
    }

    static String rewrite(String url) {
        return url.replace(OLD_FORGE_MAVEN, FORGE_MAVEN);
    }

    /** The mirrors tried after a failed Forge Maven URL, in order (empty for other URLs). */
    static List<String> fallbacks(String url) {
        if (!url.contains("maven.minecraftforge.net") && !url.contains("files.minecraftforge.net")) {
            return List.of();
        }
        String rel = url.contains("/maven/") ? url.substring(url.lastIndexOf("/maven/") + "/maven/".length())
                : url.substring(url.lastIndexOf("maven.minecraftforge.net/") + "maven.minecraftforge.net/".length());
        List<String> out = new ArrayList<>();
        for (String base : FORGE_FALLBACKS) {
            out.add(base + rel);
        }
        return out;
    }

    /** A library download: on failure the Forge fallbacks are tried; a final failure is logged, not thrown. */
    void library(Download d) {
        try {
            fetch(d.url(), d.path());
            return;
        } catch (IOException e) {
            for (String alt : fallbacks(d.url())) {
                try {
                    fetch(alt, d.path());
                    return;
                } catch (IOException ignored) {
                    // next mirror
                }
            }
            log.println("  library failed: " + d.url() + " " + e.getMessage());
        }
    }

    /** Fetches every download with {@code threads} parallel connections, failures logged as in {@link #library}. */
    public void all(List<Download> downloads, int threads) {
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Download d : downloads) {
                fs.add(ex.submit(() -> library(d)));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.println("  download failed: " + e);
                }
            }
        } finally {
            ex.shutdownNow();
        }
    }
}
