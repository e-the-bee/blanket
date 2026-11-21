// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Lookup} on the invented game of {@code src/test/resources/lookup}. */
class LookupTest {
    private final Lookup lookup = new Lookup(LookupFixture.targets(), LookupFixture.files());

    private static Map<String, Lookup.Row> byTarget(Lookup.Result r) {
        return r.rows().stream().collect(Collectors.toMap(Lookup.Row::target, x -> x));
    }

    @Test
    void simpleClassNameAndFullDescriptorResolveOnEveryTargetThatHasARoute() {
        List<Lookup.Result> results = lookup.lookup("Player#getX()D", List.of());
        assertEquals(1, results.size());
        assertEquals("net/example/Player#getX()D", results.get(0).ref());
        Map<String, Lookup.Row> rows = byTarget(results.get(0));

        Lookup.Row fabric = rows.get("fabric:1.21.1");
        assertTrue(fabric.ok());
        assertEquals("class_5", fabric.owner());
        assertEquals("method_7", fabric.name());
        assertEquals("()D", fabric.desc());

        Lookup.Row neo = rows.get("neoforge:1.21.1");
        assertTrue(neo.ok());
        assertEquals("net/example/Player", neo.owner());
        assertEquals("getX", neo.name());

        // Mojang names have no route to a 1.8.9 target: the row says so and names the step
        for (String legacy : List.of("legacy-fabric:1.8.9", "forge:1.8.9")) {
            Lookup.Row row = rows.get(legacy);
            assertFalse(row.ok(), legacy);
            assertEquals("route", row.step());
            assertTrue(row.message().contains("`lf:`"), row.message());
        }
    }

    @Test
    void legacyIdsResolveOnTheLegacyTargetsAndTheObfuscatedForgeTarget() {
        Map<String, Lookup.Row> rows = byTarget(lookup.lookup("lf:class_15#method_17()D", List.of()).get(0));
        assertEquals("class_15", rows.get("legacy-fabric:1.8.9").owner());
        assertEquals("method_17", rows.get("legacy-fabric:1.8.9").name());
        // Forge 1.8.9 runs on obfuscated names
        assertEquals("e", rows.get("forge:1.8.9").owner());
        assertEquals("g", rows.get("forge:1.8.9").name());
        assertFalse(rows.get("fabric:1.21.1").ok());
        assertEquals("route", rows.get("fabric:1.21.1").step());
    }

    @Test
    void bareMemberNameListsEveryOverload() {
        List<Lookup.Result> results = lookup.lookup("net.example.Player#getX", List.of("neoforge:1.21.1"));
        List<String> refs = results.stream().map(Lookup.Result::ref).sorted().toList();
        assertEquals(List.of("net/example/Player#getX()D", "net/example/Player#getX(D)D"), refs);
        for (Lookup.Result r : results) assertTrue(r.rows().get(0).ok(), r.ref());
    }

    @Test
    void classOnlyQueryResolvesTheClass() {
        Lookup.Row row = lookup.lookup("net.example.Client", List.of("fabric:1.21.1")).get(0).rows().get(0);
        assertTrue(row.ok());
        assertEquals("class_1", row.owner());
        assertEquals(null, row.name());
    }

    @Test
    void fieldReferenceWithAType() {
        Lookup.Row row = lookup.lookup("net.example.Client#player:Lnet/example/Player;", List.of("fabric:1.21.1")).get(0).rows().get(0);
        assertTrue(row.ok());
        assertTrue(row.field());
        assertEquals("field_2", row.name());
        assertEquals("Lclass_5;", row.desc());
    }

    @Test
    void failureNamesTheStepAndOffersMembersOfTheSameOwner() {
        Lookup.Row row = lookup.lookup("net.example.Player#getX()F", List.of("neoforge:1.21.1")).get(0).rows().get(0);
        assertFalse(row.ok());
        assertEquals("pg:1.21.1|named|obf", row.step());
        assertTrue(row.candidates().contains("same name, other descriptor: net/example/Player#getX()D"), row.candidates().toString());
        assertTrue(row.candidates().contains("same name, other descriptor: net/example/Player#getX(D)D"), row.candidates().toString());
    }

    @Test
    void failureOffersMembersWithTheSameDescriptor() {
        Lookup.Row row = lookup.lookup("net.example.Player#getY()D", List.of("neoforge:1.21.1")).get(0).rows().get(0);
        assertFalse(row.ok());
        assertTrue(row.candidates().contains("same descriptor, other name: net/example/Player#getX()D"), row.candidates().toString());
    }

    @Test
    void unknownSimpleClassNameListsNearbyClasses() {
        Lookup.Result r = lookup.lookup("Playe#getX()D", List.of()).get(0);
        Lookup.Row row = r.rows().get(0);
        assertEquals("*", row.target());
        assertFalse(row.ok());
        assertTrue(row.candidates().contains("net/example/Player"), row.candidates().toString());
    }

    @Test
    void tableHasOneLinePerTargetAndShowsTheFailingStep() {
        String[] lines = Lookup.table(lookup.lookup("Player#getX()D", List.of("fabric:1.21.1", "forge:1.8.9"))).split("\n");
        assertEquals(3, lines.length);
        assertEquals("net/example/Player#getX()D", lines[0]);
        assertEquals("  fabric:1.21.1          class_5.method_7 ()D", lines[1]);
        assertTrue(lines[2].startsWith("  forge:1.8.9            FAIL at route: mojang: "), lines[2]);
    }

    @Test
    void unknownNamespaceIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> lookup.lookup("nope:net.example.Player", List.of()));
    }
}
