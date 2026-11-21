// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.testsupport.RealData;
import com.blanket.core.validate.ApiClasses;
import org.objectweb.asm.Type;
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

    /** Resolves refs on a target. Offline mapping data skips the test instead of failing it; other failures fail it. */
    private static Map<String, Resolver.Resolved> resolve(TargetSpec t, List<String> texts) {
        List<Ref> refs = new ArrayList<>();
        for (String s : texts) {
            refs.add(Ref.parse(s, "bindings", "$"));
        }
        List<Resolver.Problem> problems = new ArrayList<>();
        Map<String, Resolver.Resolved> out;
        try {
            out = new Resolver(t, "1.21.1", files(), BindingFiles.readTargets(BINDINGS.resolve("targets.json")).gameClasses()).resolve(refs, problems);
        } catch (IllegalStateException e) {
            throw RealData.skipIfOffline(e);
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
            Map.entry("fabric:26.1.2 GlueFrame.guiWidth", "getGuiScaledWidth"),
            // D-056: the HUD and world conversions (Gen.java HudMixin, WorldMixin and the forge/neoforge listeners)
            Map.entry("fabric:1.21.1 GlueFrame.partialTick", "method_60637"),
            Map.entry("fabric:1.21.1 GlueGui.flush", "method_51452"),
            Map.entry("fabric:1.21.1 GlueWorld.camX", "field_1352"),
            Map.entry("fabric:1.21.1 GlueWorld.camY", "field_1351"),
            Map.entry("fabric:1.21.1 GlueWorld.camZ", "field_1350"),
            Map.entry("neoforge:1.21.1 GlueFrame.partialTick", "getGameTimeDeltaPartialTick"),
            Map.entry("neoforge:1.21.1 GlueGui.flush", "flush"),
            Map.entry("neoforge:1.21.1 GlueWorld.camX", "x"),
            Map.entry("forge:1.20.1 GlueGui.flush", "m_280262_"),
            Map.entry("forge:1.20.1 GlueWorld.poseMatrix", "m_252922_"),
            Map.entry("forge:1.20.1 GlueWorld.camX", "f_82479_"),
            Map.entry("forge:1.20.1 GlueWorld.camY", "f_82480_"),
            Map.entry("forge:1.20.1 GlueWorld.camZ", "f_82481_"),
            Map.entry("fabric:26.1.2 GlueFrame.partialTick", "getGameTimeDeltaPartialTick"),
            Map.entry("fabric:26.1.2 GlueWorld.camera", "getMainCamera"),
            Map.entry("fabric:26.1.2 GlueWorld.viewProjection", "getViewRotationProjectionMatrix"),
            Map.entry("fabric:26.1.2 GlueWorld.camX", "x"),
            Map.entry("fabric:26.1.2 GlueWorld.camZ", "z"));

    /** Expected runtime names of every resolved step of a glue member, where M0 used more than the last one. */
    private static final Map<String, List<String>> GLUE_ALL_NAMES = Map.of(
            "fabric:1.21.1 GlueWorld.camX", List.of("class_4184", "method_19326", "field_1352"),
            "forge:1.20.1 GlueWorld.camX", List.of("net/minecraft/client/Camera", "m_90583_", "f_82479_"),
            "forge:1.20.1 GlueWorld.poseMatrix", List.of("com/mojang/blaze3d/vertex/PoseStack", "m_85850_", "m_252922_"),
            "fabric:26.1.2 GlueWorld.camera", List.of("getInstance", "gameRenderer", "getMainCamera"),
            "fabric:26.1.2 GlueWorld.camX", List.of("net/minecraft/client/Camera", "position", "x"));

    private static final List<String> GLUE_FILES = List.of("glue/GlueFrame.json", "glue/GlueWorld.json", "glue/GlueGui.json");

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
        for (String f : List.of("glue/GlueFrame.json", "glue/GlueWorld.json", "glue/GlueGui.json", "hooks.json",
                "events/ClientTick.json", "events/HudRender.json", "events/WorldRender.json")) {
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
        for (String glueFile : GLUE_FILES) {
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
                    List<String> all = GLUE_ALL_NAMES.get(id + " " + simple + "." + m.getKey());
                    if (all != null) {
                        List<String> names = new ArrayList<>();
                        for (String r : refs) {
                            Resolver.Resolved res = out.get(r);
                            assertNotNull(res, id + " " + m.getKey() + " " + r);
                            names.add(res.name() != null ? res.name() : res.owner());
                        }
                        String last = all.get(all.size() - 1);
                        assertEquals(all.subList(1, all.size()), names.subList(1, names.size()), id + " " + m.getKey());
                        assertTrue(names.get(0).endsWith(all.get(0)), id + " " + m.getKey() + " first step " + names.get(0));
                        assertEquals(last, names.get(names.size() - 1));
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
            for (EventBinding.Injection in : recipe.injections()) {
                assertEquals("tickEnd", in.dispatch(), id);
                if (in.hook() != null) {
                    assertNotNull(firstMatch(hooks.hooks().get(in.hook()), Hooks.Rule::when, t),
                            id + " recipe names hook " + in.hook() + " with no rule for it");
                } else {
                    assertEquals("game", in.bus(), id);
                }
            }
        }
    }

    /** Glue members by simple glue type name, read from the real glue files. */
    private static Map<String, TypeBinding> glueTypes() {
        Map<String, TypeBinding> out = new java.util.HashMap<>();
        for (String f : GLUE_FILES) {
            TypeBinding g = BindingFiles.readType(BINDINGS.resolve(f));
            out.put(g.api().substring(g.api().lastIndexOf('.') + 1), g);
        }
        return out;
    }

    /** Checks a payload or before source: every glue member it names has a bound (not unsupported) rule on t. */
    private static void checkSource(EventBinding.Source s, Map<String, TypeBinding> glue, TargetSpec t, String where) {
        if (s.kind().equals("glue")) {
            String member = s.value().getAsString();
            int paren = member.indexOf('(');
            String plain = paren < 0 ? member : member.substring(0, paren);
            String type = plain.substring(0, plain.indexOf('#'));
            String name = plain.substring(plain.indexOf('#') + 1);
            TypeBinding g = glue.get(type);
            assertNotNull(g, where + ": no glue file for " + type);
            List<TypeBinding.MemberRule> rules = g.members().get(name);
            assertNotNull(rules, where + ": no glue member " + plain);
            TypeBinding.MemberRule rule = firstMatch(rules, TypeBinding.MemberRule::when, t);
            assertNotNull(rule, where + ": " + plain + " has no rule");
            assertFalse(rule.lower().kind() == Lowering.Kind.UNSUPPORTED, where + ": " + plain + " is unsupported here");
        }
        for (EventBinding.Source a : s.args()) {
            checkSource(a, glue, t, where);
        }
    }

    /** The Dispatch method each target calls per event file (M0 Dispatch and its hooks, D-057). */
    private static List<String> expectedDispatch(String event, String id) {
        boolean legacy = id.endsWith(":1.8.9");
        if (event.equals("HUD_RENDER")) {
            return id.equals("fabric:26.1.2") ? List.of("hudRecord", "hudReplay") : List.of("hud");
        }
        return List.of(legacy ? "worldLegacy" : "world");
    }

    @Test
    void hudAndWorldRecipesBindOnTheSixTargets() {
        Targets targets = BindingFiles.readTargets(BINDINGS.resolve("targets.json"));
        Hooks hooks = BindingFiles.readHooks(BINDINGS.resolve("hooks.json"));
        Map<String, TypeBinding> glue = glueTypes();
        for (String file : List.of("events/HudRender.json", "events/WorldRender.json")) {
            EventBinding ev = BindingFiles.readEvent(BINDINGS.resolve(file));
            for (String id : SIX) {
                TargetSpec t = spec(targets, id);
                EventBinding.Recipe recipe = firstMatch(ev.recipes(), EventBinding.Recipe::when, t);
                assertNotNull(recipe, id + " has no recipe in " + file);
                String where = id + " " + file;
                List<String> dispatch = new ArrayList<>();
                int before = 0;
                for (EventBinding.Injection in : recipe.injections()) {
                    dispatch.add(in.dispatch());
                    if (in.hook() != null) {
                        Hooks.Rule rule = firstMatch(hooks.hooks().get(in.hook()), Hooks.Rule::when, t);
                        assertNotNull(rule, where + " names hook " + in.hook() + " with no rule for it");
                        String expected = HOOKS.get(id).get(in.hook());
                        assertNotNull(expected, where + ": hook " + in.hook() + " is not one the M0 gate used");
                    } else {
                        assertEquals("game", in.bus(), where);
                    }
                    for (Map.Entry<String, EventBinding.Source> p : in.payload().entrySet()) {
                        checkSource(p.getValue(), glue, t, where + " payload " + p.getKey());
                    }
                    for (EventBinding.Source b : in.before()) {
                        checkSource(b, glue, t, where + " before");
                    }
                    before += in.before().size();
                }
                assertEquals(expectedDispatch(ev.event(), id), dispatch, where + " dispatch");
                // M0 flushed GuiGraphics before immediate HUD drawing on exactly these three targets
                boolean flushes = ev.event().equals("HUD_RENDER")
                        && List.of("fabric:1.21.1", "neoforge:1.21.1", "forge:1.20.1").contains(id);
                assertEquals(flushes ? 1 : 0, before, where + " before");
            }
        }
    }

    /** D-057: every recipe's dispatch names a Dispatch method whose parameter count equals the payload entry count. */
    @Test
    void everyDispatchNamesADispatchMethodOfThePayloadArity() {
        List<ApiClasses.Method> methods = ApiClasses.of(BindingsGlueHooksTest.class.getClassLoader())
                .methods("com/blanket/runtime/Dispatch").orElseThrow();
        for (String file : List.of("events/ClientTick.json", "events/HudRender.json", "events/WorldRender.json")) {
            EventBinding ev = BindingFiles.readEvent(BINDINGS.resolve(file));
            for (int i = 0; i < ev.recipes().size(); i++) {
                for (EventBinding.Injection in : ev.recipes().get(i).injections()) {
                    String where = file + " recipe " + i + " dispatch " + in.dispatch();
                    List<ApiClasses.Method> named = methods.stream()
                            .filter(m -> m.isStatic() && m.name().equals(in.dispatch())).toList();
                    assertEquals(1, named.size(), where + ": Dispatch methods of that name " + named);
                    assertEquals(Type.getArgumentTypes(named.get(0).desc()).length, in.payload().size(),
                            where + " payload " + in.payload().keySet());
                }
            }
        }
    }
}
