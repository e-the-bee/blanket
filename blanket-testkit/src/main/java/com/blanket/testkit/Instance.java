// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Provisions the shared game files of a target (version JSONs, client jar, loader, libraries, natives, assets)
 * under {@link Layout}, as the M0 spike's run.py did.
 */
public final class Instance {
    static final String MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    /** Written next to a modern installer's version JSON once the installer succeeded; later runs skip the installer. */
    static final String INSTALLED_MARKER = ".blanket-installed";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Everything the launch command needs. */
    public record Prepared(String versionId, JsonObject merged, Path java, List<Path> classpath, Path natives,
            String assetIndex) {
    }

    private final Layout layout;
    private final Fetcher fetcher;
    private final Jdks jdks;
    private final Platform platform;
    private final PrintStream log;

    public Instance(Layout layout, Fetcher fetcher, Jdks jdks, Platform platform, PrintStream log) {
        this.layout = layout;
        this.fetcher = fetcher;
        this.jdks = jdks;
        this.platform = platform;
        this.log = log;
    }

    /** The vanilla version JSON of {@code mc}: {@code .cache/<mc>.json}, fetched through the launcher manifest when absent. */
    public JsonObject vanilla(String mc) throws IOException, HarnessException {
        Path cached = layout.cache().resolve(mc + ".json");
        if (!Files.exists(cached)) {
            JsonObject manifest = JsonParser.parseString(fetcher.text(MOJANG_MANIFEST)).getAsJsonObject();
            String url = null;
            for (JsonElement e : manifest.getAsJsonArray("versions")) {
                if (e.getAsJsonObject().get("id").getAsString().equals(mc)) {
                    url = e.getAsJsonObject().get("url").getAsString();
                }
            }
            if (url == null) {
                throw new HarnessException("version " + mc + " is not in the launcher manifest");
            }
            fetcher.fetch(url, cached);
        }
        return versionJson(mc);
    }

    /** {@code versions/<id>/<id>.json}, copied from {@code .cache/<id>.json} when only the cache has it. */
    public JsonObject versionJson(String id) throws IOException, HarnessException {
        Path p = layout.versionJson(id);
        Path cached = layout.cache().resolve(id + ".json");
        if (!Files.exists(p) && Files.exists(cached)) {
            Files.createDirectories(p.getParent());
            Files.copy(cached, p);
        }
        if (!Files.exists(p)) {
            throw new HarnessException("no version JSON for " + id + " (looked for " + p + " and " + cached + ")");
        }
        return readJson(p);
    }

    /** The version JSON of {@code id} with its {@code inheritsFrom} chain merged in. */
    public JsonObject merged(String id) throws IOException, HarnessException {
        JsonObject v = versionJson(id);
        if (!v.has("inheritsFrom")) {
            return v;
        }
        return VersionJsons.merge(v, merged(v.get("inheritsFrom").getAsString()));
    }

    /** Installs the vanilla client and the target's loader; returns the version id to launch. */
    public String install(Target t) throws IOException, HarnessException {
        JsonObject vanilla = vanilla(t.mc());
        Path cachedClient = layout.cache().resolve("client-" + t.mc() + ".jar");
        if (!Files.exists(cachedClient)) {
            fetcher.fetch(vanilla.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString(),
                    cachedClient);
        }
        Path client = layout.versionJar(t.mc());
        if (!Files.exists(client)) {
            Files.copy(cachedClient, client);
        }
        switch (t.kind()) {
            case FABRIC_PROFILE -> {
                Path cached = layout.cache().resolve("profiles").resolve(t.dirName() + ".json");
                if (!Files.exists(cached)) {
                    Files.createDirectories(cached.getParent());
                    Files.writeString(cached, fetcher.text(t.loaderUrl()));
                }
                JsonObject profile = readJson(cached);
                String vid = profile.get("id").getAsString();
                writeJson(layout.versionJson(vid), profile);
                return vid;
            }
            case LEGACY_INSTALLER -> {
                return installLegacy(installer(t), layout);
            }
            case INSTALLER -> {
                return installModern(installer(t), vanilla);
            }
            default -> throw new IllegalStateException(t.kind().toString());
        }
    }

    private Path installer(Target t) throws IOException {
        String url = t.loaderUrl();
        return fetcher.fetch(url, layout.cache().resolve("installers").resolve(url.substring(url.lastIndexOf('/') + 1)));
    }

    /**
     * Forge 1.8.9's installer: {@code install_profile.json} holds the version JSON ({@code versionInfo}) and names
     * the universal jar ({@code install.filePath}) and its Maven name ({@code install.path}).
     */
    static String installLegacy(Path installer, Layout layout) throws IOException {
        try (ZipFile z = new ZipFile(installer.toFile())) {
            JsonObject profile = JsonParser.parseString(entry(z, "install_profile.json")).getAsJsonObject();
            JsonObject info = profile.getAsJsonObject("versionInfo");
            String vid = info.get("id").getAsString();
            writeJson(layout.versionJson(vid), info);
            JsonObject install = profile.getAsJsonObject("install");
            Path dest = layout.libraries().resolve(VersionJsons.mavenPath(install.get("path").getAsString()));
            Files.createDirectories(dest.getParent());
            ZipEntry jar = z.getEntry(install.get("filePath").getAsString());
            if (jar == null) {
                throw new IOException(installer + " has no " + install.get("filePath").getAsString());
            }
            try (var in = z.getInputStream(jar)) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return vid;
        }
    }

    /** The version id a modern Forge/NeoForge installer installs ({@code version.json} in the installer). */
    static String modernVersionId(Path installer) throws IOException {
        try (ZipFile z = new ZipFile(installer.toFile())) {
            return JsonParser.parseString(entry(z, "version.json")).getAsJsonObject().get("id").getAsString();
        }
    }

    /** Runs a modern installer headless into {@code .mc} (it needs a {@code launcher_profiles.json}). */
    private String installModern(Path installer, JsonObject vanilla) throws IOException, HarnessException {
        String vid = modernVersionId(installer);
        Path marker = layout.versions().resolve(vid).resolve(INSTALLED_MARKER);
        if (Files.exists(marker) && Files.exists(layout.versionJson(vid))) {
            return vid;
        }
        Path profiles = layout.mc().resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) {
            Files.createDirectories(profiles.getParent());
            Files.writeString(profiles, "{\"profiles\": {}}");
        }
        Path java = jdks.javaFor(majorVersion(vanilla));
        log.println("running installer " + installer.getFileName() + " (Java " + majorVersion(vanilla) + ")");
        String output = "";
        for (String flag : new String[] {"--installClient", "--install-client"}) {
            Process p = new ProcessBuilder(java.toString(), "-jar", installer.toAbsolutePath().toString(), flag,
                    layout.mc().toAbsolutePath().toString()).directory(installer.toAbsolutePath().getParent().toFile())
                    .redirectErrorStream(true).start();
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (!p.waitFor(30, TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HarnessException("interrupted while installing " + installer.getFileName());
            }
            if (p.exitValue() == 0) {
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, flag + "\n");
                return vid;
            }
        }
        throw new HarnessException("installer failed: " + installer.getFileName() + "\n"
                + output.substring(Math.max(0, output.length() - 3000)));
    }

    /** Fetches libraries, natives and assets of {@code vid}; returns what the launch command needs. */
    public Prepared prepare(String vid) throws IOException, HarnessException {
        JsonObject merged = merged(vid);
        Path natives = layout.natives(vid);
        Files.createDirectories(natives);
        Libraries.Plan plan = Libraries.plan(merged, platform.os(), layout.libraries());
        fetcher.all(plan.downloads(), 8);
        for (Path jar : plan.nativeJars()) {
            if (Files.exists(jar)) {
                Libraries.extract(jar, natives);
            } else {
                log.println("  native jar missing: " + jar);
            }
        }
        List<Path> cp = new ArrayList<>();
        for (Path p : plan.classpath()) {
            if (Files.exists(p)) {
                cp.add(p);
            }
        }
        Path client = layout.versionJar(vid);
        if (!Files.exists(client)) {
            String jar = merged.get("jar") != null ? merged.get("jar").getAsString() : merged.get("id").getAsString();
            Files.createDirectories(client.getParent());
            Files.copy(layout.versionJar(jar), client);
        }
        cp.add(client);
        JsonObject idx = merged.getAsJsonObject("assetIndex");
        String indexId = idx.get("id").getAsString();
        Path indexFile = fetcher.fetch(idx.get("url").getAsString(),
                layout.assets().resolve("indexes").resolve(indexId + ".json"));
        fetcher.all(Assets.objects(readJson(indexFile), layout.assets()), 16);
        return new Prepared(vid, merged, jdks.javaFor(majorVersion(merged)), cp, natives, indexId);
    }

    /** The Java major version a version JSON asks for ({@code javaVersion.majorVersion}). */
    static int majorVersion(JsonObject v) throws HarnessException {
        if (!v.has("javaVersion")) {
            throw new HarnessException("version JSON " + v.get("id") + " has no javaVersion");
        }
        return v.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
    }

    private static String entry(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) {
            throw new IOException(z.getName() + " has no " + name);
        }
        try (var in = z.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static JsonObject readJson(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static void writeJson(Path p, JsonObject o) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, GSON.toJson(o), StandardCharsets.UTF_8);
    }
}
