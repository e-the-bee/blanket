// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import java.nio.file.Path;

/**
 * Where the harness keeps its files, the M0 spike's layout under one home folder (so {@code --home
 * blanket-emit/m0-spike} reuses the spike's downloads):
 * <pre>
 * &lt;home&gt;/.cache/&lt;mc&gt;.json, client-&lt;mc&gt;.jar, server-&lt;mc&gt;.jar   vanilla version JSON and jars
 * &lt;home&gt;/.cache/installers/                              loader installers
 * &lt;home&gt;/.cache/profiles/&lt;loader&gt;-&lt;mc&gt;.json              Fabric / Legacy Fabric meta profiles
 * &lt;home&gt;/run/.mc/                                       shared libraries, versions, assets, natives
 * &lt;home&gt;/run/.worlds/&lt;mc&gt;/                               the local server per Minecraft version
 * &lt;home&gt;/run/&lt;loader&gt;-&lt;mc&gt;/                              the game folder per target (mods/, options.txt)
 * </pre>
 */
public record Layout(Path home) {

    public Path cache() {
        return home.resolve(".cache");
    }

    public Path run() {
        return home.resolve("run");
    }

    public Path mc() {
        return run().resolve(".mc");
    }

    public Path versions() {
        return mc().resolve("versions");
    }

    public Path versionJson(String id) {
        return versions().resolve(id).resolve(id + ".json");
    }

    /** {@code versions/<id>/<id>.jar}: the launcher keeps the client jar under the launched version's id (R-EXP-07). */
    public Path versionJar(String id) {
        return versions().resolve(id).resolve(id + ".jar");
    }

    public Path libraries() {
        return mc().resolve("libraries");
    }

    public Path assets() {
        return mc().resolve("assets");
    }

    public Path natives(String versionId) {
        return mc().resolve("natives").resolve(versionId);
    }

    public Path serverDir(String mc) {
        return run().resolve(".worlds").resolve(mc);
    }

    public Path gameDir(Target t) {
        return run().resolve(t.dirName());
    }
}
