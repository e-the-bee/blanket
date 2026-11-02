// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.mapping;

import com.blanket.core.fetch.ArtifactFetcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves the files a target needs to disk, verified where a digest is published: the Mojang version JSON gives
 * SHA-1 for the client jar and client mappings; Maven artifacts are checked against their {@code .sha1} sidecar.
 * Extracted members (intermediary tiny, joined.tsrg) are written next to the cache and reused.
 */
public final class MappingDownloads {
    private final ArtifactFetcher fetcher;
    private final Path extracted;
    private JsonObject manifest;

    public MappingDownloads(ArtifactFetcher fetcher) {
        this.fetcher = fetcher;
        this.extracted = fetcher.cacheRoot().resolve("extracted");
    }

    public ArtifactFetcher fetcher() { return fetcher; }

    private JsonObject versionJson(String mc) {
        if (manifest == null) {
            manifest = JsonParser.parseString(read(fetcher.fetch(URI.create(Urls.MOJANG_MANIFEST), null))).getAsJsonObject();
        }
        for (var v : manifest.getAsJsonArray("versions")) {
            JsonObject o = v.getAsJsonObject();
            if (o.get("id").getAsString().equals(mc)) {
                String sha1 = o.get("sha1").getAsString();
                return JsonParser.parseString(read(fetcher.fetch(URI.create(o.get("url").getAsString()), sha1))).getAsJsonObject();
            }
        }
        throw new IllegalArgumentException("Mojang has no version " + mc);
    }

    /** The client jar of a version, checked against Mojang's SHA-1. */
    public Path clientJar(String mc) {
        return download(versionJson(mc).getAsJsonObject("downloads").getAsJsonObject("client"));
    }

    /** Mojang's ProGuard client mappings (1.14.4 and later). */
    public Path clientMappings(String mc) {
        return download(versionJson(mc).getAsJsonObject("downloads").getAsJsonObject("client_mappings"));
    }

    public int javaVersion(String mc) {
        return versionJson(mc).getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
    }

    /** Fabric or Legacy Fabric intermediary, extracted to {@code mappings/mappings.tiny}. */
    public Path intermediary(String mc, boolean legacy) {
        String url = legacy ? Urls.legacyIntermediary(mc) : Urls.fabricIntermediary(mc);
        Path jar = fetchMavenChecked(url);
        Path out = extracted.resolve((legacy ? "lfint-" : "int-") + mc + ".tiny");
        return extractOnce(jar, "mappings/mappings.tiny", out);
    }

    /** {@code config/joined.tsrg} from the MCPConfig archive named by {@code mcpVersion}. */
    public Path joinedTsrg(String mc, String mcpVersion) {
        Path zip = fetchMavenChecked(Urls.mcpConfig(mc, mcpVersion));
        return extractOnce(zip, "config/joined.tsrg", extracted.resolve("joined-" + mc + ".tsrg"));
    }

    public Path forgeUniversal(String artifactVersion) {
        return fetchMavenChecked(Urls.forgeUniversal(artifactVersion));
    }

    public Path neoForgeUniversal(String version) {
        return fetchMavenChecked(Urls.neoForgeUniversal(version));
    }

    private Path download(JsonObject artifact) {
        try {
            return fetcher.fetch(URI.create(artifact.get("url").getAsString()), artifact.get("sha1").getAsString());
        } catch (RuntimeException e) {
            throw e;
        }
    }

    /** Downloads a Maven file, verified against its {@code .sha1} sidecar. */
    public Path fetchMavenChecked(String url) {
        Path sidecar = fetcher.fetch(URI.create(url + ".sha1"), null);
        String sha1 = read(sidecar).trim().split("\s+")[0];
        return fetcher.fetch(URI.create(url), sha1);
    }

    private static Path extractOnce(Path zip, String member, Path out) {
        try {
            if (Files.isRegularFile(out)) return out;
            Files.createDirectories(out.getParent());
            Path tmp = Files.createTempFile(out.getParent(), "extract-", ".part");
            try (ZipFile z = new ZipFile(zip.toFile())) {
                ZipEntry e = z.getEntry(member);
                if (e == null) throw new IllegalStateException(member + " is missing from " + zip);
                try (InputStream in = z.getInputStream(e); OutputStream o = Files.newOutputStream(tmp)) {
                    in.transferTo(o);
                }
            }
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("cannot extract " + member + " from " + zip, e);
        }
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }
}
