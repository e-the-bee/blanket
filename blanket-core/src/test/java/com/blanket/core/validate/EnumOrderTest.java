// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C5 on an invented game enum {@code q} (intermediary {@code net/minecraft/class_7}, fields {@code a}/{@code field_1}
 * and {@code b}/{@code field_2}; not Minecraft data) aliased by the fixture API enum {@code FxSide { LEFT, RIGHT }}.
 * Every constant of the game enum is built with a nested {@code new Object()} argument, as real enums pass vectors.
 */
class EnumOrderTest implements Opcodes {
    static final String SIDE = "com.blanket.core.validate.fixture.FxSide";
    static final String CLASS_7 = "intermediary:net/minecraft/class_7";

    @TempDir
    Path tmp;

    /** One enum constant of the game: its field, the name its constructor gets, and its ordinal. */
    record GameConstant(String field, String name, int ordinal) {}

    /** The client jar: enum {@code q} whose {@code <clinit>} builds {@code constants} in order; fields declared in reverse. */
    static Path clientJar(Path dir, List<GameConstant> constants) throws IOException {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_ENUM | ACC_SUPER, "q", null, "java/lang/Enum", null);
        for (int i = constants.size() - 1; i >= 0; i--) {
            w.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, constants.get(i).field(), "Lq;", null, null).visitEnd();
        }
        MethodVisitor init = w.visitMethod(ACC_PRIVATE, "<init>", "(Ljava/lang/String;ILjava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ILOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor clinit = w.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (GameConstant c : constants) {
            clinit.visitTypeInsn(NEW, "q");
            clinit.visitInsn(DUP);
            clinit.visitLdcInsn(c.name());
            clinit.visitIntInsn(BIPUSH, c.ordinal());
            clinit.visitTypeInsn(NEW, "java/lang/Object");
            clinit.visitInsn(DUP);
            clinit.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            clinit.visitMethodInsn(INVOKESPECIAL, "q", "<init>", "(Ljava/lang/String;ILjava/lang/Object;)V", false);
            clinit.visitFieldInsn(PUTSTATIC, "q", c.field(), "Lq;");
        }
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        w.visitEnd();
        Path jar = Files.createTempFile(dir, "client", ".jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("q.class"));
            z.write(w.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    /** The intermediary file of the game: {@code q} is {@code class_7}, {@code a}/{@code b} are {@code field_1}/{@code field_2}. */
    static Path tiny(Path dir) throws IOException {
        Path p = dir.resolve("int.tiny");
        try (OutputStream o = Files.newOutputStream(p)) {
            o.write(String.join("\n", "tiny\t2\t0\tofficial\tintermediary", "c\tq\tnet/minecraft/class_7",
                    "\tf\tLq;\ta\tfield_1", "\tf\tLq;\tb\tfield_2", "").getBytes(StandardCharsets.UTF_8));
        }
        return p;
    }

    static TargetData data(Path tiny, Path jar) {
        return new TargetData() {
            @Override
            public Resolver.MappingFiles mappingFiles() {
                return key -> {
                    if (key.startsWith("int:")) return tiny;
                    throw new IOException("no fixture mapping file " + key);
                };
            }

            @Override
            public List<Path> classpath(TargetSpec target) {
                return List.of(jar);
            }

            @Override
            public boolean hasLoaderJar(TargetSpec target) {
                return false;
            }

            @Override
            public Optional<Path> mojangMappings(String mc) {
                return Optional.empty();
            }

            @Override
            public Optional<String> mcpLicense() {
                return Optional.empty();
            }
        };
    }

    /** The type file of FxSide with the given members (JSON object body). */
    static String side(String members) {
        return """
                { "schema": 1, "api": "%s",
                  "representation": [ { "when": {}, "alias": "%s" } ],
                  "members": { %s } }""".formatted(SIDE, CLASS_7, members);
    }

    static String constant(String name, String field) {
        return "\"" + name + "\": [ { \"when\": {}, \"lower\": { \"get\": \"" + CLASS_7 + "#" + field + ":Lnet/minecraft/class_7;\" } } ]";
    }

    /** Runs the validator on fabric:1.21.1 with the given type files. */
    static Report run(Path tmp, Map<String, String> files, List<GameConstant> game, ApiClasses api) throws IOException {
        Path dir = Files.createTempDirectory(tmp, "bindings");
        Files.writeString(dir.resolve("targets.json"), ValidatorTest.targets("fabric:1.21.1"), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        return new Validator(BindingSet.load(dir), data(tiny(tmp), clientJar(tmp, game)), api).run();
    }

    private static final List<GameConstant> API_ORDER = List.of(new GameConstant("a", "LEFT", 0), new GameConstant("b", "RIGHT", 1));

    private Report run(List<GameConstant> game, String members) throws IOException {
        return run(tmp, Map.of("types/FxSide.json", side(members)), game, ApiClasses.of(EnumOrderTest.class.getClassLoader()));
    }

    @Test
    void ordinalsInTheApiOrderAreGreenWhateverTheFieldOrder() throws IOException {
        // the fields are declared b, a: only <clinit> gives a ordinal 0
        Report r = run(API_ORDER, constant("LEFT", "field_1") + ", " + constant("RIGHT", "field_2"));
        assertTrue(r.ok(), r.format());
    }

    @Test
    void aSwappedGameEnumIsOrder() throws IOException {
        // the game declares RIGHT first: a = RIGHT (0), b = LEFT (1); the rules bind each constant to its own field
        List<GameConstant> swapped = List.of(new GameConstant("a", "RIGHT", 0), new GameConstant("b", "LEFT", 1));
        Report r = run(swapped, constant("LEFT", "field_2") + ", " + constant("RIGHT", "field_1"));
        Diagnostic d = ValidatorTest.only(r);
        assertEquals(ErrorCode.ORDER, d.code());
        assertEquals("fabric:1.21.1", d.target());
        assertEquals("FxSide", d.member());
        assertTrue(d.detail().startsWith("C5 enum order: "), d.detail());
        assertTrue(d.detail().contains("LEFT is 0 in the API but 1 on fabric:1.21.1 (q.b)"), d.detail());
        assertTrue(d.detail().contains("RIGHT is 1 in the API but 0 on fabric:1.21.1 (q.a)"), d.detail());
    }

    @Test
    void ordinalsComeFromTheConstructorArgumentNotTheFieldOrder() throws IOException {
        Path jar = clientJar(tmp, List.of(new GameConstant("x", "X", 0), new GameConstant("y", "Y", 7)));
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
            Map<String, Integer> ordinals = EnumOrder.ordinals(z.getInputStream(z.getEntry("q.class")).readAllBytes());
            assertEquals(Map.of("x:Lq;", 0, "y:Lq;", 7), ordinals);
        }
    }
}
