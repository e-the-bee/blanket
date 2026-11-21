// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.V17;

import com.blanket.core.bindings.Lowering;
import com.blanket.core.resolve.ClassIndex;
import com.blanket.core.resolve.Resolver;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/** Every way the C3 check cannot run is reported through the skip callback instead of passing silently. */
class ShapesSkipTest {
    private static final ApiClasses.Method API = new ApiClasses.Method("member", "()I", true);
    private static final String REF = "fake.P#q()I";

    @TempDir
    Path tmp;

    private Path jar() throws IOException {
        Path jar = tmp.resolve("p.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            ClassWriter p = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            p.visit(V17, ACC_PUBLIC, "p", null, "java/lang/Object", null);
            MethodVisitor m = p.visitMethod(ACC_PUBLIC | ACC_STATIC, "q", "()I", null, null);
            m.visitCode();
            m.visitInsn(ICONST_0);
            m.visitInsn(IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            p.visitEnd();
            z.putNextEntry(new ZipEntry("p.class"));
            z.write(p.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    private static Lowering invoke(String ref) {
        return Lowering.parse(JsonParser.parseString("{\"invoke\": \"" + ref + "\"}"), "t", "$");
    }

    private List<String> skips(Lowering l, Function<String, Resolver.Resolved> resolved) throws IOException {
        List<String> out = new ArrayList<>();
        try (ClassIndex index = ClassIndex.open(List.of(jar()), false)) {
            assertNull(new Shapes(index, Map.of()).check(API, "com/blanket/test/Api", l, resolved, out::add));
        }
        return out;
    }

    private static Resolver.Resolved at(String owner, String name) {
        return new Resolver.Resolved(REF, owner, name, "()I", false);
    }

    @Test
    void ownerNotInTheIndexIsASkip() throws IOException {
        List<String> s = skips(invoke(REF), t -> at("zz", "q"));
        assertEquals(1, s.size());
        assertTrue(s.get(0).contains("class zz is not in the index"), s.get(0));
    }

    @Test
    void memberMissingFromTheIndexIsASkip() throws IOException {
        List<String> s = skips(invoke(REF), t -> at("p", "nope"));
        assertEquals(1, s.size());
        assertTrue(s.get(0).contains("p.nope()I is not in the index"), s.get(0));
    }

    @Test
    void unresolvedReferenceIsASkip() throws IOException {
        List<String> s = skips(invoke(REF), t -> null);
        assertEquals(1, s.size());
        assertTrue(s.get(0).contains("did not resolve"), s.get(0));
    }

    @Test
    void chainWithAnUnmodelledStepIsASkip() throws Exception {
        var make = Lowering.class.getDeclaredConstructor(Lowering.Kind.class, String.class, com.google.gson.JsonPrimitive.class, List.class);
        make.setAccessible(true);
        Lowering chain = make.newInstance(Lowering.Kind.CHAIN, null, null,
                List.of(invoke(REF), make.newInstance(Lowering.Kind.NEW, REF, null, null)));
        List<String> s = skips(chain, t -> at("p", "q"));
        assertEquals(1, s.size());
        assertTrue(s.get(0).contains("chain step 2") && s.get(0).contains("does not model"), s.get(0));
    }

    @Test
    void aCheckThatRunsReportsNoSkip() throws IOException {
        assertEquals(List.of(), skips(invoke(REF), t -> at("p", "q")));
    }
}
