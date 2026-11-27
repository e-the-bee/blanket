// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.blanket.core.lookup.Matrix;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.validate.BindingSet;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Features} and the real {@code blanket-bindings/features.json}. */
class FeaturesTest {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");

    @TempDir
    Path tmp;

    private static TargetSpec target(Targets t, String id) {
        return Matrix.spec(t, id);
    }

    @Test
    void offhandIsAbsentOnTheTwo189TargetsAndPresentOnTheOthers() throws IOException {
        BindingSet b = BindingSet.load(BINDINGS);
        assertEquals(Set.of("OFFHAND"), b.features().names());
        for (String id : new String[] {"legacy-fabric:1.8.9", "forge:1.8.9"}) {
            assertFalse(b.features().available("OFFHAND", target(b.targets(), id)), id);
        }
        for (String id : new String[] {"fabric:1.21.1", "neoforge:1.21.1", "fabric:26.1.2", "forge:1.20.1"}) {
            assertTrue(b.features().available("OFFHAND", target(b.targets(), id)), id);
        }
    }

    @Test
    void rangeIsTheAvailableRulesAsText() throws IOException {
        assertEquals("mc 1.9..", BindingSet.load(BINDINGS).features().range("OFFHAND"));
        String json = """
                { "schema": 1, "features": {
                  "TWO": { "available": [ { "when": { "loader": ["fabric", "forge"], "mc": "1.14.." } }, { "when": { "mc": "1.8.9" } } ] },
                  "ALL": { "available": [ { "when": {} } ] } } }""";
        Features f = Features.parse(JsonParser.parseString(json), "features.json");
        assertEquals("fabric, forge mc 1.14.. or mc 1.8.9", f.range("TWO"));
        assertEquals("every target", f.range("ALL"));
        assertEquals(null, f.range("NONE"));
    }

    @Test
    void anUnknownKeyIsRejectedWithFileAndPath() {
        String json = """
                { "schema": 1, "features": { "OFFHAND": { "available": [ { "when": { "mc": "1.9.." } } ], "colour": "red" } } }""";
        BindingException e = assertThrows(BindingException.class, () -> Features.parse(JsonParser.parseString(json), "features.json"));
        assertTrue(e.getMessage().contains("features.json"), e.getMessage());
        assertTrue(e.getMessage().contains("$.features.OFFHAND.colour"), e.getMessage());
    }

    @Test
    void anUnknownTopLevelKeyIsRejected() {
        BindingException e = assertThrows(BindingException.class,
                () -> Features.parse(JsonParser.parseString("{ \"schema\": 1, \"features\": {}, \"extra\": 1 }"), "features.json"));
        assertTrue(e.getMessage().contains("$.extra"), e.getMessage());
    }

    @Test
    void aFolderWithoutFeaturesJsonHasNoFeatures() throws IOException {
        Files.copy(BINDINGS.resolve("targets.json"), tmp.resolve("targets.json"));
        BindingSet b = BindingSet.load(tmp);
        assertTrue(b.features().names().isEmpty());
        TargetSpec t = target(b.targets(), "fabric:1.21.1");
        assertThrows(IllegalArgumentException.class, () -> b.features().available("OFFHAND", t));
    }

    @Test
    void aBadFeaturesFileFailsTheLoadNamingTheFile() throws IOException {
        Files.copy(BINDINGS.resolve("targets.json"), tmp.resolve("targets.json"));
        Files.writeString(tmp.resolve("features.json"), "{ \"schema\": 1, \"features\": { \"X\": { \"available\": [ { \"when\": { \"os\": \"x\" } } ] } } }",
                StandardCharsets.UTF_8);
        BindingException e = assertThrows(BindingException.class, () -> BindingSet.load(tmp));
        assertTrue(e.getMessage().contains("features.json"), e.getMessage());
        assertTrue(e.getMessage().contains("$.features.X.available[0].when.os"), e.getMessage());
    }
}
