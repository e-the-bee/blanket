// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The libraries of a merged version JSON for one OS: classpath jars, downloads and native jars to extract.
 * <ul>
 * <li>skipped: entries whose {@code rules} disallow the OS, and {@code "clientreq": false} (Forge 1.8.9);</li>
 * <li>{@code downloads.artifact} with a URL: that path and URL;</li>
 * <li>no artifact and no {@code natives}: the Maven path of {@code name} under {@code url} (default the launcher's
 * library repository);</li>
 * <li>{@code natives.<os>} (with {@code ${arch}} = 64): the classifier from {@code downloads.classifiers}, or Maven
 * style ({@code name:classifier} under {@code url}) as Legacy Fabric's LWJGL 2 has it. Native jars are extracted into
 * the natives folder, without {@code META-INF}. LWJGL 3 natives are ordinary rule-selected classpath jars that LWJGL
 * extracts itself.</li>
 * </ul>
 */
public final class Libraries {
    static final String MOJANG_LIBRARIES = "https://libraries.minecraft.net/";

    /** What to fetch and put on the classpath, in version-JSON order. */
    public record Plan(List<Path> classpath, List<Fetcher.Download> downloads, List<Path> nativeJars) {
    }

    private Libraries() {
    }

    public static Plan plan(JsonObject merged, String os, Path libDir) {
        List<Path> cp = new ArrayList<>();
        Map<Path, Fetcher.Download> downloads = new LinkedHashMap<>();
        List<Path> natives = new ArrayList<>();
        for (JsonElement e : merged.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            if (!VersionJsons.rulesAllow(lib.has("rules") ? lib.getAsJsonArray("rules") : null, os)) {
                continue;
            }
            if (lib.has("clientreq") && !lib.get("clientreq").getAsBoolean()) {
                continue;
            }
            String name = lib.get("name").getAsString();
            JsonObject d = lib.has("downloads") ? lib.getAsJsonObject("downloads") : new JsonObject();
            String url = VersionJsons.str(lib, "url");
            String base = url == null || url.isEmpty() ? MOJANG_LIBRARIES : url;
            JsonObject art = d.has("artifact") ? d.getAsJsonObject("artifact") : null;
            String artUrl = art == null ? null : VersionJsons.str(art, "url");
            if (art != null && artUrl != null && !artUrl.isEmpty()) {
                Path p = libDir.resolve(art.get("path").getAsString());
                downloads.put(p, new Fetcher.Download(artUrl, p));
                cp.add(p);
            } else if (art == null && !lib.has("natives")) {
                String path = VersionJsons.mavenPath(name);
                Path p = libDir.resolve(path);
                downloads.put(p, new Fetcher.Download(join(base, path), p));
                cp.add(p);
            }
            JsonObject nat = lib.has("natives") ? lib.getAsJsonObject("natives") : null;
            if (nat != null && nat.has(os)) {
                String classifier = nat.get(os).getAsString().replace("${arch}", "64");
                JsonObject cls = d.has("classifiers") ? d.getAsJsonObject("classifiers") : new JsonObject();
                Path p;
                if (cls.has(classifier)) {
                    JsonObject c = cls.getAsJsonObject(classifier);
                    p = libDir.resolve(c.get("path").getAsString());
                    downloads.put(p, new Fetcher.Download(c.get("url").getAsString(), p));
                } else {
                    String path = VersionJsons.mavenPath(name + ":" + classifier);
                    p = libDir.resolve(path);
                    downloads.put(p, new Fetcher.Download(join(base, path), p));
                }
                natives.add(p);
            }
        }
        return new Plan(List.copyOf(new LinkedHashSet<>(cp)), List.copyOf(downloads.values()), natives);
    }

    static String join(String base, String path) {
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/" + path;
    }

    /** Extracts a native jar's files (not {@code META-INF}, not folders) into {@code dir}, replacing older copies. */
    public static void extract(Path jar, Path dir) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                if (e.getName().startsWith("META-INF") || e.isDirectory()) {
                    continue;
                }
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("native jar entry outside the folder: " + e.getName() + " in " + jar);
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = z.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
