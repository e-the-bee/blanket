// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.bindings.Targets;
import com.blanket.core.resolve.Resolver;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

/**
 * A small invented game for tests of {@code lookup}, {@code scaffold} and {@code validateBindings}: files under
 * {@code src/test/resources/lookup}. Mojang names are {@code net.example.Client} (obf {@code a}) and
 * {@code net.example.Player} (obf {@code e}); intermediary ids are {@code class_1}/{@code class_5} (1.21.1) and
 * {@code class_11}/{@code class_15} (Legacy Fabric 1.8.9).
 */
public final class LookupFixture {
    private LookupFixture() {}

    public static Path resource(String name) {
        try {
            return Path.of(LookupFixture.class.getResource("/lookup/" + name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalStateException("missing test resource /lookup/" + name, e);
        }
    }

    /** {@code targets.json} of the fixture: legacy-fabric:1.8.9, forge:1.8.9, fabric:1.21.1, neoforge:1.21.1. */
    public static Targets targets() {
        return BindingFiles.readTargets(resource("targets.json"));
    }

    /** The fixture mapping files under the resolver's keys ({@code pg:1.21.1}, {@code int:1.21.1}, {@code lfint:1.8.9}). */
    public static Resolver.MappingFiles files() {
        return key -> {
            int colon = key.indexOf(':');
            String name = key.substring(0, colon) + "-" + key.substring(colon + 1) + (key.startsWith("pg:") ? ".txt" : ".tiny");
            try {
                return resource(name);
            } catch (IllegalStateException e) {
                throw new IOException("no fixture mapping file for " + key, e);
            }
        };
    }
}
