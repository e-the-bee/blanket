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
 * M1 T9: the M0 subset bindings in blanket-bindings, read by the real loaders and resolved against the real mapping
 * files on the six core targets. Expected names are the ones Gen.java hard-coded and verified in real games.
 */
class BindingsM0SubsetTest {

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

    /** One target's runtime row from targets.json. */
    private static TargetSpec spec(Targets targets, String id) {
        int colon = id.indexOf(':');
        String loader = id.substring(0, colon);
        String mc = id.substring(colon + 1);
        Targets.Era era = targets.eras(loader).stream()
                .filter(e -> e.when().matches(loader, com.blanket.core.bindings.Version.parse(mc)))
                .findFirst().orElseThrow();
        int java = targets.versions().get(mc).java();
        String mcp = era.runtime().startsWith("srg") ? mc : null;
        return new TargetSpec(loader, mc, era.runtime(), era.emitter(), java, mcp);
    }

    /** The first rule whose selector matches the target, as binding-format.md 2.2 requires. */
    private static <T> T firstMatch(List<T> rules, java.util.function.Function<T, com.blanket.core.bindings.Selector> when, TargetSpec t) {
        for (T r : rules) {
            if (when.apply(r).matches(t.loader(), com.blanket.core.bindings.Version.parse(t.mc()))) return r;
        }
        throw new AssertionError("no rule matches " + t.id());
    }

    /** Resolves the reference of a direct lowering on the target, and returns "owner name desc". */
    private static String resolveRef(TargetSpec t, String ref) {
        List<Ref> refs = List.of(Ref.parse(ref, "bindings", "$"));
        List<Resolver.Problem> problems = new ArrayList<>();
        Map<String, Resolver.Resolved> out;
        try {
            out = new Resolver(t, "1.21.1", files()).resolve(refs, problems);
        } catch (IllegalStateException e) {
            Assumptions.assumeTrue(false, "mapping data unavailable (offline?): " + e.getMessage());
            return null;
        }
        assertTrue(problems.isEmpty(), t.id() + " " + ref + ": " + problems);
        Resolver.Resolved r = out.get(ref);
        assertNotNull(r, t.id() + " " + ref);
        return r.owner() + " " + r.name() + " " + r.desc();
    }

    @Test
    void clientAndPlayerResolveToTheGenNamesOnTheSixTargets() throws IOException {
        Targets targets = BindingFiles.readTargets(BINDINGS.resolve("targets.json"));
        TypeBinding client = BindingFiles.readType(BINDINGS.resolve("types/Client.json"));
        TypeBinding player = BindingFiles.readType(BINDINGS.resolve("types/Player.json"));

        // target -> expected "owner name desc" for Client.get, Client.player, Player.x
        Map<String, String[]> expected = Map.of(
                "legacy-fabric:1.8.9", new String[] {"net/minecraft/class_1600 method_2965 ()Lnet/minecraft/class_1600;",
                        "net/minecraft/class_1600 field_10310 Lnet/minecraft/class_518;", "net/minecraft/class_864 field_3252 D"},
                "forge:1.8.9", new String[] {"ave A ()Lave;", "ave h Lbew;", "pk s D"},
                "fabric:1.21.1", new String[] {"net/minecraft/class_310 method_1551 ()Lnet/minecraft/class_310;",
                        "net/minecraft/class_310 field_1724 Lnet/minecraft/class_746;", "net/minecraft/class_1297 method_23317 ()D"},
                "neoforge:1.21.1", new String[] {"net/minecraft/client/Minecraft getInstance ()Lnet/minecraft/client/Minecraft;",
                        "net/minecraft/client/Minecraft player Lnet/minecraft/client/player/LocalPlayer;",
                        "net/minecraft/world/entity/Entity getX ()D"},
                "fabric:26.1.2", new String[] {"net/minecraft/client/Minecraft getInstance ()Lnet/minecraft/client/Minecraft;",
                        "net/minecraft/client/Minecraft player Lnet/minecraft/client/player/LocalPlayer;",
                        "net/minecraft/world/entity/Entity getX ()D"},
                "forge:1.20.1", new String[] {"net/minecraft/client/Minecraft m_91087_ ()Lnet/minecraft/client/Minecraft;",
                        "net/minecraft/client/Minecraft f_91074_ Lnet/minecraft/client/player/LocalPlayer;",
                        "net/minecraft/world/entity/Entity m_20185_ ()D"});

        for (Map.Entry<String, String[]> e : expected.entrySet()) {
            TargetSpec t = spec(targets, e.getKey());
            String get = resolveRef(t, firstMatch(client.members().get("get"), MemberRuleSelector.INSTANCE, t).lower().ref());
            String player0 = resolveRef(t, firstMatch(client.members().get("player"), MemberRuleSelector.INSTANCE, t).lower().ref());
            String x = resolveRef(t, firstMatch(player.members().get("x"), MemberRuleSelector.INSTANCE, t).lower().ref());
            assertEquals(e.getValue()[0], get, t.id() + " Client.get");
            assertEquals(e.getValue()[1], player0, t.id() + " Client.player");
            assertEquals(e.getValue()[2], x, t.id() + " Player.x");
        }
    }

    @Test
    void everyMemberRuleListMatchesEverySupportedTarget() throws IOException {
        Targets targets = BindingFiles.readTargets(BINDINGS.resolve("targets.json"));
        for (String file : List.of("Client", "Player")) {
            TypeBinding tb = BindingFiles.readType(BINDINGS.resolve("types/" + file + ".json"));
            for (String id : targets.supported()) {
                TargetSpec t = spec(targets, id);
                assertTrue(tb.representation().stream().anyMatch(r -> r.when().matches(t.loader(), com.blanket.core.bindings.Version.parse(t.mc()))),
                        file + " representation has no rule for " + id);
                for (Map.Entry<String, List<TypeBinding.MemberRule>> m : tb.members().entrySet()) {
                    assertTrue(m.getValue().stream().anyMatch(r -> r.when().matches(t.loader(), com.blanket.core.bindings.Version.parse(t.mc()))),
                            file + "." + m.getKey() + " has no rule for " + id);
                }
            }
        }
        assertFalse(targets.supported().isEmpty());
    }

    /** Adapter so {@link #firstMatch} can read the selector of a member rule. */
    private enum MemberRuleSelector implements java.util.function.Function<TypeBinding.MemberRule, com.blanket.core.bindings.Selector> {
        INSTANCE;

        @Override
        public com.blanket.core.bindings.Selector apply(TypeBinding.MemberRule r) {
            return r.when();
        }
    }
}
