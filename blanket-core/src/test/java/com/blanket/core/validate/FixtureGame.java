// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.lookup.LookupFixture;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The invented game of the validation tests, as a client jar built with ASM. Its obfuscated names are the ones of
 * {@code src/test/resources/lookup/pg-1.21.1.txt}: Client {@code a} (field {@code b}, {@code c()V} which calls
 * {@code d()} twice, static {@code d()La;}), Player {@code e} extends Base {@code k} (field {@code f}, {@code g()D},
 * {@code h()I}, {@code i(La;I)V}), Base {@code k} ({@code m()D}).
 */
final class FixtureGame implements Opcodes {
    private FixtureGame() {}

    /** The client jar. {@code withTick} and {@code withArmor} switch {@code a.c()V} and {@code e.h()I} off. */
    static Path clientJar(Path dir, boolean withTick, boolean withArmor) throws IOException {
        Path jar = dir.resolve("client-" + withTick + "-" + withArmor + ".jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            add(z, "a", client(withTick));
            add(z, "e", player(withArmor));
            add(z, "k", base());
        }
        return jar;
    }

    /**
     * A loader jar holding {@code net/example/RealEvent} and {@code com/loader/KeyEvent}. The latter is compiled against
     * the game's runtime (Mojang) names like a real loader: static {@code register(Lnet/example/Player;)V} and
     * {@code registerOther(Lnet/example/Client;)V}.
     */
    static Path loaderJar(Path dir) throws IOException {
        Path jar = dir.resolve("loader.jar");
        ClassWriter real = new ClassWriter(0);
        real.visit(V17, ACC_PUBLIC, "net/example/RealEvent", null, "java/lang/Object", null);
        real.visitEnd();
        ClassWriter keys = new ClassWriter(0);
        keys.visit(V17, ACC_PUBLIC, "com/loader/KeyEvent", null, "java/lang/Object", null);
        for (String[] m : new String[][] {{"register", "(Lnet/example/Player;)V"}, {"registerOther", "(Lnet/example/Client;)V"}}) {
            MethodVisitor v = keys.visitMethod(ACC_PUBLIC | ACC_STATIC, m[0], m[1], null, null);
            v.visitCode();
            v.visitInsn(RETURN);
            v.visitMaxs(0, 0);
            v.visitEnd();
        }
        keys.visitEnd();
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            add(z, "net/example/RealEvent", real);
            add(z, "com/loader/KeyEvent", keys);
        }
        return jar;
    }

    private static byte[] client(boolean withTick) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, "a", null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC, "b", "Le;", null, null).visitEnd();
        MethodVisitor d = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "d", "()La;", null, null);
        d.visitCode();
        d.visitInsn(ACONST_NULL);
        d.visitInsn(ARETURN);
        d.visitMaxs(0, 0);
        d.visitEnd();
        if (withTick) {
            MethodVisitor c = w.visitMethod(ACC_PUBLIC, "c", "()V", null, null);
            c.visitCode();
            for (int i = 0; i < 2; i++) {
                c.visitMethodInsn(INVOKESTATIC, "a", "d", "()La;", false);
                c.visitInsn(POP);
            }
            c.visitInsn(RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] player(boolean withArmor) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, "e", null, "k", null);
        w.visitField(ACC_PUBLIC, "f", "D", null, null).visitEnd();
        returning(w, "g", "()D", DCONST_0, DRETURN);
        if (withArmor) returning(w, "h", "()I", ICONST_0, IRETURN);
        MethodVisitor i = w.visitMethod(ACC_PUBLIC, "i", "(La;I)V", null, null);
        i.visitCode();
        i.visitInsn(RETURN);
        i.visitMaxs(0, 0);
        i.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] base() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, "k", null, "java/lang/Object", null);
        returning(w, "m", "()D", DCONST_0, DRETURN);
        w.visitEnd();
        return w.toByteArray();
    }

    private static void returning(ClassWriter w, String name, String desc, int load, int ret) {
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        m.visitInsn(load);
        m.visitInsn(ret);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void add(ZipOutputStream z, String name, ClassWriter w) throws IOException {
        add(z, name, w.toByteArray());
    }

    private static void add(ZipOutputStream z, String name, byte[] bytes) throws IOException {
        z.putNextEntry(new ZipEntry(name + ".class"));
        z.write(bytes);
        z.closeEntry();
    }

    // ---------------------------------------------------------------------------------------- data

    /** Fixture {@link TargetData}: the lookup fixture's mapping files and the jars above. */
    static final class Data implements TargetData {
        final Path clientJar;
        Path loaderJar;
        Path mojangMappings;
        String mcpLicense;

        Data(Path clientJar) {
            this.clientJar = clientJar;
        }

        @Override
        public Resolver.MappingFiles mappingFiles() {
            return LookupFixture.files();
        }

        @Override
        public List<Path> classpath(TargetSpec target) {
            return loaderJar == null ? List.of(clientJar) : List.of(clientJar, loaderJar);
        }

        @Override
        public boolean hasLoaderJar(TargetSpec target) {
            return loaderJar != null;
        }

        @Override
        public Optional<Path> mojangMappings(String mc) {
            return Optional.ofNullable(mojangMappings);
        }

        @Override
        public Optional<String> mcpLicense() {
            return Optional.ofNullable(mcpLicense);
        }
    }

    /** A mappings file whose header is {@code headerText} (one {@code #} line), for C8. */
    static Path mappingsWithHeader(Path dir, String name, String headerText) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream o = Files.newOutputStream(p)) {
            o.write(("# " + headerText + "\nnet.example.Client -> a:\n").getBytes(StandardCharsets.UTF_8));
        }
        return p;
    }
}
