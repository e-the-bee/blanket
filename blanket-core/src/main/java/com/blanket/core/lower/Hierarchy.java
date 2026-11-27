// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Super class and interfaces of classes by output (runtime) internal name, for frame computation. */
public interface Hierarchy extends AutoCloseable {

    /** What the class file says about one class. */
    record Facts(String superName, List<String> interfaces, boolean isInterface) {}

    /** The facts of a class, or empty when this hierarchy does not know it. */
    Optional<Facts> get(String outputInternalName);

    @Override
    void close() throws IOException;

    /** A hierarchy that knows no class. */
    static Hierarchy empty() {
        return new Hierarchy() {
            @Override
            public Optional<Facts> get(String outputInternalName) {
                return Optional.empty();
            }

            @Override
            public void close() {}
        };
    }

    /** {@code known} first, then {@code next}; closing closes {@code next}. */
    static Hierarchy layered(Map<String, Facts> known, Hierarchy next) {
        Map<String, Facts> copy = Map.copyOf(known);
        return new Hierarchy() {
            @Override
            public Optional<Facts> get(String outputInternalName) {
                Facts f = copy.get(outputInternalName);
                return f != null ? Optional.of(f) : next.get(outputInternalName);
            }

            @Override
            public void close() throws IOException {
                next.close();
            }
        };
    }
}
