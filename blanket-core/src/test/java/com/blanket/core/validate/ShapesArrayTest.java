// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * C3 result check (M3 KEYS): a glue member typed {@code Object} may lower to a game array ({@code GlueKeys.mappings}
 * returns {@code Options#keyMappings}); the JVM assigns every array to {@code Object} and to nothing else that is not
 * an array type.
 */
class ShapesArrayTest {
    private final Shapes shapes = new Shapes(null, Map.of());

    @Test
    void everyArrayIsAnObject() {
        assertTrue(shapes.isSubtype("[Lnet/minecraft/class_304;", "Ljava/lang/Object;"));
        assertTrue(shapes.isSubtype("[I", "Ljava/lang/Object;"));
        assertTrue(shapes.isSubtype("[[Lfake/K;", "Ljava/lang/Object;"));
    }

    @Test
    void anArrayIsNoOtherClassAndPrimitivesAreNoObjects() {
        assertFalse(shapes.isSubtype("[Lfake/K;", "Lfake/K;"));
        assertFalse(shapes.isSubtype("[Lfake/K;", "Ljava/lang/String;"));
        assertFalse(shapes.isSubtype("I", "Ljava/lang/Object;"));
        assertTrue(shapes.isSubtype("[Lfake/K;", "[Lfake/K;"));
    }
}
