// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A supported target and how its loader is installed. The loader URLs and server ports are the ones the M0 spike's
 * {@code tools/run.py} used on all six targets.
 */
public record Target(String loader, String mc, Kind kind, String loaderUrl) {

    /** How the loader gets into the instance. */
    public enum Kind {
        /** Fabric / Legacy Fabric meta profile JSON: a version JSON that inherits from vanilla. */
        FABRIC_PROFILE,
        /** Forge 1.13+ / NeoForge installer, run headless with {@code --installClient <dir>}. */
        INSTALLER,
        /** Forge 1.8.9 installer: {@code install_profile.json} holds the version JSON and the universal jar's path. */
        LEGACY_INSTALLER
    }

    private static final Map<String, Target> ALL = new LinkedHashMap<>();
    private static final Map<String, Integer> PORTS = Map.of("1.8.9", 25589, "1.20.1", 25590, "1.21.1", 25591,
            "26.1.2", 25592);

    static {
        add("legacy-fabric", "1.8.9", Kind.FABRIC_PROFILE,
                "https://meta.legacyfabric.net/v2/versions/loader/1.8.9/0.20.1/profile/json");
        add("forge", "1.8.9", Kind.LEGACY_INSTALLER,
                "https://maven.minecraftforge.net/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/"
                        + "forge-1.8.9-11.15.1.2318-1.8.9-installer.jar");
        add("fabric", "1.21.1", Kind.FABRIC_PROFILE,
                "https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.20.1/profile/json");
        add("neoforge", "1.21.1", Kind.INSTALLER,
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.265/neoforge-21.1.265-installer.jar");
        add("forge", "1.20.1", Kind.INSTALLER,
                "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-installer.jar");
        add("fabric", "26.1.2", Kind.FABRIC_PROFILE,
                "https://meta.fabricmc.net/v2/versions/loader/26.1.2/0.20.1/profile/json");
    }

    private static void add(String loader, String mc, Kind kind, String url) {
        ALL.put(loader + ":" + mc, new Target(loader, mc, kind, url));
    }

    /** Parses {@code <loader>:<mc>}; only the six targets above are known. */
    public static Target parse(String id) throws HarnessException {
        Target t = ALL.get(id);
        if (t == null) {
            throw new HarnessException("unknown target " + id + "; known: " + String.join(", ", ALL.keySet()));
        }
        return t;
    }

    public String id() {
        return loader + ":" + mc;
    }

    /** The target's game folder name under the run folder, as in run.py ({@code fabric-1.21.1}). */
    public String dirName() {
        return loader + "-" + mc;
    }

    /** The local server's port; one per Minecraft version so servers of different versions never collide. */
    public int port() {
        return PORTS.get(mc);
    }
}
