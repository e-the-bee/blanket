// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 T9: the glue, hook and ClientTick bindings, read by the real loaders and resolved against the real mapping files
 * on the six core targets. The expected names are the ones Gen.java used in real games (the M0 gate).
 */
class BindingsGlueHooksTest {

    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final MappingDownloads DOWNLOADS =
            new MappingDownloads(new ArtifactFetcher(ArtifactFetcher.defaultCacheRoot(), false));

    private static Resolver.MappingFiles files() {
        return key -> {
            int colon = key.indexOf(':');
            String kind = key.substring(0, colon);
            String mc = key.substring(colon + 1);
            try {
                return switch (kind) {
                    case "pg" -> DOWNLOADS.clientMappings(mc);
                    case "int" -> DOWNLOADS.intermediary(mc, false);
                    case "lfint" -> DOWNLOADS.intermediary(mc, true);
                    case "tsrg" -> DOWNLOADS.joinedTsrg(mc, mc);
                    default -> throw new IllegalArgumentException(key);
                };
            } catch (RuntimeException e) {
                throw new IOException("mapping file " + key + " unavailable: " + e.getMessage(), e);
            }
        };
    }

    private static TargetSpec spec(Targets targets, String id) {
        int colon = id.indexOf(':');
        String loader = id.substring(0, colon);
        String mc = id.substring(colon + 1);
        Targets.Era era = targets.eras(loader).stream()
                .filter(e -> e.when().matches(loader, Version.parse(mc)))
                .findFirst().orElseThrow();
        int java = targets.versions().get(mc).java();
        String mcp = era.runtime().startsWith("srg") ? mc : null;
        return new TargetSpec(loader, mc, era.runtime(), era.emitter(), java, mcp);
    }

    /** The first rule whose selector matches the target (binding-format.md 2.2). */
    private static <T> T firstMatch(List<T> rules, java.util.function.Function<T, Selector> when, TargetSpec t) {
        for (T r : rules) {
            if (when.apply(r).matches(t.loader(), Version.parse(t.mc()))) {
                return r;
            }
        }
        return null;
    }

    /** The refs a lowering names, in step order. Constants and unsupported lowerings name none. */
    private static List<String> refsOf(Lowering l) {
        List<String> out = new ArrayList<>();
        switch (l.kind()) {
            case CHAIN:
                for (Lowering s : l.steps()) {
                    out.addAll(refsOf(s));
                }
                break;
            case INVOKE:
            case GET:
            case PUT:
            case NEW:
            case ADAPTER:
            case CAST:
                out.add(l.ref());
                break;
            default:
                break;
        }
        return out;
    }

    /** Resolves refs on a target. Offline mapping data skips the test instead of failing it. */
    private static Map<String, Resolver.Resolved> resolve(TargetSpec t, List<String> texts) {
        List<Ref> refs = new ArrayList<>();
        for (String s : texts) {
            refs.add(Ref.parse(s, "bindings", "$"));
        }
        List<Resolver.Problem> problems = new ArrayList<>();
        Map<String, Resolver.Resolved> out;
        try {
            out = new Resolver(t, "1.21.1", files()).resolve(refs, problems);
        } catch (IllegalStateException e) {
            Assumptions.assumeTrue(false, "mapping data unavailable (offline?): " + e.getMessage());
            return null;
        }
        assertTrue(problems.isEmpty(), t.id() + ": " + problems);
        return out;
    }

    private static String lastRef(Lowering l) {
        if (l.kind() == Lowering.Kind.CHAIN) {
            return l.steps().get(l.steps().size() - 1).ref();
        }
        return l.ref();
    }

    /** Expected runtime name of the last step of a glue member, per target (Gen.java, verified in real games). */
    private static final Map<String, String> GLUE_LAST_NAME = Map.ofEntries(
            Map.entry("legacy-fabric:1.8.9 GlueWorld.px", "field_3252"),
            Map.entry("legacy-fabric:1.8.9 GlueWorld.lastPx", "field_3211"),
            Map.entry("forge:1.8.9 GlueWorld.px", "s"),
            Map.entry("forge:1.8.9 GlueWorld.lastPx", "P"),
            Map.entry("fabric:1.21.1 GlueFrame.fbo", "field_1476"),
            Map.entry("fabric:1.21.1 GlueFrame.fbWidth", "field_1482"),
            Map.entry("fabric:1.21.1 GlueFrame.fbHeight", "field_1481"),
            Map.entry("fabric:1.21.1 GlueFrame.guiWidth", "method_4486"),
            Map.entry("fabric:1.21.1 GlueFrame.guiHeight", "method_4502"),
            Map.entry("neoforge:1.21.1 GlueFrame.fbo", "frameBufferId"),
            Map.entry("neoforge:1.21.1 GlueFrame.fbWidth", "width"),
            Map.entry("neoforge:1.21.1 GlueFrame.fbHeight", "height"),
            Map.entry("neoforge:1.21.1 GlueFrame.guiWidth", "getGuiScaledWidth"),
            Map.entry("neoforge:1.21.1 GlueFrame.guiHeight", "getGuiScaledHeight"),
            Map.entry("forge:1.20.1 GlueFrame.fbo", "f_83920_"),
            Map.entry("forge:1.20.1 GlueFrame.fbWidth", "f_83915_"),
            Map.entry("forge:1.20.1 GlueFrame.guiWidth", "m_85445_"),
            Map.entry("fabric:26.1.2 GlueFrame.colorTex", "glId"),
            Map.entry("fabric:26.1.2 GlueFrame.depthTex", "glId"),
            Map.entry("fabric:26.1.2 GlueFrame.fbWidth", "width"),
            Map.entry("fabric:26.1.2 GlueFrame.guiWidth", "getGuiScaledWidth"));

    /** Hook points the M0 gate used, per mixin target, with the expected runtime method name. */
    private static final Map<String, Map<String, String>> HOOKS = Map.of(
            "legacy-fabric:1.8.9", Map.of("tick.end", "method_2954", "gui.hud", "method_9420", "world.legacy", "method_9776"),
            "fabric:1.21.1", Map.of("tick.end", "method_1574", "gui.hud", "method_1753", "world.render", "method_22710"),
            "fabric:26.1.2", Map.of("tick.end", "tick", "gui.record", "extractRenderState", "gui.replay", "render",
                    "world.render", "lambda$addMainPass$0"));

    private static final List<String> SIX = List.of("legacy-fabric:1.8.9", "forge:1.8.9", "fabric:1.21.1", "neoforge:1.21.1",
            "fabric:26.1.2", "forge:1.20.1");

    @Test
    void realGlueAndHookFilesReadBackUnchanged() {
        for (String f : List.of("glue/GlueFrame.json", "glue/GlueWorld.json", "hooks.json", "events/ClientTick.json")) {
            Path p = BINDINGS.resolve(f);
            if (f.startsWith("glue/")) {
                assertEquals(BindingFiles.readJson(p), BindingFiles.readType(p).toJson(), f);
            } else if (f.equals("hooks.json")) {
                assertEquals(BindingFiles.readJson(p), BindingFiles.readHooks(p).toJson(), f);
            } else {
                assertEquals(BindingFiles.readJson(p), BindingFiles.readEvent(p).toJson(), f);
            }
        }
    }

    @Test
    void everyGlueMemberResolvesOnTheSixTargets() {
        Targets targets = BindingFiles.readTargets(BINDINGS.resolve("targets.json"));
        for (String glueFile : List.of("glue/GlueFrame.json", "glue/GlueWorld.json")) {
            TypeBinding glue = BindingFiles.readType(BINDINGS.resolve(glueFile));
            for (String id : SIX) {
                TargetSpec t = spec(targets, id);
                for (Map.Entry<String, List<TypeBinding.MemberRule>> m : glue.members().entrySet()) {
                    TypeBinding.MemberRule rule = firstMatch(m.getValue(), TypeBinding.MemberRule::when, t);
                    assertNotNull(rule, id + " has no rule for " + glueFile + " " + m.getKey());
                    Lowering l = rule.lower();
                    List<String> refs = refsOf(l);
                    if (refs.isEmpty()) {
                        continue;
                    }
                    Map<String, Resolver.Resolved> out = resolve(t, refs);
                    String simple = glue.api().substring(glue.api().lastIndexOf('.') + 1);
                    String expected = GLUE_LAST_NAME.get(id + " " + simple + "." + m.getKey());
                    if (expected != null) {
                        Resolver.Resolved last = out.get(lastRef(l));
                        assertNotNull(last, id + " " + m.getKey());
                        assertEquals(expected, last.name(), id + " " + m.getKey() + " last step");
                    }
                }
            }
        }
    }

    @Test
    void hooksResolveOnTheMixinTargets() {
        Targets targets = BindingFiles.readTargets(BINDINGS.resolve("targets.json"));
        Hooks hooks = BindingFiles.readHooks(BINDINGS.resolve("hooks.json"));
        for (Map.Entry<String, Map<String, String>> target : HOOKS.entrySet()) {
            TargetSpec t = spec(targets, target.getKey());
            for (Map.Entry<String, String> hook : target.getValue().entrySet()) {
                List<Hooks.Rule> rules = hooks.hooks().get(hook.getKey());
                assertNotNull(rules, "hook " + hook.getKey());
                Hooks.Rule rule = firstMatch(rules, Hooks.Rule::when, t);
                assertNotNull(rule, target.getKey() + " has no rule for hook " + hook.getKey());
                List<String> texts = new ArrayList<>();
                texts.add(rule.method());
                if (rule.at().kind() == Hooks.At.Kind.INVOKE || rule.at().kind() == Hooks.At.Kind.INVOKE_STRING) {
                    texts.add(rule.at().method());
                }
                Map<String, Resolver.Resolved> out = resolve(t, texts);
                Resolver.Resolved method = out.get(rule.method());
                assertNotNull(method, target.getKey() + " " + hook.getKey());
                assertEquals(hook.getValue(), method.name(), target.getKey() + " " + hook.getKey());
                assertFalse(method.field(), "a hook names a method");
            }
        }
    }

    @Test
    void clientTickRecipesCoverEverySupportedTarget() {
        Targets targets = BindingFiles.readTargets(BINDINGS.resolve("targets.json"));
        EventBinding tick = BindingFiles.readEvent(BINDINGS.resolve("events/ClientTick.json"));
        Hooks hooks = BindingFiles.readHooks(BINDINGS.resolve("hooks.json"));
        for (String id : targets.supported()) {
            TargetSpec t = spec(targets, id);
            EventBinding.Recipe recipe = firstMatch(tick.recipes(), EventBinding.Recipe::when, t);
            assertNotNull(recipe, id + " has no ClientTick recipe");
            if (recipe.hook() != null) {
                assertNotNull(firstMatch(hooks.hooks().get(recipe.hook()), Hooks.Rule::when, t),
                        id + " recipe names hook " + recipe.hook() + " with no rule for it");
            } else {
                assertEquals("game", recipe.bus(), id);
            }
        }
    }

}
