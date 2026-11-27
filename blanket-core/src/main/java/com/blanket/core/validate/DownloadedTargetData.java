// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.bindings.Targets;
import com.blanket.core.fetch.FetchException;
import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.lookup.MappingSources;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.mapping.Urls;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Game data from the Blanket cache, downloaded on first use (build-and-gradle.md section 4): client jars and mapping files
 * through {@link MappingDownloads}. Loader jars are found through the optional file {@code loader-artifacts.json} in
 * the bindings folder, a flat object from target id to Forge or NeoForge artifact version
 * ({@code {"forge:1.20.1": "<version>"}}); a target without an entry has no loader jar, and a loader reference on that
 * target is an UNRESOLVED error at step {@code loader-jar}.
 */
public final class DownloadedTargetData implements TargetData {
    /** The MCPConfig license (research-log.md R-MAP-16). */
    static final String MCP_LICENSE_URL = "https://raw.githubusercontent.com/MinecraftForge/MCPConfig/master/LICENSE";

    private final MappingDownloads downloads;
    private final Targets targets;
    private final Map<String, String> loaderArtifacts;
    private final Map<String, Path> clientJars = new HashMap<>();
    private final Map<String, List<Library>> libraries = new HashMap<>();
    private final Map<String, Map<String, Path>> loaderPackages = new HashMap<>();

    public DownloadedTargetData(MappingDownloads downloads, Targets targets, Path bindingsDir) throws IOException {
        this.downloads = downloads;
        this.targets = targets;
        this.loaderArtifacts = readLoaderArtifacts(bindingsDir.resolve("loader-artifacts.json"));
    }

    private static Map<String, String> readLoaderArtifacts(Path file) {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        JsonElement root = BindingFiles.readJson(file);
        if (!root.isJsonObject()) throw new IllegalArgumentException(file + ": expected an object from target id to artifact version");
        JsonObject o = root.getAsJsonObject();
        for (String id : o.keySet()) out.put(id, o.get(id).getAsString());
        return out;
    }

    @Override
    public Resolver.MappingFiles mappingFiles() {
        return MappingSources.files(downloads, targets);
    }

    @Override
    public List<Path> classpath(TargetSpec target) throws IOException {
        List<Path> jars = new ArrayList<>();
        try {
            jars.add(clientJars.computeIfAbsent(target.mc(), downloads::clientJar));
            if (hasLoaderJar(target)) {
                String version = loaderArtifacts.get(target.id());
                jars.add(target.loader().equals("neoforge") ? downloads.neoForgeUniversal(version) : downloads.forgeUniversal(version));
            }
        } catch (RuntimeException e) {
            throw new IOException("cannot obtain the jars of " + target.id() + ": " + e.getMessage(), e);
        }
        return jars;
    }

    @Override
    public boolean hasLoaderJar(TargetSpec target) {
        return (target.loader().equals("forge") || target.loader().equals("neoforge")) && loaderArtifacts.containsKey(target.id());
    }

    @Override
    public boolean coversLibraries(TargetSpec target) {
        return true;
    }

    /**
     * The library jars that provide {@code owners}. A Mojang library of the version json is taken by its package prefix
     * ({@link #prefixes}). On a target with a loader jar, an owner no Mojang library claims is looked up in the loader's
     * own libraries ({@link #loaderArtifacts}) by the packages their jars really hold: their Maven names do not tell
     * (Forge 1.20.1's {@code net/minecraftforge/api/distmarker/Dist} is in {@code mergetool-1.1.5-api.jar}). The lists
     * and the jars come through the fetcher, so offline mode with an empty cache fails with the fetcher's message.
     */
    @Override
    public List<Path> libraryJars(TargetSpec target, Set<String> owners) throws IOException {
        List<Path> jars = new ArrayList<>();
        try {
            Map<String, Library> found = new LinkedHashMap<>();
            List<String> unclaimed = new ArrayList<>();
            for (String owner : owners) {
                Library best = null;
                int bestLength = -1;
                for (Library lib : libraries(target.mc())) {
                    for (String prefix : lib.prefixes()) {
                        if (owner.startsWith(prefix) && prefix.length() > bestLength) {
                            best = lib;
                            bestLength = prefix.length();
                        }
                    }
                }
                if (best != null) found.putIfAbsent(best.name(), best);
                else unclaimed.add(owner);
            }
            for (Library lib : found.values()) jars.add(downloads.fetcher().fetch(URI.create(lib.url()), lib.sha1()));
            if (!unclaimed.isEmpty() && hasLoaderJar(target)) {
                Map<String, Path> packages = loaderPackages.computeIfAbsent(target.id(), id -> packages(fetchAll(readLoaderArtifacts(target))));
                for (String owner : unclaimed) {
                    Path jar = packages.get(owner.substring(0, owner.lastIndexOf('/') + 1));
                    if (jar != null && !jars.contains(jar)) jars.add(jar);
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("cannot obtain the library jars of " + target.id() + " named in the " + target.mc()
                    + " version json or the loader installer: " + e.getMessage(), e);
        }
        return jars;
    }

    /** One library of a version json: its Maven name, jar, and the package prefixes (internal names) it is taken to provide. */
    private record Library(String name, String url, String sha1, List<String> prefixes) {}

    /** One library of a loader installer: its Maven name, jar URL and the SHA-1 its list gives (null: the Maven {@code .sha1} sidecar checks it). */
    record Artifact(String name, String url, String sha1) {}

    private List<Library> libraries(String mc) {
        return libraries.computeIfAbsent(mc, this::readLibraries);
    }

    private List<Path> fetchAll(List<Artifact> artifacts) {
        List<Path> out = new ArrayList<>();
        for (Artifact a : artifacts) out.add(a.sha1() != null ? downloads.fetcher().fetch(URI.create(a.url()), a.sha1()) : downloads.fetchMavenChecked(a.url()));
        return out;
    }

    /**
     * Each package (internal name ending with {@code /}) that a jar holds classes of, to the first of {@code jars} that
     * does. {@code META-INF/} (multi-release copies) and {@code module-info} are not packages of the jar's API.
     */
    static Map<String, Path> packages(List<Path> jars) {
        Map<String, Path> out = new HashMap<>();
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String n = entries.nextElement().getName();
                    int slash = n.lastIndexOf('/');
                    if (!n.endsWith(".class") || slash < 0 || n.startsWith("META-INF/") || n.endsWith("module-info.class")) continue;
                    out.putIfAbsent(n.substring(0, slash + 1), jar);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read library jar " + jar + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    /**
     * The loader's libraries, from the installer of the loader version loader-artifacts.json names for the target: the
     * launcher profile {@code version.json} first, then the {@code libraries} of {@code install_profile.json} (Forge 1.20.1
     * keeps {@code fmlcore} and the language providers there); an installer without {@code version.json} (Forge 1.8.9)
     * has its profile under {@code install_profile.json}'s {@code versionInfo}.
     */
    private List<Artifact> readLoaderArtifacts(TargetSpec target) {
        String version = loaderArtifacts.get(target.id());
        Path installer = downloads.fetchMavenChecked(target.loader().equals("neoforge") ? Urls.neoForgeInstaller(version) : Urls.forgeInstaller(version));
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            List<Artifact> out = new ArrayList<>();
            for (String name : List.of("version.json", "install_profile.json")) {
                ZipEntry e = zip.getEntry(name);
                if (e == null) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    out.addAll(loaderArtifacts(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject()));
                }
            }
            if (out.isEmpty()) throw new IllegalStateException(installer + " lists no libraries in version.json or install_profile.json");
            return out;
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read the loader installer " + installer + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * The libraries of one installer profile: entries with {@code downloads.artifact} (URL and SHA-1; Forge 1.13 and later,
     * NeoForge), or, in an old {@code install_profile.json}, {@code versionInfo.libraries} with a Maven name and a
     * repository {@code url} ({@link Urls#MOJANG_LIBRARIES} when absent), checked against the Maven {@code .sha1} sidecar.
     * An artifact with an empty URL is made by the installer on the player's machine and is left out.
     */
    static List<Artifact> loaderArtifacts(JsonObject profile) {
        JsonObject version = profile.has("versionInfo") ? profile.getAsJsonObject("versionInfo") : profile;
        List<Artifact> out = new ArrayList<>();
        if (!version.has("libraries")) return out;
        for (JsonElement el : version.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            String name = lib.get("name").getAsString();
            String[] gav = name.split(":");
            if (gav.length < 3) continue;
            JsonObject downloadsOf = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;
            if (downloadsOf != null) {
                if (!downloadsOf.has("artifact")) continue;
                JsonObject artifact = downloadsOf.getAsJsonObject("artifact");
                String url = artifact.get("url").getAsString();
                if (!url.isEmpty()) out.add(new Artifact(name, url, artifact.get("sha1").getAsString()));
            } else {
                String repo = lib.has("url") ? lib.get("url").getAsString() : Urls.MOJANG_LIBRARIES;
                if (!repo.endsWith("/")) repo += "/";
                String file = gav[1] + "-" + gav[2] + (gav.length > 3 ? "-" + gav[3] : "") + ".jar";
                out.add(new Artifact(name, repo + gav[0].replace('.', '/') + "/" + gav[1] + "/" + gav[2] + "/" + file, null));
            }
        }
        return out;
    }

    private List<Library> readLibraries(String mc) {
        ArtifactFetcher fetcher = downloads.fetcher();
        URI manifestUri = URI.create(Urls.MOJANG_MANIFEST);
        JsonObject entry = versionEntry(readJson(fetcher.fetch(manifestUri, null)), mc);
        if (entry == null && !fetcher.offline()) entry = versionEntry(readJson(fetcher.refetch(manifestUri)), mc);
        if (entry == null) throw new IllegalArgumentException("Mojang has no version " + mc);
        JsonObject version = readJson(fetcher.fetch(URI.create(entry.get("url").getAsString()), entry.get("sha1").getAsString()));
        List<Library> out = new ArrayList<>();
        for (JsonElement e : version.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            String[] gav = lib.get("name").getAsString().split(":");
            if (gav.length != 3) continue;   // a classifier is a natives jar: no classes to read
            JsonObject downloadsOf = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;
            if (downloadsOf == null || !downloadsOf.has("artifact")) continue;
            JsonObject artifact = downloadsOf.getAsJsonObject("artifact");
            out.add(new Library(lib.get("name").getAsString(), artifact.get("url").getAsString(), artifact.get("sha1").getAsString(), prefixes(gav[0], gav[1])));
        }
        return out;
    }

    /**
     * The package a library is taken to provide, from its Maven coordinates: {@code org.joml:joml} gives
     * {@code org/joml/}, {@code org.lwjgl:lwjgl-glfw} gives {@code org/lwjgl/glfw/}. The longest matching prefix wins,
     * so {@code org/lwjgl/glfw/} beats {@code org/lwjgl/}.
     */
    static List<String> prefixes(String group, String artifact) {
        String base = group.replace('.', '/') + "/";
        String last = group.substring(group.lastIndexOf('.') + 1);
        if (artifact.equals(last)) return List.of(base);
        if (artifact.startsWith(last + "-")) return List.of(base + artifact.substring(last.length() + 1).replace("-", "") + "/");
        return List.of(base + artifact.replace("-", "") + "/");
    }

    private static JsonObject versionEntry(JsonObject manifest, String mc) {
        for (JsonElement v : manifest.getAsJsonArray("versions")) {
            if (v.getAsJsonObject().get("id").getAsString().equals(mc)) return v.getAsJsonObject();
        }
        return null;
    }

    private static JsonObject readJson(Path p) {
        try {
            return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }

    @Override
    public Optional<Path> mojangMappings(String mc) throws IOException {
        Targets.VersionRow row = targets.versions().get(mc);
        if (row == null || !Boolean.TRUE.equals(row.mapping("mojang"))) return Optional.empty();
        try {
            return Optional.of(downloads.clientMappings(mc));
        } catch (RuntimeException e) {
            throw new IOException("cannot obtain the Mojang mappings of " + mc + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> mcpLicense() throws IOException {
        try {
            URI uri = URI.create(MCP_LICENSE_URL);
            // The file is cached by URL forever; a license change has to be seen, so fetch it again when online.
            Path p = downloads.fetcher().offline() ? downloads.fetcher().fetch(uri, null) : downloads.fetcher().refetch(uri);
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (FetchException e) {
            throw new IOException("cannot obtain the MCPConfig license: " + e.getMessage(), e);
        }
    }
}
