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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Game data from the Blanket cache, downloaded on first use (build-and-gradle.md section 4): client jars and mapping files
 * through {@link MappingDownloads}. Loader jars are found through the optional file {@code loader-artifacts.json} in
 * the bindings folder, a flat object from target id to Forge or NeoForge artifact version
 * ({@code {"forge:1.20.1": "<version>"}}); a target without an entry has no loader jar and native-event classes of that
 * target are reported as warnings.
 */
public final class DownloadedTargetData implements TargetData {
    /** The MCPConfig license (research-log.md R-MAP-16). */
    static final String MCP_LICENSE_URL = "https://raw.githubusercontent.com/MinecraftForge/MCPConfig/master/LICENSE";

    private final MappingDownloads downloads;
    private final Targets targets;
    private final Map<String, String> loaderArtifacts;
    private final Map<String, Path> clientJars = new HashMap<>();
    private final Map<String, List<Library>> libraries = new HashMap<>();

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
     * The library jars named in the Mojang version json whose package holds one of {@code owners}. The version json and
     * the jars come through the fetcher, so offline mode with an empty cache fails with the fetcher's message.
     */
    @Override
    public List<Path> libraryJars(TargetSpec target, Set<String> owners) throws IOException {
        List<Path> jars = new ArrayList<>();
        try {
            Map<String, Library> found = new LinkedHashMap<>();
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
            }
            for (Library lib : found.values()) jars.add(downloads.fetcher().fetch(URI.create(lib.url()), lib.sha1()));
        } catch (RuntimeException e) {
            throw new IOException("cannot obtain the library jars of " + target.id() + " named in the " + target.mc() + " version json: " + e.getMessage(), e);
        }
        return jars;
    }

    /** One library of a version json: its Maven name, jar, and the package prefixes (internal names) it is taken to provide. */
    private record Library(String name, String url, String sha1, List<String> prefixes) {}

    private List<Library> libraries(String mc) {
        return libraries.computeIfAbsent(mc, this::readLibraries);
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
     * {@code org/joml/}, {@code org.lwjgl:lwjgl-glfw} gives {@code org/lwjgl/glfw/}, {@code com.mojang:brigadier} gives
     * {@code com/mojang/brigadier/}. The longest matching prefix wins, so {@code org/lwjgl/glfw/} beats {@code org/lwjgl/}.
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
