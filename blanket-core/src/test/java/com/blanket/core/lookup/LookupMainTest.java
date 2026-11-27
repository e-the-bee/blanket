// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exit code of {@code lookup}: the table is the answer unless the user's own targets fail or nothing resolves. */
class LookupMainTest {
    private final Lookup lookup = new Lookup(LookupFixture.targets(), LookupFixture.files());

    private int exit(String ref, String... named) {
        return LookupMain.exitCode(lookup.lookup(ref, List.of(named)), named.length > 0);
    }

    @Test
    void aReferenceValidOnPartOfTheMatrixExitsZero() {
        // Mojang names resolve on fabric and neoforge 1.21.1 and have no route to the 1.8.9 targets
        List<Lookup.Result> results = lookup.lookup("Player#getX()D", List.of());
        assertTrue(results.get(0).rows().stream().anyMatch(r -> !r.ok()));
        assertTrue(results.get(0).rows().stream().anyMatch(Lookup.Row::ok));
        assertEquals(0, exit("Player#getX()D"));
    }

    @Test
    void aTargetTheUserNamedThatFailsExitsOne() {
        assertEquals(1, exit("Player#getX()D", "legacy-fabric:1.8.9"));
        assertEquals(1, exit("Player#getX()D", "fabric:1.21.1", "forge:1.8.9"));
    }

    @Test
    void namedTargetsThatAllResolveExitZero() {
        assertEquals(0, exit("Player#getX()D", "fabric:1.21.1", "neoforge:1.21.1"));
    }

    @Test
    void aReferenceThatResolvesNowhereExitsOne() {
        assertEquals(1, exit("Player#noSuchMember()D"));
    }

    @Test
    void noRowsAtAllExitsOne() {
        assertEquals(1, LookupMain.exitCode(List.of(), false));
    }
}
