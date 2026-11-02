// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.resolve;

import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.mapping.MappingDownloads;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M0 oracle (M1 T7). The names M0's Gen.java hard-coded for the core six were verified in real games; each test
 * resolves the same members through the real mapping files and expects exactly those names. Skipped when the
 * mapping files cannot be obtained.
 */
class NameRoutesOracleTest {

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

    private static Map<String, Resolver.Resolved> resolve(TargetSpec t, List<String> refs) {
        List<Ref> parsed = new ArrayList<>();
        for (String r : refs) parsed.add(Ref.parse(r, "oracle", "$"));
        List<Resolver.Problem> problems = new ArrayList<>();
        try {
            Map<String, Resolver.Resolved> out = new Resolver(t, "1.21.1", files()).resolve(parsed, problems);
            assertTrue(problems.isEmpty(), "unexpected problems: " + problems);
            return out;
        } catch (IllegalStateException e) {
            Assumptions.assumeTrue(false, "mapping data unavailable (offline?): " + e.getMessage());
            return null;
        }
    }

    private static void expect(Resolver.Resolved r, String owner, String name, String desc) {
        assertEquals(owner, r.owner());
        assertEquals(name, r.name());
        assertEquals(desc, r.desc());
    }

    private static final String GET_INSTANCE = "net.minecraft.client.Minecraft#getInstance()Lnet/minecraft/client/Minecraft;";
    private static final String PLAYER = "net.minecraft.client.Minecraft#player:Lnet/minecraft/client/player/LocalPlayer;";
    private static final String GET_X = "net.minecraft.world.entity.Entity#getX()D";

    @Test
    void legacyFabric189MatchesM0() {
        TargetSpec t = new TargetSpec("legacy-fabric", "1.8.9", "lf", "fabric", 8, null);
        String m = "lf:net/minecraft/class_1600#method_2965()Lnet/minecraft/class_1600;";
        String f = "lf:net/minecraft/class_1600#field_10310:Lnet/minecraft/class_518;";
        String x = "lf:net/minecraft/class_864#field_3252:D";
        Map<String, Resolver.Resolved> r = resolve(t, List.of(m, f, x));
        expect(r.get(m), "net/minecraft/class_1600", "method_2965", "()Lnet/minecraft/class_1600;");
        expect(r.get(f), "net/minecraft/class_1600", "field_10310", "Lnet/minecraft/class_518;");
        expect(r.get(x), "net/minecraft/class_864", "field_3252", "D");
    }

    @Test
    void forge189MatchesM0() {
        TargetSpec t = new TargetSpec("forge", "1.8.9", "obf", "forge-legacy", 8, null);
        String m = "lf:net/minecraft/class_1600#method_2965()Lnet/minecraft/class_1600;";
        String f = "lf:net/minecraft/class_1600#field_10310:Lnet/minecraft/class_518;";
        String x = "lf:net/minecraft/class_864#field_3252:D";
        Map<String, Resolver.Resolved> r = resolve(t, List.of(m, f, x));
        expect(r.get(m), "ave", "A", "()Lave;");
        expect(r.get(f), "ave", "h", "Lbew;");
        expect(r.get(x), "pk", "s", "D");
    }

    @Test
    void fabric1211MatchesM0() {
        TargetSpec t = new TargetSpec("fabric", "1.21.1", "intermediary", "fabric", 21, null);
        Map<String, Resolver.Resolved> r = resolve(t, List.of(GET_INSTANCE, PLAYER, GET_X));
        expect(r.get(GET_INSTANCE), "net/minecraft/class_310", "method_1551", "()Lnet/minecraft/class_310;");
        expect(r.get(PLAYER), "net/minecraft/class_310", "field_1724", "Lnet/minecraft/class_746;");
        expect(r.get(GET_X), "net/minecraft/class_1297", "method_23317", "()D");
    }

    @Test
    void neoforge1211MatchesM0() {
        TargetSpec t = new TargetSpec("neoforge", "1.21.1", "mojang", "neoforge", 21, null);
        Map<String, Resolver.Resolved> r = resolve(t, List.of(GET_INSTANCE, PLAYER, GET_X));
        expect(r.get(GET_INSTANCE), "net/minecraft/client/Minecraft", "getInstance", "()Lnet/minecraft/client/Minecraft;");
        expect(r.get(PLAYER), "net/minecraft/client/Minecraft", "player", "Lnet/minecraft/client/player/LocalPlayer;");
        expect(r.get(GET_X), "net/minecraft/world/entity/Entity", "getX", "()D");
    }

    @Test
    void forge1201MatchesM0ThroughTheIntermediaryOfTheTargetVersion() {
        // Mojang names of 1.21.1 reach 1.20.1 through intermediary ids (P-05); the output names are SRG members.
        TargetSpec t = new TargetSpec("forge", "1.20.1", "srgmoj", "forge-modern", 17, "1.20.1");
        Map<String, Resolver.Resolved> r = resolve(t, List.of(GET_INSTANCE, PLAYER, GET_X));
        expect(r.get(GET_INSTANCE), "net/minecraft/client/Minecraft", "m_91087_", "()Lnet/minecraft/client/Minecraft;");
        expect(r.get(PLAYER), "net/minecraft/client/Minecraft", "f_91074_", "Lnet/minecraft/client/player/LocalPlayer;");
        expect(r.get(GET_X), "net/minecraft/world/entity/Entity", "m_20185_", "()D");
    }

    @Test
    void fabric2612UsesOfficialNames() {
        TargetSpec t = new TargetSpec("fabric", "26.1.2", "official", "fabric", 25, null);
        String m = "official:" + GET_INSTANCE;
        Map<String, Resolver.Resolved> r = resolve(t, List.of(m));
        expect(r.get(m), "net/minecraft/client/Minecraft", "getInstance", "()Lnet/minecraft/client/Minecraft;");
    }
}
