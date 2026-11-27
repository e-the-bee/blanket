// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import java.util.Arrays;
import java.util.List;

/**
 * The part of the key hand-over (events-and-hooks 4.7) that needs no game type: appending the queued mappings to the
 * game's key array. The Fabric eras and Forge 1.8.9 replace the options' key array with the result before the options
 * read {@code options.txt}, which is what Forge's {@code ClientRegistry.registerKeyBinding} and NeoForge's
 * {@code RegisterKeyMappingsEvent#register} do one mapping at a time.
 */
public final class KeyHandover {
    private KeyHandover() {}

    /**
     * {@code keys} followed by every queued mapping it does not already hold (by identity), in registration order. The
     * result has the runtime component type of {@code keys} (the game's key mapping class); {@code keys} itself is
     * returned when nothing is new.
     */
    public static Object[] append(Object[] keys, List<Object> queued) {
        Object[] out = keys;
        int n = keys.length;
        for (Object mapping : queued) {
            if (contains(out, n, mapping)) continue;
            if (out == keys) out = Arrays.copyOf(keys, keys.length + queued.size());
            out[n++] = mapping;
        }
        return out == keys ? keys : Arrays.copyOf(out, n);
    }

    private static boolean contains(Object[] keys, int n, Object mapping) {
        for (int i = 0; i < n; i++) {
            if (keys[i] == mapping) return true;
        }
        return false;
    }
}
