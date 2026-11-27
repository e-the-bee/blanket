// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.lookup.LookupFixture;
import com.blanket.core.resolve.TargetSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One fixture binding per check that fails it (C1, C2, C3, C7, C8), on the invented game of
 * {@code src/test/resources/lookup} and {@link FixtureGame}. {@link #baseline()} is green; each test changes one file of it.
 */
class ValidatorTest {
    private static final String PLAYER = "com.blanket.core.validate.fixture.FxPlayer";
    private static final String CLIENT = "com.blanket.core.validate.fixture.FxClient";
    private static final String GLUE = "com.blanket.core.validate.fixture.FxGlue";

    @TempDir
    Path tmp;

    FixtureGame.Data data;

    @BeforeEach
    void game() throws IOException {
        data = new FixtureGame.Data(FixtureGame.clientJar(tmp, true, true));
    }

    // ---------------------------------------------------------------------------------------- fixtures

    static String player(String members) {
        return """
                { "schema": 1, "api": "%s",
                  "representation": [ { "when": {}, "alias": "net.example.Player" } ],
                  "members": { %s } }""".formatted(PLAYER, members);
    }

    static final String X = """
            "x": [ { "when": {}, "lower": { "get": "net.example.Player#x:D" } } ]""";
    static final String ARMOR = """
            "armor": [ { "when": {}, "lower": { "invoke": "net.example.Player#armor()I" } } ]""";

    static final String CLIENT_FILE = """
            { "schema": 1, "api": "%s",
              "representation": [ { "when": {}, "alias": "net.example.Client" } ],
              "members": {
                "get": [ { "when": {}, "lower": { "invoke": "net.example.Client#getInstance()Lnet/example/Client;" } } ],
                "player": [ { "when": {}, "lower": { "get": "net.example.Client#player:Lnet/example/Player;" } } ] } }""".formatted(CLIENT);

    /** The glue file: {@code fbo} with the given lowering, and {@code armorOf(Object)} which a leading cast makes direct. */
    static String glue(String fboLowering) {
        return """
                { "schema": 1, "api": "%s", "members": {
                    "fbo": [ { "when": {}, "lower": %s } ],
                    "armorOf": [ { "when": {}, "lower": { "chain": [
                        { "cast": "net.example.Player" }, { "invoke": "net.example.Player#armor()I" } ] } } ] } }""".formatted(GLUE, fboLowering);
    }

    static final String HOOKS = """
            { "schema": 1, "hooks": { "tick.end": [
              { "when": {}, "method": "net.example.Client#tick()V", "at": "RETURN" } ] } }""";

    static final String TICK_EVENT = """
            { "schema": 1, "event": "FX_TICK",
              "recipes": [ { "when": {}, "mixin": { "hook": "tick.end", "dispatch": "tickEnd" } } ] }""";

    /** A binding set that is green on neoforge:1.21.1 with {@link #game()}. */
    static Map<String, String> baseline() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("types/FxPlayer.json", player(X + ",\n" + ARMOR));
        f.put("types/FxClient.json", CLIENT_FILE);
        f.put("glue/FxGlue.json", glue("{ \"const\": -1 }"));
        f.put("hooks.json", HOOKS);
        f.put("events/FxTick.json", TICK_EVENT);
        return f;
    }

    /** The lookup fixture's targets.json with {@code supported} replaced. */
    static String targets(String... supported) throws IOException {
        String text = Files.readString(LookupFixture.resource("targets.json"), StandardCharsets.UTF_8);
        List<String> quoted = new ArrayList<>();
        for (String s : supported) quoted.add("\"" + s + "\"");
        return text.replaceAll("\"supported\":\\s*\\[[^\\]]*\\]", "\"supported\": [" + String.join(", ", quoted) + "]");
    }

    Report run(Map<String, String> files, String... supported) throws IOException {
        Path dir = Files.createTempDirectory(tmp, "bindings");
        Map<String, String> all = new LinkedHashMap<>(files);
        all.put("targets.json", targets(supported.length == 0 ? new String[] {"neoforge:1.21.1"} : supported));
        for (Map.Entry<String, String> e : all.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        return new Validator(BindingSet.load(dir), data, ApiClasses.of(ValidatorTest.class.getClassLoader())).run();
    }

    /** The one error of a report, after checking that it carries the target and ends with a next step. */
    static Diagnostic only(Report r) {
        assertEquals(1, r.errors().size(), r.format());
        Diagnostic d = r.errors().get(0);
        assertTrue(d.format().contains("; next step: ") && d.format().endsWith(d.nextStep().trim()), d.format());
        assertTrue(d.format().contains(d.target()), d.format());
        return d;
    }

    // ---------------------------------------------------------------------------------------- green

    @Test
    void baselineIsGreen() throws IOException {
        Report r = run(baseline());
        assertTrue(r.ok(), r.format());
        assertTrue(r.warnings().isEmpty(), r.format());
    }

    @Test
    void everyErrorOfEveryTargetIsReportedAtOnce() throws IOException {
        // two targets, one broken member: both targets report it, the run does not stop at the first (binding-format.md 2.5)
        Map<String, String> f = baseline();
        f.put("types/FxPlayer.json", player(X + ",\n" + "\"armor\": [ { \"when\": {}, \"lower\": { \"invoke\": \"net.example.Player#armor()F\" } } ]"));
        Report r = run(f, "neoforge:1.21.1", "fabric:1.21.1");
        List<String> targets = r.errors().stream().map(Diagnostic::target).sorted().toList();
        assertEquals(List.of("fabric:1.21.1", "neoforge:1.21.1"), targets, r.format());
    }

    // ---------------------------------------------------------------------------------------- C1 coverage

    @Test
    void c1PublicMemberWithoutARuleListIsUnbound() throws IOException {
        Map<String, String> f = baseline();
        f.put("types/FxPlayer.json", player(X));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.UNBOUND, d.code());
        assertEquals("*", d.target());
        assertEquals("FxPlayer.armor", d.member());
        assertTrue(d.detail().startsWith("C1 coverage"), d.detail());
    }

    @Test
    void c1RuleListWithoutARuleForASupportedTargetIsUnbound() throws IOException {
        Map<String, String> f = baseline();
        f.put("types/FxPlayer.json", player(X + ",\n\"armor\": [ { \"when\": { \"mc\": \"1.8.9\" }, \"lower\": { \"unsupported\": \"only 1.8.9\" } } ]"));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.UNBOUND, d.code());
        assertEquals("neoforge:1.21.1", d.target());
        assertEquals("FxPlayer.armor", d.member());
    }

    @Test
    void c1EventWithoutARecipeForASupportedTargetIsUnbound() throws IOException {
        Map<String, String> f = baseline();
        f.put("events/FxTick.json", TICK_EVENT.replace("\"when\": {}", "\"when\": { \"loader\": \"fabric\" }"));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.UNBOUND, d.code());
        assertEquals("neoforge:1.21.1", d.target());
        assertEquals("event FX_TICK", d.member());
    }

    private static String viaEvent(String event, String when, String via) {
        return """
                { "schema": 1, "event": "%s", "recipes": [ { "when": %s, "via": "%s" } ] }""".formatted(event, when, via);
    }

    @Test
    void c1ViaAnEventWithARecipeForTheTargetIsGreen() throws IOException {
        Map<String, String> f = baseline();
        f.put("events/FxJoin.json", viaEvent("FX_JOIN", "{}", "FX_TICK"));
        assertTrue(run(f).ok(), run(f).format());
    }

    @Test
    void c1ViaAnEventWithoutAFileIsUnbound() throws IOException {
        Map<String, String> f = baseline();
        f.put("events/FxJoin.json", viaEvent("FX_JOIN", "{}", "FX_NOWHERE"));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.UNBOUND, d.code());
        assertEquals("event FX_JOIN", d.member());
        assertTrue(d.detail().contains("goes via FX_NOWHERE") && d.detail().contains("which no events/ file binds"), d.detail());
    }

    @Test
    void c1ViaAnEventWithoutARecipeForTheTargetIsUnbound() throws IOException {
        Map<String, String> f = baseline();
        f.put("events/FxTick.json", TICK_EVENT.replace("\"when\": {}", "\"when\": { \"loader\": \"fabric\" }"));
        f.put("events/FxJoin.json", viaEvent("FX_JOIN", "{}", "FX_TICK"));
        Report r = run(f);
        List<String> members = r.errors().stream().map(Diagnostic::member).sorted().toList();
        assertEquals(List.of("event FX_JOIN", "event FX_TICK"), members, r.format());
        Diagnostic join = r.errors().stream().filter(d -> d.member().equals("event FX_JOIN")).findFirst().orElseThrow();
        assertTrue(join.detail().contains("which has no recipe for neoforge:1.21.1 in events/FxTick.json"), join.detail());
    }

    @Test
    void c1ViaChainIsUnbound() throws IOException {
        Map<String, String> f = baseline();
        f.put("events/FxJoin.json", viaEvent("FX_JOIN", "{}", "FX_TICK"));
        f.put("events/FxLeave.json", viaEvent("FX_LEAVE", "{}", "FX_JOIN"));
        Diagnostic d = only(run(f));
        assertEquals("event FX_LEAVE", d.member());
        assertTrue(d.detail().contains("goes via FX_JOIN") && d.detail().contains("a via chain or cycle"), d.detail());
    }

    @Test
    void c1ViaCycleIsUnboundOnBothEvents() throws IOException {
        Map<String, String> f = baseline();
        f.put("events/FxJoin.json", viaEvent("FX_JOIN", "{}", "FX_LEAVE"));
        f.put("events/FxLeave.json", viaEvent("FX_LEAVE", "{}", "FX_JOIN"));
        Report r = run(f);
        assertEquals(List.of("event FX_JOIN", "event FX_LEAVE"), r.errors().stream().map(Diagnostic::member).sorted().toList(), r.format());
        assertTrue(r.errors().stream().allMatch(d -> d.detail().contains("a via chain or cycle")), r.format());
    }

    // ---------------------------------------------------------------------------------------- C2 resolution

    @Test
    void c2ReferenceWithNoRowInTheMappingFailsAtTheMappingStep() throws IOException {
        Map<String, String> f = baseline();
        f.put("types/FxPlayer.json", player(X + ",\n\"armor\": [ { \"when\": {}, \"lower\": { \"invoke\": \"net.example.Player#armor()F\" } } ]"));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("neoforge:1.21.1", d.target());
        assertEquals("FxPlayer.armor", d.member());
        assertEquals("net.example.Player#armor()F", d.reference());
        assertEquals("pg:1.21.1|named|obf", d.namespaceStep());
        assertTrue(d.nextStep().contains("lookup"), d.nextStep());
    }

    @Test
    void c2MappedMemberAbsentFromTheClientJarFailsAtTheJar() throws IOException {
        data = new FixtureGame.Data(FixtureGame.clientJar(tmp, true, false));   // e.h()I is not in this jar
        Report r = run(baseline());
        // FxPlayer.armor and the glue member armorOf both lower to Player#armor()I
        assertEquals(List.of("FxGlue.armorOf", "FxPlayer.armor"), r.errors().stream().map(Diagnostic::member).sorted().toList(), r.format());
        for (Diagnostic d : r.errors()) {
            assertEquals(ErrorCode.UNRESOLVED, d.code());
            assertEquals("jar", d.namespaceStep());
            assertEquals("net.example.Player#armor()I", d.reference());
            assertTrue(d.detail().contains("h()I"), d.detail());
        }
    }

    @Test
    void c2ModernNamesOnALegacyTargetFailAtTheRoute() throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("types/FxPlayer.json", player(X + ",\n" + ARMOR));
        Report r = run(f, "forge:1.8.9");
        assertEquals(3, r.errors().size(), r.format());   // the alias and both members
        for (Diagnostic d : r.errors()) {
            assertEquals(ErrorCode.UNRESOLVED, d.code());
            assertEquals("route", d.namespaceStep());
            assertEquals("forge:1.8.9", d.target());
            assertTrue(d.nextStep().contains("lf:"), d.nextStep());
        }
    }

    @Test
    void c2MalformedReferenceIsReportedNotThrown() throws IOException {
        Map<String, String> f = baseline();
        f.put("types/FxPlayer.json", player(X + ",\n\"armor\": [ { \"when\": {}, \"lower\": { \"invoke\": \"TODO:net.example.Player\" } } ]"));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("parse", d.namespaceStep());
    }

    // ---------------------------------------------------------------------------------------- C3 shape

    @Test
    void c3ResultTypeThatDiffersFromTheApiIsAShapeError() throws IOException {
        Map<String, String> f = baseline();
        f.put("types/FxPlayer.json", player(X + ",\n\"armor\": [ { \"when\": {}, \"lower\": { \"invoke\": \"net.example.Player#getX()D\" } } ]"));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.SHAPE, d.code());
        assertEquals("neoforge:1.21.1", d.target());
        assertEquals("FxPlayer.armor", d.member());
        assertTrue(d.detail().contains("(Le;)I") && d.detail().contains("(Le;)D"), "both descriptors: " + d.detail());
    }

    @Test
    void c3ReceiverMayBeASubclassOfTheLoweredOwner() throws IOException {
        // the API receiver is Player (class e) which extends Base (class k); the lowering is declared on Base
        Map<String, String> f = baseline();
        f.put("types/FxPlayer.json", player("\"x\": [ { \"when\": {}, \"lower\": { \"invoke\": \"net.example.Base#baseX()D\" } } ],\n" + ARMOR));
        assertTrue(run(f).ok());
    }

    @Test
    void c3ChainStepThatDoesNotFitThePreviousResultIsAShapeError() throws IOException {
        Map<String, String> f = baseline();
        f.put("glue/FxGlue.json", glue("""
                { "chain": [ { "invoke": "net.example.Client#getInstance()Lnet/example/Client;" },
                             { "invoke": "net.example.Player#armor()I" } ] }"""));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.SHAPE, d.code());
        assertEquals("FxGlue.fbo", d.member());
        assertTrue(d.detail().contains("chain step 2"), d.detail());
    }

    @Test
    void c3ChainThatFitsIsGreen() throws IOException {
        // getInstance -> player field -> armor: every step's owner accepts the previous result; the baseline's armorOf
        // takes an Object, which its leading cast accepts
        Map<String, String> f = baseline();
        f.put("glue/FxGlue.json", glue("""
                { "chain": [
                    { "invoke": "net.example.Client#getInstance()Lnet/example/Client;" },
                    { "get": "net.example.Client#player:Lnet/example/Player;" },
                    { "invoke": "net.example.Player#armor()I" } ] }"""));
        assertTrue(run(f).ok());
    }

    // ---------------------------------------------------------------------------------------- C7 hooks

    @Test
    void c7HookMethodMissingFromTheJarIsUnresolved() throws IOException {
        data = new FixtureGame.Data(FixtureGame.clientJar(tmp, false, true));   // a.c()V is not in this jar
        Diagnostic d = only(run(baseline()));
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("hook tick.end (event FX_TICK)", d.member());
        assertEquals("jar", d.namespaceStep());
        assertTrue(d.detail().startsWith("C7 hooks"), d.detail());
        assertTrue(d.format().contains("hook tick.end (event FX_TICK)"), d.format());
    }

    @Test
    void c7HookNamedByTwoEventsNamesBothInTheDiagnostic() throws IOException {
        data = new FixtureGame.Data(FixtureGame.clientJar(tmp, false, true));   // a.c()V is not in this jar
        Map<String, String> f = baseline();
        f.put("events/FxTock.json", TICK_EVENT.replace("FX_TICK", "FX_TOCK"));
        Diagnostic d = only(run(f));
        assertEquals("hook tick.end (event FX_TICK, FX_TOCK)", d.member());
    }

    private static final String TWO_CALLS = """
            { "schema": 1, "hooks": { "tick.end": [
              { "when": {}, "method": "net.example.Client#tick()V",
                "at": { "invoke": "net.example.Client#getInstance()Lnet/example/Client;", "shift": "AFTER"%s } } ] } }""";

    @Test
    void c7InvokePointThatMatchesTwoCallSitesIsUnresolved() throws IOException {
        Map<String, String> f = baseline();
        f.put("hooks.json", TWO_CALLS.formatted(""));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("call sites", d.namespaceStep());
        assertEquals("hook tick.end (event FX_TICK)", d.member());
        assertTrue(d.detail().contains("2 call sites"), d.detail());
        assertTrue(d.nextStep().contains("ordinal"), d.nextStep());
    }

    @Test
    void c7OrdinalChoosesOneOfTheCallSites() throws IOException {
        Map<String, String> f = baseline();
        f.put("hooks.json", TWO_CALLS.formatted(", \"ordinal\": 1"));
        assertTrue(run(f).ok());
    }

    @Test
    void c7MixinRecipeOnLegacyForgeIsUnsupported() throws IOException {
        // forge 1.8.9 is the forge-legacy emitter: no Mixin. Only a hook and an event, written with lf: ids.
        Map<String, String> f = new LinkedHashMap<>();
        f.put("hooks.json", """
                { "schema": 1, "hooks": { "tick.end": [
                  { "when": {}, "method": "lf:class_11#method_13()V", "at": "RETURN" } ] } }""");
        f.put("events/FxTick.json", TICK_EVENT);
        Diagnostic d = only(run(f, "forge:1.8.9"));
        assertEquals(ErrorCode.UNSUPPORTED, d.code());
        assertEquals("forge:1.8.9", d.target());
        assertEquals("event FX_TICK", d.member());
        assertTrue(d.detail().startsWith("C7 hooks"), d.detail());
    }

    private static String nativeEvent(String eventClass) {
        return """
                { "schema": 1, "event": "FX_NATIVE",
                  "recipes": [ { "when": {}, "native": { "event": "loader:%s", "bus": "game", "dispatch": "tickEnd" } } ] }""".formatted(eventClass);
    }

    @Test
    void c7NativeEventClassMissingFromTheLoaderJarIsUnresolved() throws IOException {
        data.loaderJar = FixtureGame.loaderJar(tmp);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("events/FxNative.json", nativeEvent("net.example.NoSuchEvent"));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("event FX_NATIVE", d.member());
        assertEquals("jar", d.namespaceStep());
        assertTrue(d.detail().startsWith("C7 hooks") && d.detail().contains("net/example/NoSuchEvent"), d.detail());

        Map<String, String> ok = new LinkedHashMap<>();
        ok.put("events/FxNative.json", nativeEvent("net.example.RealEvent"));
        assertTrue(run(ok).ok());
    }

    @Test
    void c7NativeEventClassIsUnresolvedAtLoaderJarWhenTheLoaderJarIsUnknown() throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("events/FxNative.json", nativeEvent("net.example.NoSuchEvent"));
        Diagnostic d = only(run(f));
        assertEquals(ErrorCode.UNRESOLVED, d.code());
        assertEquals("event FX_NATIVE", d.member());
        assertEquals("loader-jar", d.namespaceStep());
        assertTrue(d.nextStep().contains("loader-artifacts.json"), d.nextStep());
    }

    // ---------------------------------------------------------------------------------------- C8 mapping terms

    @Test
    void c8MojangHeaderThatChangedIsUnsupported() throws IOException {
        data.mojangMappings = FixtureGame.mappingsWithHeader(tmp, "terms.txt", "(c) 2030 Microsoft Corporation. New terms apply.");
        Diagnostic d = only(run(baseline()));
        assertEquals(ErrorCode.UNSUPPORTED, d.code());
        assertEquals("*:1.21.1", d.target());
        assertTrue(d.detail().startsWith("C8 mapping terms"), d.detail());
        assertTrue(d.nextStep().contains("mappings-and-versions.md"), d.nextStep());
    }

    @Test
    void c8HeaderWithTheDocumentedTextPassesWhateverTheLineBreaks() throws IOException {
        String broken = MappingTerms.MOJANG_HEADER.replace(". ", ".\n#   ");
        Path p = tmp.resolve("wrapped.txt");
        Files.writeString(p, "# " + broken + "\nnet.example.Client -> a:\n", StandardCharsets.UTF_8);
        data.mojangMappings = p;
        assertTrue(run(baseline()).ok());
    }

    @Test
    void c8McpLicenseThatChangedIsUnsupported() throws IOException {
        TargetSpec srg = new TargetSpec("forge", "1.20.1", "srgmoj", "forge-modern", 17, "1.20.1");
        List<Diagnostic> errors = new ArrayList<>();
        data.mcpLicense = MappingTerms.MCP_LICENSE.replace("Permission is granted", "Permission is not granted");
        MappingTerms.check(LookupFixture.targets(), List.of(srg), data, errors);
        assertEquals(1, errors.size());
        assertEquals(ErrorCode.UNSUPPORTED, errors.get(0).code());
        assertTrue(errors.get(0).detail().startsWith("C8 mapping terms"));

        errors.clear();
        data.mcpLicense = MappingTerms.MCP_LICENSE;
        MappingTerms.check(LookupFixture.targets(), List.of(srg), data, errors);
        assertTrue(errors.isEmpty(), errors.toString());
    }

    // ---------------------------------------------------------------------------------------- misc

    @Test
    void ruleNoSupportedTargetReachesIsAWarningNotAnError() throws IOException {
        Map<String, String> f = baseline();
        f.put("types/FxPlayer.json", player(X + ",\n\"armor\": [ { \"when\": {}, \"lower\": { \"invoke\": \"net.example.Player#armor()I\" } },"
                + " { \"when\": { \"mc\": \"1.8.9\" }, \"lower\": { \"unsupported\": \"never reached\" } } ]"));
        Report r = run(f);
        assertTrue(r.ok(), r.format());
        assertEquals(1, r.warnings().size(), r.format());
        assertTrue(r.warnings().get(0).contains("dead rule"), r.warnings().get(0));
    }
}
