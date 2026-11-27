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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Constructors have no rows in mapping files: a {@code <init>} ref keeps its name and gets owner and descriptor mapped. */
class ResolverConstructorTest {

    private static final String REF = "net.minecraft.client.Minecraft#<init>(Lnet/minecraft/client/Minecraft;)V";
    private static final TargetSpec TARGET = new TargetSpec("fabric", "1.99", "official", "fabric", 21, null);

    @TempDir
    Path tmp;

    @Test
    void constructorResolvesThroughAMappingHopWithoutARow() throws IOException {
        Path pg = tmp.resolve("pg.txt");
        Files.writeString(pg, "net.minecraft.client.Minecraft -> fgo:\n");
        List<Resolver.Problem> problems = new ArrayList<>();
        Resolver.Resolved r = new Resolver(TARGET, "1.99", key -> pg, LookupFixture.targets().gameClasses())
                .resolve(List.of(Ref.parse(REF, "test", "$")), problems).get(REF);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals("fgo", r.owner());
        assertEquals("<init>", r.name());
        assertEquals("(Lfgo;)V", r.desc());
    }
}
