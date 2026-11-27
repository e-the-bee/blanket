// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code loader:} references resolve through a loader jar built here with ASM (no download). */
class LoaderLookupTest {
    private static final String EVENT = "net/loader/event/TickEvent$Post";

    @TempDir
    Path dir;
    private Lookup lookup;

    @BeforeEach
    void setUp() throws IOException {
        Path jar = dir.resolve("loader.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry(EVENT + ".class"));
            z.write(eventClass());
            z.closeEntry();
        }
        // only neoforge:1.21.1 has a loader artifact in this test
        lookup = new Lookup(LookupFixture.targets(), LookupFixture.files(),
                t -> t.id().equals("neoforge:1.21.1") ? List.of(jar) : List.of());
    }

    @AfterEach
    void tearDown() {
        lookup.close();
    }

    private static byte[] eventClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, EVENT, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "count", "I", null, null).visitEnd();
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "getCount", "()I", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ICONST_0);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        MethodVisitor o = cw.visitMethod(Opcodes.ACC_PUBLIC, "getCount", "(I)I", null, null);
        o.visitCode();
        o.visitInsn(Opcodes.ICONST_0);
        o.visitInsn(Opcodes.IRETURN);
        o.visitMaxs(1, 2);
        o.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private Lookup.Row row(String ref, String target) {
        List<Lookup.Result> rs = lookup.lookup(ref, List.of(target));
        assertEquals(1, rs.size(), rs.toString());
        assertEquals(1, rs.get(0).rows().size());
        return rs.get(0).rows().get(0);
    }

    @Test
    void aClassInTheLoaderJarIsFound() {
        Lookup.Row r = row("loader:net.loader.event.TickEvent$Post", "neoforge:1.21.1");
        assertTrue(r.ok(), r.message());
        assertEquals(EVENT, r.owner());
        assertNull(r.name());
        assertTrue(Lookup.table(lookup.lookup("loader:net.loader.event.TickEvent$Post", List.of("neoforge:1.21.1")))
                .contains("neoforge:1.21.1"));
    }

    @Test
    void aMethodAndAFieldWithDescriptorsAreFound() {
        Lookup.Row m = row("loader:net.loader.event.TickEvent$Post#getCount()I", "neoforge:1.21.1");
        assertTrue(m.ok(), m.message());
        assertEquals("getCount", m.name());
        assertEquals("()I", m.desc());
        Lookup.Row f = row("loader:net.loader.event.TickEvent$Post#count:I", "neoforge:1.21.1");
        assertTrue(f.ok(), f.message());
        assertTrue(f.field());
    }

    @Test
    void aBareMemberNameListsItsOverloadsFromTheJar() {
        List<Lookup.Result> rs = lookup.lookup("loader:net.loader.event.TickEvent$Post#getCount", List.of("neoforge:1.21.1"));
        assertEquals(2, rs.size());
        assertEquals(List.of("loader:" + EVENT + "#getCount()I", "loader:" + EVENT + "#getCount(I)I"),
                rs.stream().map(Lookup.Result::ref).toList());
        assertTrue(rs.stream().allMatch(r -> r.rows().get(0).ok()));
    }

    @Test
    void aMisspeltClassIsNotFoundAndNamesTheNearClass() {
        Lookup.Row r = row("loader:net.loader.event.TickEvnt$Post", "neoforge:1.21.1");
        assertFalse(r.ok());
        assertEquals("jar", r.step());
        assertTrue(r.message().contains("net/loader/event/TickEvnt$Post"), r.message());
        assertEquals(List.of("net.loader.event.TickEvent$Post"), r.candidates());
    }

    @Test
    void aMissingMemberIsNotFoundWithCandidates() {
        Lookup.Row r = row("loader:net.loader.event.TickEvent$Post#getCount()J", "neoforge:1.21.1");
        assertFalse(r.ok());
        assertEquals("jar", r.step());
        assertTrue(r.message().contains("method"), r.message());
        assertEquals(List.of(EVENT + "#getCount()I", EVENT + "#getCount(I)I"), r.candidates());
        Lookup.Row bare = lookup.lookup("loader:net.loader.event.TickEvent$Post#nothing", List.of("neoforge:1.21.1")).get(0).rows().get(0);
        assertFalse(bare.ok());
        assertTrue(bare.message().contains("no member named nothing"), bare.message());
    }

    @Test
    void aTargetWithoutALoaderArtifactSaysSo() {
        for (String target : List.of("fabric:1.21.1", "forge:1.8.9")) {
            Lookup.Row r = row("loader:net.loader.event.TickEvent$Post", target);
            assertFalse(r.ok(), target);
            assertEquals("loader-jar", r.step());
            assertTrue(r.message().contains(target + " has no loader artifact"), r.message());
        }
    }

    @Test
    void aLookupBuiltWithoutLoaderJarsAnswersTheSameForEveryTarget() {
        Lookup plain = new Lookup(LookupFixture.targets(), LookupFixture.files());
        Lookup.Row r = plain.lookup("loader:" + EVENT, List.of("neoforge:1.21.1")).get(0).rows().get(0);
        assertEquals("loader-jar", r.step());
    }

    @Test
    void loaderJarsReadFromLoaderArtifactsJson() throws IOException {
        Path file = dir.resolve("loader-artifacts.json");
        Files.writeString(file, "{\"neoforge:1.21.1\": \"21.1.265\"}");
        LoaderJars jars = LoaderJars.fromFile(null, file);
        // a target outside the file, and a target that is not Forge or NeoForge, have no artifact (nothing is fetched)
        assertTrue(jars.jars(com.blanket.core.lookup.Matrix.spec(LookupFixture.targets(), "forge:1.8.9")).isEmpty());
        assertTrue(jars.jars(com.blanket.core.lookup.Matrix.spec(LookupFixture.targets(), "fabric:1.21.1")).isEmpty());
        assertTrue(LoaderJars.fromFile(null, dir.resolve("absent.json")).jars(
                com.blanket.core.lookup.Matrix.spec(LookupFixture.targets(), "neoforge:1.21.1")).isEmpty());
    }
}
