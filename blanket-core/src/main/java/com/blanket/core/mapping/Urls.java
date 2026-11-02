// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.mapping;

/**
 * Where the public artifacts Blanket reads live (ported from M0's tools/run.py, M1 T5). Only public downloads;
 * every artifact is checked against a published SHA-1 (Mojang JSON, Maven `.sha1` sidecars).
 */
public final class Urls {
    private Urls() {}

    public static final String MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** Fabric intermediary v2 jar; contains {@code mappings/mappings.tiny}. */
    public static String fabricIntermediary(String mc) {
        return "https://maven.fabricmc.net/net/fabricmc/intermediary/" + mc + "/intermediary-" + mc + "-v2.jar";
    }

    /** Legacy Fabric intermediary v2 jar (1.3 to 1.13.2). */
    public static String legacyIntermediary(String mc) {
        return "https://maven.legacyfabric.net/net/legacyfabric/intermediary/" + mc + "/intermediary-" + mc + "-v2.jar";
    }

    /** MCPConfig archive; {@code config/joined.tsrg} is the obf to SRG table (D-033). */
    public static String mcpConfig(String mc, String mcpVersion) {
        return "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/" + mcpVersion + "/mcp_config-" + mcpVersion + ".zip";
    }

    public static String forgeUniversal(String artifactVersion) {
        return "https://maven.minecraftforge.net/net/minecraftforge/forge/" + artifactVersion + "/forge-" + artifactVersion + "-universal.jar";
    }

    public static String neoForgeUniversal(String version) {
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + version + "/neoforge-" + version + "-universal.jar";
    }
}
