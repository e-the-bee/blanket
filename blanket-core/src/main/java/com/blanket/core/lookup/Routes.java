// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.TargetSpec;

/**
 * Which namespaces can reach which targets (binding-format.md section 2.4). The resolver streams mapping files and fails
 * with an unreadable file when a route does not exist; this check says why in words before it is asked.
 */
public final class Routes {
    /** The step name used for a route that cannot exist. */
    public static final String STEP = "route";

    private Routes() {}

    /** A sentence naming the missing route of {@code ref} on {@code target}, or null when a route exists. */
    public static String problem(TargetSpec target, Ref ref) {
        String library = ref.owner().startsWith("net/minecraft/") || ref.owner().startsWith("com/mojang/") ? ""
                : " (a library or JDK class such as " + ref.owner() + " is written with a `loader:` reference)";
        switch (ref.ns()) {
            case "mojang":
            case "intermediary":
                if (target.hasFabricIntermediary()) return null;
                return ref.ns() + ": names reach only targets with a Fabric intermediary (1.14 to 1.21.11); " + target.id() + " has none, "
                        + (target.hasLegacyIntermediary() ? "write this rule with an `lf:` reference" : target.isOfficial()
                        ? "write this rule with an `official:` reference" : "no reference namespace reaches it") + library;
            case "lf":
                if (target.hasLegacyIntermediary()) return null;
                return "lf: names exist only for 1.3 to 1.13.2; " + target.id() + " is outside that range";
            case "official":
                if (target.isOfficial()) return null;
                return "official: names exist only on 26.x targets; " + target.id() + " is obfuscated";
            default:
                return null;
        }
    }
}
