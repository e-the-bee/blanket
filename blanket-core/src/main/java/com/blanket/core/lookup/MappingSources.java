// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.bindings.Targets;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.resolve.Resolver;

import java.io.IOException;

/** The resolver's file keys ({@code pg:}, {@code int:}, {@code lfint:}, {@code tsrg:} plus a release) mapped to downloaded files. */
public final class MappingSources {
    private MappingSources() {}

    public static Resolver.MappingFiles files(MappingDownloads downloads, Targets targets) {
        return key -> {
            int colon = key.indexOf(':');
            String kind = key.substring(0, colon);
            String mc = key.substring(colon + 1);
            try {
                switch (kind) {
                    case "pg":
                        return downloads.clientMappings(mc);
                    case "int":
                        return downloads.intermediary(mc, false);
                    case "lfint":
                        return downloads.intermediary(mc, true);
                    case "tsrg": {
                        String mcp = Matrix.mcpVersion(targets, mc);
                        return downloads.joinedTsrg(mc, mcp == null ? mc : mcp);
                    }
                    default:
                        throw new IllegalArgumentException(key);
                }
            } catch (RuntimeException e) {
                throw new IOException("mapping file " + key + " unavailable: " + e.getMessage(), e);
            }
        };
    }
}
