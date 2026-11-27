// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.testsupport.RealData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 TKIT: glue/GlueTestkit.json on the six core targets. {@code quit} resolves to the game's stop method (names from the
 * mapping files: yarn-legacy {@code MinecraftClient#scheduleStop}, Mojang {@code Minecraft#stop}); {@code press} is an
 * adapter of the target's adapter set.
 */
class BindingsTestkitTest {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final MappingDownloads DOWNLOADS = new MappingDownloads(new ArtifactFetcher(ArtifactFetcher.defaultCacheRoot(), false));

    /** Runtime name of {@code quit}'s last step per target (lfint/int tiny, joined tsrg, javap on the client jars). */
    private static final Map<String, String> QUIT = Map.of(
            "legacy-fabric:1.8.9", "method_2949",
            "forge:1.8.9", "m",
            "fabric:1.21.1", "method_1592",
            "neoforge:1.21.1", "stop",
            "forge:1.20.1", "m_91395_",
            "fabric:26.1.2", "stop");

    private static final Map<String, String> PRESS_SET = Map.of(
            "legacy-fabric:1.8.9", "legacy", "forge:1.8.9", "legacy", "fabric:1.21.1", "modern", "neoforge:1.21.1", "modern",
            "forge:1.20.1", "modern", "fabric:26.1.2", "unobf");

    private static Resolver.MappingFiles files() {
        return key -> {
            int colon = key.indexOf(':');
            String kind = key.substring(0, colon), mc = key.substring(colon + 1);
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
        String loader = id.substring(0, colon), mc = id.substring(colon + 1);
        Targets.Era era = targets.eras(loader).stream().filter(e -> e.when().matches(loader, Version.parse(mc))).findFirst().orElseThrow();
        return new TargetSpec(loader, mc, era.runtime(), era.emitter(), targets.versions().get(mc).java(), era.runtime().startsWith("srg") ? mc : null);
    }

    private static TypeBinding.MemberRule first(List<TypeBinding.MemberRule> rules, TargetSpec t) {
        for (TypeBinding.MemberRule r : rules) if (r.when().matches(t.loader(), Version.parse(t.mc()))) return r;
        return null;
    }

    @Test
    void theFileReadsBackUnchanged() {
        Path p = BINDINGS.resolve("glue/GlueTestkit.json");
        assertEquals(BindingFiles.readJson(p), BindingFiles.readType(p).toJson());
    }

    @Test
    void quitResolvesToTheStopMethodAndPressToTheTargetsAdapterSet() {
        Targets targets = BindingFiles.readTargets(BINDINGS.resolve("targets.json"));
        TypeBinding glue = BindingFiles.readType(BINDINGS.resolve("glue/GlueTestkit.json"));
        for (String id : QUIT.keySet()) {
            TargetSpec t = spec(targets, id);
            TypeBinding.MemberRule quit = first(glue.members().get("quit"), t);
            assertNotNull(quit, id + " quit");
            assertEquals(Lowering.Kind.CHAIN, quit.lower().kind(), id);
            List<Ref> refs = new ArrayList<>();
            for (Lowering s : quit.lower().steps()) refs.add(Ref.parse(s.ref(), "bindings", "$"));
            List<Resolver.Problem> problems = new ArrayList<>();
            Map<String, Resolver.Resolved> out;
            try {
                out = new Resolver(t, "1.21.1", files(), targets.gameClasses()).resolve(refs, problems);
            } catch (IllegalStateException e) {
                throw RealData.skipIfOffline(e);
            }
            assertTrue(problems.isEmpty(), id + ": " + problems);
            Resolver.Resolved last = out.get(quit.lower().steps().get(1).ref());
            assertNotNull(last, id);
            assertEquals(QUIT.get(id), last.name(), id + " quit");
            assertEquals("()V", last.desc(), id + " quit descriptor");

            TypeBinding.MemberRule press = first(glue.members().get("press"), t);
            assertNotNull(press, id + " press");
            assertEquals(Lowering.Kind.ADAPTER, press.lower().kind(), id);
            assertEquals(PRESS_SET.get(id) + ":com.blanket.adapters.KeyPress#press", press.lower().ref(), id);
            assertEquals(PRESS_SET.get(id), targets.versions().get(t.mc()).adapters(), id + " adapter set of " + t.mc());
        }
    }
}
