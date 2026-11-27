// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import org.objectweb.asm.commons.Remapper;

import java.util.Map;
import java.util.Objects;

/**
 * The type map of pass 5 (lowering-and-adapters.md section 4.5). Only class references move; string constants are
 * never touched ({@link Remapper#mapValue} rewrites types, handles and dynamic constants, not strings).
 */
public final class TypeMap extends Remapper {
    private static final String RUNTIME = "com/blanket/runtime/";
    private static final String ADAPTERS = "com/blanket/adapters/";
    private static final String API = "com/blanket/api/";

    private final Map<String, String> aliases;
    private final String base;

    /**
     * @param aliases alias API type to runtime class, internal names
     * @param base    the mod's base package, internal form ({@code com/example/mod})
     */
    public TypeMap(Map<String, String> aliases, String base) {
        this.aliases = Map.copyOf(aliases);
        this.base = Objects.requireNonNull(base, "base");
    }

    @Override
    public String map(String internalName) {
        String alias = aliases.get(internalName);
        if (alias != null) return alias;
        if (internalName.startsWith(RUNTIME)) {
            return base + "/blanket/runtime/" + internalName.substring(RUNTIME.length());
        }
        if (internalName.startsWith(ADAPTERS)) {
            int slash = internalName.indexOf('/', ADAPTERS.length());
            if (slash > 0) return base + "/blanket/adapters/" + internalName.substring(slash + 1);
            return internalName;
        }
        if (internalName.startsWith(API)) {
            return base + "/blanket/api/" + internalName.substring(API.length());
        }
        return internalName;
    }
}
