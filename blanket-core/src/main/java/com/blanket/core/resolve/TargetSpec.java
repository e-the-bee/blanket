// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.resolve;

/**
 * One supported target: a loader, a Minecraft release, the era's runtime namespace and emitter, and the Java level of
 * the release (targets.json, binding-format.md section 3). {@code mcpVersion} names the MCPConfig archive for SRG eras.
 */
public record TargetSpec(String loader, String mc, String runtime, String emitter, int java, String mcpVersion) {

    public String id() {
        return loader + ":" + mc;
    }

    /** Whether this target's intermediary is Fabric's (1.14 to 1.21.11). */
    public boolean hasFabricIntermediary() {
        return isBetween(mc, "1.14", "1.21.11");
    }

    /** Whether this target's intermediary is Legacy Fabric's (1.3 to 1.13.2). */
    public boolean hasLegacyIntermediary() {
        return isBetween(mc, "1.3", "1.13.2");
    }

    /** Whether this release is unobfuscated (26.1 and later): official names are the Mojang names. */
    public boolean isOfficial() {
        return com.blanket.core.bindings.Version.parse(mc).compareTo(com.blanket.core.bindings.Version.parse("26.1")) >= 0;
    }

    static boolean isBetween(String mc, String low, String high) {
        var v = com.blanket.core.bindings.Version.parse(mc);
        return v.compareTo(com.blanket.core.bindings.Version.parse(low)) >= 0 && v.compareTo(com.blanket.core.bindings.Version.parse(high)) <= 0;
    }
}
