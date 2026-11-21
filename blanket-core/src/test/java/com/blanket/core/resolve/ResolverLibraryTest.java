// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.resolve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Members of classes outside the game's obfuscated set (libraries such as JOML): the name is the same in every
 * namespace, the class must exist in the class index, and Minecraft classes in the descriptor are still translated.
 * Uses a one-class ProGuard file and a one-class jar built in the test, so no download is needed.
 */
class ResolverLibraryTest {

    private static final String MATRIX_GET = "org.joml.Matrix4f#get([F)[F";
    private static final String MATRIX_SET_MC = "org.joml.Matrix4f#set(Lnet/minecraft/client/Minecraft;)V";
    private static final TargetSpec TARGET = new TargetSpec("fabric", "1.99", "official", "fabric", 21, null);

    @TempDir
    Path tmp;

    private Resolver.MappingFiles files() throws IOException {
        Path pg = tmp.resolve("pg.txt");
        Files.writeString(pg, "net.minecraft.client.Minecraft -> fgo:\n");
        return key -> pg;
    }

    private ClassIndex libraryJar(String method, String desc) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "org/joml/Matrix4f", null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC, method, desc, null, null).visitEnd();
        cw.visitEnd();
        Path jar = tmp.resolve("joml.jar");
        try (OutputStream o = Files.newOutputStream(jar); ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("org/joml/Matrix4f.class"));
            z.write(cw.toByteArray());
            z.closeEntry();
        }
        return ClassIndex.open(List.of(jar), false);
    }

    private Map<String, Resolver.Resolved> resolve(ClassIndex index, List<Resolver.Problem> problems, String... texts) throws IOException {
        List<Ref> refs = new ArrayList<>();
        for (String t : texts) refs.add(Ref.parse(t, "test", "$"));
        return new Resolver(TARGET, "1.99", files(), index).resolve(refs, problems);
    }

    @Test
    void libraryMemberPresentInTheIndexResolvesUnchanged() throws IOException {
        List<Resolver.Problem> problems = new ArrayList<>();
        try (ClassIndex index = libraryJar("get", "([F)[F")) {
            Resolver.Resolved r = resolve(index, problems, MATRIX_GET).get(MATRIX_GET);
            assertTrue(problems.isEmpty(), problems.toString());
            assertEquals("org/joml/Matrix4f", r.owner());
            assertEquals("get", r.name());
            assertEquals("([F)[F", r.desc());
        }
    }

    @Test
    void libraryMemberAbsentFromTheIndexIsUnresolvedAtTheIndexStep() throws IOException {
        List<Resolver.Problem> problems = new ArrayList<>();
        try (ClassIndex index = libraryJar("other", "()V")) {
            Map<String, Resolver.Resolved> out = resolve(index, problems, MATRIX_GET);
            assertTrue(out.isEmpty());
            assertEquals(1, problems.size());
            Resolver.Problem p = problems.get(0);
            assertEquals("UNRESOLVED", p.code());
            assertEquals(MATRIX_GET, p.ref());
            assertEquals("index|mojang|official", p.step());
            assertTrue(p.message().contains("org/joml/Matrix4f.get([F)[F"), p.message());
        }
    }

    @Test
    void libraryMethodTranslatesMinecraftClassesInItsDescriptor() throws IOException {
        List<Resolver.Problem> problems = new ArrayList<>();
        try (ClassIndex index = libraryJar("set", "(Lfgo;)V")) {
            Resolver.Resolved r = resolve(index, problems, MATRIX_SET_MC).get(MATRIX_SET_MC);
            assertTrue(problems.isEmpty(), problems.toString());
            assertEquals("org/joml/Matrix4f", r.owner());
            assertEquals("set", r.name());
            assertEquals("(Lfgo;)V", r.desc());   // runtime namespace "official" is the obfuscated name on this target
        }
    }
}
