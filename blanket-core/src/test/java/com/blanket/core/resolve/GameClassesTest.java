// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.resolve;

import com.blanket.core.bindings.BindingException;
import com.blanket.core.bindings.Targets;
import com.blanket.core.lookup.LookupFixture;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game-class predicate comes from targets.json (D-059): a game class with no mapping row is UNRESOLVED, never a
 * library class, and a targets.json without the keys does not load.
 */
class GameClassesTest {

    private static final TargetSpec TARGET = new TargetSpec("neoforge", "1.99", "mojang", "neoforge", 21, null);
    private static final String HEADER = "{\"schema\":1,\"anchor\":\"1.21.1\",\"loaders\":{},\"versions\":{}";

    @TempDir
    Path tmp;

    private static GameClasses game() {
        return LookupFixture.targets().gameClasses();
    }

    private Resolver.MappingFiles files() throws IOException {
        Path pg = tmp.resolve("pg.txt");
        Files.writeString(pg, "net.minecraft.client.Minecraft -> fgo:\n    bew$a field -> a\n");
        return key -> pg;
    }

    private Map<String, Resolver.Resolved> resolve(List<Resolver.Problem> problems, String text) throws IOException {
        return new Resolver(TARGET, "1.99", files(), game()).resolve(List.of(Ref.parse(text, "test", "$")), problems);
    }

    @Test
    void predicateTakesGamePackagesAndObfuscatedNamesWithInnerSuffix() {
        GameClasses g = game();
        assertTrue(g.isGame("net/minecraft/client/Minecraft"));
        assertTrue(g.isGame("com/mojang/blaze3d/Missing"));
        assertTrue(g.isGame("ave"));
        assertTrue(g.isGame("bew$a"));
        assertFalse(g.isGame("net/minecraftforge/event/TickEvent"));
        assertFalse(g.isGame("org/joml/Matrix4f"));
    }

    @Test
    void obfuscatedInnerClassWithoutARowIsUnresolved() throws IOException {
        String ref = "net.minecraft.client.Minecraft#field:Lbew$a;";
        List<Resolver.Problem> problems = new ArrayList<>();
        resolve(problems, ref);
        assertEquals(1, problems.size(), problems.toString());
        assertEquals("UNRESOLVED", problems.get(0).code());
        assertTrue(problems.get(0).message().contains("class bew$a has no row in pg:1.99"), problems.get(0).message());
    }

    @Test
    void mojangClassWithoutARowIsUnresolved() throws IOException {
        String ref = "com.mojang.blaze3d.Missing";
        List<Resolver.Problem> problems = new ArrayList<>();
        Map<String, Resolver.Resolved> out = resolve(problems, ref);
        assertTrue(out.isEmpty(), out.toString());
        assertEquals(1, problems.size(), problems.toString());
        assertEquals("pg:1.99|named|obf", problems.get(0).step());
        assertEquals("class com/mojang/blaze3d/Missing has no row in pg:1.99", problems.get(0).message());
    }

    @Test
    void jomlStaysALibraryClass() throws IOException {
        String ref = "org.joml.Matrix4f#get([F)[F";
        List<Resolver.Problem> problems = new ArrayList<>();
        Resolver.Resolved r = resolve(problems, ref).get(ref);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals("org/joml/Matrix4f", r.owner());
        assertEquals("get", r.name());
    }

    @Test
    void targetsWithoutGamePackagesFailsToLoadWithThePath() {
        BindingException e = assertThrows(BindingException.class,
                () -> Targets.parse(JsonParser.parseString(HEADER + ",\"obfuscatedClass\":\"[a-z]+\"}"), "targets.json"));
        assertEquals("targets.json: $.gamePackages: missing \"gamePackages\"", e.getMessage());
    }

    @Test
    void targetsWithoutObfuscatedClassFailsToLoadWithThePath() {
        BindingException e = assertThrows(BindingException.class,
                () -> Targets.parse(JsonParser.parseString(HEADER + ",\"gamePackages\":[\"net/minecraft/\"]}"), "targets.json"));
        assertEquals("targets.json: $.obfuscatedClass: missing \"obfuscatedClass\"", e.getMessage());
    }

    @Test
    void gamePackageWithoutTrailingSlashFailsToLoadWithThePath() {
        BindingException e = assertThrows(BindingException.class, () -> Targets.parse(
                JsonParser.parseString(HEADER + ",\"gamePackages\":[\"net/minecraft\"],\"obfuscatedClass\":\"[a-z]+\"}"), "targets.json"));
        assertTrue(e.getMessage().startsWith("targets.json: $.gamePackages[0]:"), e.getMessage());
    }
}
