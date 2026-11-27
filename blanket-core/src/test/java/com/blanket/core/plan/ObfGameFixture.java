// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.resolve.GameClasses;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.validate.TargetData;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An invented obfuscated game ({@code net/example/}, not Minecraft data) with the three mapping files of the
 * resolver's routes: a Tiny v2 intermediary (obf to {@code net/example/class_N} ids), a ProGuard file (named to obf)
 * and a TSRG2 table (obf to {@code m_N_}/{@code f_N_}). Two targets use it: an intermediary runtime and an srgmoj-like
 * runtime (classes Mojang-named, members SRG-named), as on Fabric and on Forge 1.17 to 1.20.4.
 *
 * <pre>
 * obf  intermediary         named                         members (obf / intermediary / named / srg)
 * i    net/example/class_9  net/example/gui/Widget        interface
 * a    net/example/class_1  net/example/gui/Screen        implements i; c:I / field_20 / width / f_20_; a(Lb;)V / method_10 / render / m_10_
 * b    net/example/class_2  net/example/gui/Painter
 * d    net/example/class_3  net/example/gui/TitleScreen   extends a
 * h    net/example/class_5  net/example/gui/OptionsScreen extends a
 * e    net/example/class_4  net/example/Game              f()Le; / method_11 / getInstance / m_11_; a(La;)V / method_12 / setScreen / m_12_;
 *                                                         g:La; / field_21 / screen / f_21_; tick()V without any mapping row
 * j    net/example/class_6  net/example/Skin              extends the library class org/lib/Base
 * </pre>
 */
final class ObfGameFixture implements Opcodes {
    static final GameClasses GAME = GameClasses.of(List.of("net/example/"), "[a-z][a-z0-9]{0,3}(\\$.*)?");
    static final TargetSpec INTERMEDIARY = new TargetSpec("fabric", "1.21.1", "intermediary", "fabric", 21, null);
    static final TargetSpec SRGMOJ = new TargetSpec("forge", "1.20.1", "srgmoj", "forge", 17, "1.20.1");
    static final String ANCHOR = "1.21.1";

    final Path client;
    final Path library;
    final Path tiny;
    final Path proguard;
    final Path tsrg;

    ObfGameFixture(Path dir) throws IOException {
        client = jar(dir.resolve("client.jar"), Map.of(
                "i", cls("i", "java/lang/Object", ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, w -> {}),
                "a", cls("a", "java/lang/Object", ACC_PUBLIC, w -> {
                    w.visitField(ACC_PUBLIC, "c", "I", null, null).visitEnd();
                    method(w, ACC_PUBLIC, "<init>", "()V");
                    method(w, ACC_PUBLIC, "a", "(Lb;)V");
                }, "i"),
                "b", cls("b", "java/lang/Object", ACC_PUBLIC, w -> method(w, ACC_PUBLIC, "<init>", "()V")),
                "d", cls("d", "a", ACC_PUBLIC, w -> method(w, ACC_PUBLIC, "<init>", "()V")),
                "h", cls("h", "a", ACC_PUBLIC, w -> method(w, ACC_PUBLIC, "<init>", "()V")),
                "e", cls("e", "java/lang/Object", ACC_PUBLIC, w -> {
                    w.visitField(ACC_PUBLIC, "g", "La;", null, null).visitEnd();
                    method(w, ACC_PUBLIC | ACC_STATIC, "f", "()Le;");
                    method(w, ACC_PUBLIC, "a", "(La;)V");
                    method(w, ACC_PUBLIC, "tick", "()V");
                }),
                "j", cls("j", "org/lib/Base", ACC_PUBLIC, w -> {})));
        library = jar(dir.resolve("lib.jar"), Map.of("org/lib/Base", cls("org/lib/Base", "java/lang/Object", ACC_PUBLIC, w -> {})));
        tiny = write(dir.resolve("intermediary.tiny"),
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ti\tnet/example/class_9",
                "c\ta\tnet/example/class_1",
                "\tf\tI\tc\tfield_20",
                "\tm\t(Lb;)V\ta\tmethod_10",
                "c\tb\tnet/example/class_2",
                "c\td\tnet/example/class_3",
                "c\th\tnet/example/class_5",
                "c\te\tnet/example/class_4",
                "\tm\t()Le;\tf\tmethod_11",
                "\tm\t(La;)V\ta\tmethod_12",
                "\tf\tLa;\tg\tfield_21",
                "c\tj\tnet/example/class_6");
        proguard = write(dir.resolve("proguard.txt"),
                "# fixture: ProGuard, invented names",
                "net.example.gui.Widget -> i:",
                "net.example.gui.Screen -> a:",
                "    int width -> c",
                "    void render(net.example.gui.Painter) -> a",
                "net.example.gui.Painter -> b:",
                "net.example.gui.TitleScreen -> d:",
                "net.example.gui.OptionsScreen -> h:",
                "net.example.Game -> e:",
                "    net.example.gui.Screen screen -> g",
                "    net.example.Game getInstance() -> f",
                "    void setScreen(net.example.gui.Screen) -> a",
                "net.example.Skin -> j:");
        tsrg = write(dir.resolve("joined.tsrg"),
                "tsrg2 obf srg id",
                "i net/example/gui/Widget 9",
                "a net/example/gui/Screen 1",
                "\tc f_20_ 20",
                "\ta (Lb;)V m_10_ 10",
                "b net/example/gui/Painter 2",
                "d net/example/gui/TitleScreen 3",
                "h net/example/gui/OptionsScreen 5",
                "e net/example/Game 4",
                "\tg f_21_ 21",
                "\tf ()Le; m_11_ 11",
                "\ta (La;)V m_12_ 12",
                "j net/example/Skin 6");
    }

    /** Game data over the fixture; {@code libraries} says whether {@code org/lib/} is a library the version lists. */
    TargetData data(boolean mappings, boolean libraries) {
        return new TargetData() {
            @Override
            public Resolver.MappingFiles mappingFiles() {
                return key -> {
                    if (!mappings) throw new IOException("no mapping file in this test: " + key);
                    return switch (key.substring(0, key.indexOf(':'))) {
                        case "int" -> tiny;
                        case "pg" -> proguard;
                        case "tsrg" -> tsrg;
                        default -> throw new IOException("no fixture for " + key);
                    };
                };
            }

            @Override
            public List<Path> classpath(TargetSpec target) {
                return List.of(client);
            }

            @Override
            public boolean hasLoaderJar(TargetSpec target) {
                return false;
            }

            @Override
            public List<Path> libraryJars(TargetSpec target, Set<String> owners) {
                return libraries && owners.stream().anyMatch(o -> o.startsWith("org/lib/")) ? List.of(library) : List.of();
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

    static byte[] cls(String name, String sup, int access, Consumer<ClassWriter> body, String... interfaces) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, access, name, null, sup, interfaces.length == 0 ? null : interfaces);
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }

    /** A method whose body only throws: its signature is what counts. */
    static void method(ClassWriter w, int access, String name, String desc) {
        MethodVisitor m = w.visitMethod(access, name, desc, null, null);
        if ((access & ACC_ABSTRACT) == 0) {
            m.visitCode();
            m.visitInsn(ACONST_NULL);
            m.visitInsn(ATHROW);
            m.visitMaxs(0, 0);
        }
        m.visitEnd();
    }

    static Path jar(Path jar, Map<String, byte[]> classes) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> e : new LinkedHashMap<>(classes).entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return jar;
    }

    private static Path write(Path p, String... lines) throws IOException {
        try (OutputStream o = Files.newOutputStream(p)) {
            o.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return p;
    }
}
