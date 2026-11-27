// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * compileAdapters on a fixture set: javac {@code --release 8} gives major 52; a {@code @Stubs} class gets its own run
 * against its own stub jar; game classes newer than the running javac and game records are readable.
 *
 * <p>Fixture stub jars: {@code old.jar} holds {@code net/minecraft/Thing} (class file major 69, Java 25, newer than
 * the JDK 21 the tests run on) with {@code int value()}, and the record {@code net/minecraft/Event} with
 * {@code int key()}; {@code new.jar} holds {@code net/minecraft/NewThing} with {@code int size()}, which old.jar lacks.
 */
class CompileAdaptersTest implements Opcodes {
    @TempDir
    Path dir;

    private static byte[] gameClass(int version, String name, String superName, String method) {
        ClassWriter w = new ClassWriter(0);
        w.visit(version, ACC_PUBLIC | ACC_SUPER | (superName.equals("java/lang/Record") ? ACC_FINAL : 0), name, null, superName, null);
        w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).visitEnd();
        w.visitMethod(ACC_PUBLIC, method, "()I", null, null).visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private Path jar(String name, Map<String, byte[]> classes) throws IOException {
        Path jar = dir.resolve(name);
        StubJars.writeJar(jar, classes);
        return jar;
    }

    private Path source(Path root, String file, String text) throws IOException {
        Path p = root.resolve("com/blanket/adapters").resolve(file);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    /** The real compile-only annotation, compiled as compileAdapters does. */
    private Path stubsAnnotation() throws IOException {
        Path out = dir.resolve("common");
        CompileAdapters.compileSet(Path.of("..", "blanket-bindings", "adapters", "common", "src"), List.of(),
                v -> new CompileAdapters.EraFiles(null, List.of()), dir.resolve("work"), out);
        assertTrue(Files.isRegularFile(out.resolve("com/blanket/adapters/Stubs.class")));
        return out;
    }

    private static int major(Path classFile) throws IOException {
        byte[] b = Files.readAllBytes(classFile);
        return ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
    }

    private CompileAdapters.EraLookup eras() throws IOException {
        Map<String, byte[]> old = new TreeMap<>();
        old.put("net/minecraft/Thing.class", gameClass(V25, "net/minecraft/Thing", "java/lang/Object", "value"));
        old.put("net/minecraft/Event.class", gameClass(V21, "net/minecraft/Event", "java/lang/Record", "key"));
        Path oldJar = jar("old.jar", old);
        Path newJar = jar("new.jar", Map.of("net/minecraft/NewThing.class", gameClass(V21, "net/minecraft/NewThing", "java/lang/Object", "size")));
        return v -> {
            if (v == null) return new CompileAdapters.EraFiles(oldJar, List.of());
            assertEquals("2.0", v);
            return new CompileAdapters.EraFiles(newJar, List.of());
        };
    }

    @Test
    void aSetCompilesToJava8AndAStubsClassAgainstItsOwnStubJar() throws Exception {
        Path src = dir.resolve("src");
        source(src, "Uses.java", "package com.blanket.adapters;\n"
                + "import net.minecraft.Event;\nimport net.minecraft.Thing;\n"
                + "public final class Uses {\n"
                + "    public static int value(Thing t) { return t.value(); }\n"
                + "    public static int key(Event e) { return e.key(); }\n"
                + "    public static Runnable later(Thing t) { return () -> t.value(); }\n"
                + "    public static int one() { return 1; }\n"
                + "}\n");
        source(src, "UsesNew.java", "package com.blanket.adapters;\n"
                + "import net.minecraft.NewThing;\n"
                + "@Stubs(\"2.0\")\n"
                + "public final class UsesNew {\n"
                + "    public static int size(NewThing t) { return t.size() + Uses.one(); }\n"
                + "}\n");
        Path out = dir.resolve("out");
        int n = CompileAdapters.compileSet(src, List.of(stubsAnnotation()), eras(), dir.resolve("work"), out);
        assertEquals(2, n);
        assertEquals(52, major(out.resolve("com/blanket/adapters/Uses.class")));
        assertEquals(52, major(out.resolve("com/blanket/adapters/UsesNew.class")));
        assertEquals("2.0", RemapAdapters.stubsVersion(Files.readAllBytes(out.resolve("com/blanket/adapters/UsesNew.class"))));
    }

    @Test
    void aClassWithoutStubsDoesNotSeeTheOtherStubJar() throws Exception {
        Path src = dir.resolve("src");
        source(src, "Wrong.java", "package com.blanket.adapters;\n"
                + "public final class Wrong {\n"
                + "    public static int size(net.minecraft.NewThing t) { return t.size(); }\n"
                + "}\n");
        CompileAdapters.JavacException e = assertThrows(CompileAdapters.JavacException.class,
                () -> CompileAdapters.compileSet(src, List.of(stubsAnnotation()), eras(), dir.resolve("work"), dir.resolve("out")));
        assertTrue(e.getMessage().contains("NewThing"), e.getMessage());
    }

    @Test
    void java9ApisAreRejectedByRelease8() throws Exception {
        Path src = dir.resolve("src");
        source(src, "Modern.java", "package com.blanket.adapters;\n"
                + "public final class Modern {\n"
                + "    public static java.util.List<String> list() { return java.util.List.of(\"a\"); }\n"
                + "}\n");
        CompileAdapters.JavacException e = assertThrows(CompileAdapters.JavacException.class,
                () -> CompileAdapters.compileSet(src, List.of(), eras(), dir.resolve("work"), dir.resolve("out")));
        assertTrue(e.getMessage().contains("of("), e.getMessage());
    }

    @Test
    void stubsVersionIsFoundInTheSource() throws Exception {
        Path src = dir.resolve("src");
        Path a = source(src, "A.java", "package com.blanket.adapters;\n@Stubs( value = \"1.20.1\" )\npublic final class A {}\n");
        Path b = source(src, "B.java", "package com.blanket.adapters;\npublic final class B {}\n");
        assertEquals("1.20.1", CompileAdapters.stubsVersion(a));
        assertEquals(null, CompileAdapters.stubsVersion(b));
        Map<String, List<Path>> units = CompileAdapters.units(src);
        assertEquals(List.of(b), units.get(null));
        assertEquals(List.of(a), units.get("1.20.1"));
    }
}
