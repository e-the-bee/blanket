// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Selector grammar, binding-format.md section 2.1. */
class SelectorTest {
    private static final String FILE = "types/Player.json";

    private static Selector sel(String json) {
        return Selector.parse(json, FILE, "$.members.x[0].when");
    }

    private static boolean hit(Selector s, String loader, String mc) {
        return s.matches(loader, Version.parse(mc));
    }

    // ---- Version ordering -------------------------------------------------------------------

    @Test
    void newerYearVersionSortsAboveEveryOneDotX() {
        assertTrue(Version.parse("26.1").compareTo(Version.parse("1.21.11")) > 0);
        assertTrue(Version.parse("26.1.2").compareTo(Version.parse("26.1")) > 0);
    }

    @Test
    void shorterIdIsPaddedWithZeros() {
        assertTrue(Version.parse("1.16").equals(Version.parse("1.16.0")));
        assertTrue(Version.parse("1.16").compareTo(Version.parse("1.16.5")) < 0);
        assertTrue(Version.parse("1.8.9").compareTo(Version.parse("1.12.2")) < 0);
    }

    @Test
    void snapshotsAndGarbageAreNotReleaseIds() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Version.parse("26.4-snapshot-6"));
        assertTrue(e.getMessage().contains("snapshot"));
        assertFalse(Version.isReleaseId("1.21.x"));
        assertFalse(Version.isReleaseId(""));
        assertFalse(Version.isReleaseId("1..2"));
    }

    // ---- Examples from section 2.1 and the grammar ---------------------------------------------

    @Test
    void omittedKeysMatchEverything() {
        Selector any = sel("{}");
        assertTrue(hit(any, "forge", "1.8.9"));
        assertTrue(hit(any, "fabric", "26.1.2"));
        assertNull(any.loaders());
    }

    @Test
    void singleLoaderMatchesOnlyThatLoader() {
        Selector s = sel("{ \"loader\": \"fabric\" }");
        assertTrue(hit(s, "fabric", "1.21.1"));
        assertFalse(hit(s, "neoforge", "1.21.1"));
        assertFalse(hit(s, "legacy-fabric", "1.8.9"));
    }

    @Test
    void loaderListMatchesEachListedLoader() {
        Selector s = sel("{ \"loader\": [\"fabric\", \"legacy-fabric\"], \"mc\": \"1.8.9\" }");
        assertTrue(hit(s, "fabric", "1.8.9"));
        assertTrue(hit(s, "legacy-fabric", "1.8.9"));
        assertFalse(hit(s, "forge", "1.8.9"));
    }

    @Test
    void exactReleaseId() {
        Selector s = sel("{ \"mc\": \"1.21.1\" }");
        assertTrue(hit(s, "forge", "1.21.1"));
        assertFalse(hit(s, "forge", "1.21.11"));
        assertFalse(hit(s, "forge", "1.21"));
    }

    @Test
    void inclusiveRangeIncludesBothEnds() {
        Selector s = sel("{ \"mc\": \"1.16.5..1.21.11\" }");
        assertFalse(hit(s, "fabric", "1.16.4"));
        assertTrue(hit(s, "fabric", "1.16.5"));
        assertTrue(hit(s, "fabric", "1.21"));
        assertTrue(hit(s, "fabric", "1.21.11"));
        assertFalse(hit(s, "fabric", "26.1"));
    }

    @Test
    void openUpperRangeMatchesEverythingFromTheLowerBound() {
        Selector s = sel("{ \"mc\": \"1.21.9..\" }");
        assertFalse(hit(s, "neoforge", "1.21.8"));
        assertTrue(hit(s, "neoforge", "1.21.9"));
        assertTrue(hit(s, "neoforge", "26.1.2"));
    }

    @Test
    void openLowerRangeMatchesEverythingUpToTheUpperBound() {
        Selector s = sel("{ \"mc\": \"..1.12.2\" }");
        assertTrue(hit(s, "forge", "1.8.9"));
        assertTrue(hit(s, "forge", "1.12.2"));
        assertFalse(hit(s, "forge", "1.13.2"));
    }

    @Test
    void regexBetweenSlashes() {
        Selector s = sel("{ \"mc\": \"/^1\\\\.20\\\\..*/\" }");
        assertTrue(hit(s, "forge", "1.20.1"));
        assertTrue(hit(s, "forge", "1.20.6"));
        assertFalse(hit(s, "forge", "1.21.1"));
        assertFalse(hit(s, "forge", "26.1"));
    }

    @Test
    void loaderAndVersionMustBothMatch() {
        Selector s = sel("{ \"loader\": \"forge\", \"mc\": \"1.14..1.16.5\" }");
        assertTrue(hit(s, "forge", "1.16.5"));
        assertFalse(hit(s, "fabric", "1.16.5"));
        assertFalse(hit(s, "forge", "1.17"));
    }

    // ---- Malformed selectors name the file and the JSON path ----------------------------------

    private static void assertMalformed(String json, String mustContain) {
        BindingException e = assertThrows(BindingException.class,
                () -> Selector.parse(json, FILE, "$.members.x[0].when"));
        String msg = e.getMessage();
        assertTrue(msg.startsWith(FILE + ": $.members.x[0].when"), msg);
        assertTrue(msg.contains(mustContain), msg);
    }

    @Test
    void unknownLoaderFails() {
        assertMalformed("{ \"loader\": \"quilt\" }", "unknown loader 'quilt'");
    }

    @Test
    void unknownKeyFails() {
        assertMalformed("{ \"loader\": \"fabric\", \"minecraft\": \"1.21.1\" }", "unknown key 'minecraft'");
    }

    @Test
    void badVersionPointsAtTheMcKey() {
        BindingException e = assertThrows(BindingException.class,
                () -> Selector.parse("{ \"mc\": \"1.21.x\" }", FILE, "$.members.x[0].when"));
        assertTrue(e.getMessage().startsWith(FILE + ": $.members.x[0].when.mc:"), e.getMessage());
    }

    @Test
    void reversedRangeFails() {
        assertMalformed("{ \"mc\": \"1.21.1..1.16.5\" }", "starts above its end");
    }

    @Test
    void badRegexFails() {
        assertMalformed("{ \"mc\": \"/[1/\" }", "invalid regex");
    }

    @Test
    void emptyLoaderListFails() {
        assertMalformed("{ \"loader\": [] }", "loader list is empty");
    }

    @Test
    void notAnObjectFails() {
        assertMalformed("[\"fabric\"]", "when must be a JSON object");
    }

    @Test
    void notJsonFails() {
        assertMalformed("{ \"mc\": ", "not valid JSON");
    }

    @Test
    void anchoredRegexDoesNotMatchOtherMajor() {
        assertFalse(hit(sel("{ \"mc\": \"/^26\\\\./\" }"), "fabric", "1.21.1"));
    }
}
