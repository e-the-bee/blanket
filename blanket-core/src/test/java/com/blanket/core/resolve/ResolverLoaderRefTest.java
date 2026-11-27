// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.resolve;

import com.blanket.core.lookup.LookupFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loader references on a notch-runtime target (Forge 1.8.9) are used literally: no Fabric intermediary file is asked
 * for, not for the owner, not for loader classes in the descriptor. A Minecraft class in the descriptor is translated
 * from the anchor's Mojang names without leaving the anchor's own ProGuard file.
 */
class ResolverLoaderRefTest {

    private static final String PHASE = "loader:net.minecraftforge.fml.common.gameevent.TickEvent#phase:Lnet/minecraftforge/fml/common/gameevent/TickEvent$Phase;";
    private static final String END = "loader:net.minecraftforge.fml.common.gameevent.TickEvent$Phase#END:Lnet/minecraftforge/fml/common/gameevent/TickEvent$Phase;";
    private static final String PHASE_CLASS = "loader:net.minecraftforge.fml.common.gameevent.TickEvent$Phase";
    private static final String WITH_MC = "loader:net.minecraftforge.fml.common.gameevent.TickEvent#world:Lnet/minecraft/client/Minecraft;";
    private static final String WITH_LF = "loader:net.minecraftforge.client.event.GuiOpenEvent#gui:Lnet/minecraft/class_388;";
    private static final TargetSpec FORGE_189 = new TargetSpec("forge", "1.8.9", "obf", "forge-legacy", 8, null);

    @TempDir
    Path tmp;

    private final List<String> asked = new ArrayList<>();

    private Resolver.MappingFiles files() {
        return key -> {
            asked.add(key);
            if (key.equals("lfint:1.8.9")) {
                Path lf = tmp.resolve("lfint.tiny");
                Files.writeString(lf, "tiny\t2\t0\tofficial\tintermediary\nc\taxu\tnet/minecraft/class_388\n");
                return lf;
            }
            if (!key.equals("pg:1.8.9")) throw new IOException("no fixture for " + key + " (HTTP 404)");
            Path pg = tmp.resolve("pg.txt");
            Files.writeString(pg, "net.minecraft.client.Minecraft -> ave:\n");
            return pg;
        };
    }

    private Map<String, Resolver.Resolved> resolve(List<Resolver.Problem> problems, String... texts) {
        List<Ref> refs = new ArrayList<>();
        for (String t : texts) refs.add(Ref.parse(t, "test", "$"));
        return new Resolver(FORGE_189, "1.8.9", files(), LookupFixture.targets().gameClasses()).resolve(refs, problems);
    }

    @Test
    void loaderClassesAndMembersNeedNoMappingFile() {
        List<Resolver.Problem> problems = new ArrayList<>();
        Map<String, Resolver.Resolved> out = resolve(problems, PHASE, END, PHASE_CLASS);
        assertTrue(problems.isEmpty(), problems.toString());
        assertTrue(asked.isEmpty(), "asked for " + asked);
        Resolver.Resolved phase = out.get(PHASE);
        assertEquals("net/minecraftforge/fml/common/gameevent/TickEvent", phase.owner());
        assertEquals("phase", phase.name());
        assertEquals("Lnet/minecraftforge/fml/common/gameevent/TickEvent$Phase;", phase.desc());
        assertEquals("net/minecraftforge/fml/common/gameevent/TickEvent$Phase", out.get(PHASE_CLASS).owner());
        assertEquals("END", out.get(END).name());
    }

    @Test
    void minecraftClassInALoaderDescriptorIsTranslatedWithoutAnIntermediary() {
        List<Resolver.Problem> problems = new ArrayList<>();
        Map<String, Resolver.Resolved> out = resolve(problems, WITH_MC);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(List.of("pg:1.8.9"), asked);
        assertEquals("Lave;", out.get(WITH_MC).desc());
        assertEquals("world", out.get(WITH_MC).name());
    }

    @Test
    void legacyIdInALoaderDescriptorOnALegacyTargetIsTranslatedThroughTheLegacyIntermediary() {
        List<Resolver.Problem> problems = new ArrayList<>();
        Map<String, Resolver.Resolved> out = resolve(problems, WITH_LF);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(List.of("lfint:1.8.9"), asked, "no Mojang file: the legacy range has no Mojang names");
        assertEquals("Laxu;", out.get(WITH_LF).desc());
        assertEquals("net/minecraftforge/client/event/GuiOpenEvent", out.get(WITH_LF).owner());
        assertEquals("gui", out.get(WITH_LF).name());
    }

    @Test
    void routeOfALoaderReferenceIsEmpty() {
        Resolver r = new Resolver(FORGE_189, "1.8.9", files(), LookupFixture.targets().gameClasses());
        assertTrue(r.route(Ref.parse(PHASE, "test", "$")).isEmpty());
        assertTrue(r.route(Ref.parse(PHASE_CLASS, "test", "$")).isEmpty());
    }
}
