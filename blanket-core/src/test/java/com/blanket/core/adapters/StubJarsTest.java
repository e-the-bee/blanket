// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blanket.core.bindings.BindingException;
import com.blanket.core.mapping.Urls;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** The stub jar of a fixture client jar: no code, names remapped through a chain of mapping files, structure kept. */
class StubJarsTest implements Opcodes {
    @TempDir
    Path dir;

    /**
     * Fixture game in "obf" names: {@code a} (class with a generic field, a method with code, an inner class {@code a$b}
     * and a field of the enum {@code c}), {@code c} (enum), {@code a$b}.
     */
    private Path clientJar() throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();

        ClassWriter a = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        a.visit(V21, ACC_PUBLIC | ACC_SUPER, "a", null, "java/lang/Object", null);
        a.visitInnerClass("a$b", "a", "b", ACC_PUBLIC | ACC_STATIC);
        FieldVisitor f = a.visitField(ACC_PUBLIC, "f", "Ljava/util/List;", "Ljava/util/List<La$b;>;", null);
        f.visitEnd();
        a.visitField(ACC_PUBLIC, "e", "Lc;", null, null).visitEnd();
        MethodVisitor m = a.visitMethod(ACC_PUBLIC, "m", "(Lc;)La$b;", null, null);
        m.visitCode();
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        a.visitEnd();
        classes.put("a.class", a.toByteArray());

        ClassWriter b = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        b.visit(V21, ACC_PUBLIC | ACC_SUPER, "a$b", null, "java/lang/Object", null);
        b.visitInnerClass("a$b", "a", "b", ACC_PUBLIC | ACC_STATIC);
        b.visitEnd();
        classes.put("a$b.class", b.toByteArray());

        ClassWriter c = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        c.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_ENUM, "c", "Ljava/lang/Enum<Lc;>;", "java/lang/Enum", null);
        c.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, "x", "Lc;", null, null).visitEnd();
        MethodVisitor clinit = c.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        c.visitEnd();
        classes.put("c.class", c.toByteArray());

        classes.put("assets/lang.json", "{}".getBytes(StandardCharsets.UTF_8));
        Path jar = dir.resolve("client.jar");
        StubJars.writeJar(jar, classes);
        return jar;
    }

    /** obf to intermediary (first stage) and intermediary to named (second stage), as for Legacy Yarn. */
    private List<StubJars.Table> twoStages() throws IOException {
        Path lf = dir.resolve("lf.tiny");
        Files.writeString(lf, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tLjava/util/List;\tf\tfield_10",
                "\tf\tLc;\te\tfield_11",
                "\tm\t(Lc;)La$b;\tm\tmethod_12",
                "c\ta$b\tnet/minecraft/class_1$class_2",
                "c\tc\tnet/minecraft/class_3",
                "\tf\tLc;\tx\tfield_13",
                ""));
        Path yarn = dir.resolve("yarn.tiny");
        Files.writeString(yarn, String.join("\n",
                "tiny\t2\t0\tintermediary\tnamed",
                "c\tnet/minecraft/class_1\tfx/Client",
                "\tf\tLjava/util/List;\tfield_10\tentries",
                "\tf\tLnet/minecraft/class_3;\tfield_11\tmode",
                "\tm\t(Lnet/minecraft/class_3;)Lnet/minecraft/class_1$class_2;\tmethod_12\tpick",
                "c\tnet/minecraft/class_1$class_2\tfx/Client$Entry",
                "c\tnet/minecraft/class_3\tfx/Mode",
                "\tf\tLnet/minecraft/class_3;\tfield_13\tFIRST",
                ""));
        return List.of(StubJars.Table.read(lf, 0, 1), StubJars.Table.read(yarn, 0, 1));
    }

    private static Map<String, byte[]> entries(Path jar) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                out.put(e.getName(), zip.getInputStream(e).readAllBytes());
            }
        }
        return out;
    }

    /** What a class file declares, read without skipping code. */
    private record Shape(String name, String signature, int access, List<String> fields, List<String> methods,
                         List<String> inner, int codeBlocks) {}

    private static Shape shape(byte[] classFile) {
        List<String> fields = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        List<String> inner = new ArrayList<>();
        int[] code = new int[1];
        String[] head = new String[3];
        int[] access = new int[1];
        new ClassReader(classFile).accept(new ClassVisitor(ASM9) {
            @Override
            public void visit(int version, int acc, String name, String signature, String superName, String[] interfaces) {
                head[0] = name;
                head[1] = signature;
                access[0] = acc;
            }

            @Override
            public void visitInnerClass(String name, String outer, String innerName, int acc) {
                inner.add(name + "|" + outer + "|" + innerName);
            }

            @Override
            public FieldVisitor visitField(int acc, String name, String desc, String signature, Object value) {
                fields.add(name + ":" + desc + (signature == null ? "" : "<" + signature + ">"));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String signature, String[] exceptions) {
                methods.add(name + desc);
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitCode() {
                        code[0]++;
                    }
                };
            }
        }, 0);
        return new Shape(head[0], head[1], access[0], fields, methods, inner, code[0]);
    }

    @Test
    void stubsHaveNoCodeAndNamesAreRemappedThroughBothStages() throws Exception {
        Path out = dir.resolve("out/stub.jar");
        int n = StubJars.write(clientJar(), twoStages(), out);
        assertEquals(3, n);

        Map<String, byte[]> e = entries(out);
        assertEquals(List.of("fx/Client$Entry.class", "fx/Client.class", "fx/Mode.class").stream().sorted().toList(), new ArrayList<>(e.keySet()),
                "only classes, all in authoring names; the old names are gone");

        Shape client = shape(e.get("fx/Client.class"));
        assertEquals("fx/Client", client.name());
        assertEquals(0, client.codeBlocks(), "SKIP_CODE: no method keeps a Code attribute");
        assertEquals(List.of("pick(Lfx/Mode;)Lfx/Client$Entry;"), client.methods(), "method name and descriptor remapped");
        assertTrue(client.fields().contains("entries:Ljava/util/List;<Ljava/util/List<Lfx/Client$Entry;>;>"),
                "the generic Signature is remapped: " + client.fields());
        assertTrue(client.fields().contains("mode:Lfx/Mode;"), client.fields().toString());
    }

    @Test
    void innerClassesAndEnumFlagsAreKept() throws Exception {
        Path out = dir.resolve("stub.jar");
        StubJars.write(clientJar(), twoStages(), out);
        Map<String, byte[]> e = entries(out);

        Shape client = shape(e.get("fx/Client.class"));
        assertEquals(List.of("fx/Client$Entry|fx/Client|Entry"), client.inner(), "InnerClasses names and inner name survive");
        assertEquals(List.of("fx/Client$Entry|fx/Client|Entry"), shape(e.get("fx/Client$Entry.class")).inner());

        Shape mode = shape(e.get("fx/Mode.class"));
        assertTrue((mode.access() & Opcodes.ACC_ENUM) != 0, "ACC_ENUM kept");
        assertEquals("Ljava/lang/Enum<Lfx/Mode;>;", mode.signature(), "enum class Signature remapped");
        assertEquals(List.of("FIRST:Lfx/Mode;"), mode.fields());
        assertEquals(List.of("<clinit>()V"), mode.methods(), "the method stays, without its Code");
        assertEquals(0, mode.codeBlocks(), "the enum's static initializer keeps no code");
    }

    @Test
    void noStagesKeepsTheNamesAndStillDropsCode() throws Exception {
        Path out = dir.resolve("official.jar");
        StubJars.write(clientJar(), List.of(), out);
        Map<String, byte[]> e = entries(out);
        assertEquals(List.of("a$b.class", "a.class", "c.class").stream().sorted().toList(), new ArrayList<>(e.keySet()));
        assertEquals(0, shape(e.get("a.class")).codeBlocks());
    }

    @Test
    void sameInputsGiveSameBytes() throws Exception {
        Path jar = clientJar();
        Path one = dir.resolve("one.jar");
        Path two = dir.resolve("two.jar");
        StubJars.write(jar, twoStages(), one);
        StubJars.write(jar, twoStages(), two);
        assertEquals(Files.mismatch(one, two), -1L, "stub jars are reproducible");
    }

    @Test
    void proguardStageMapsObfToMojangNamesAndTranslatesDescriptors() throws Exception {
        Path proguard = Path.of("src/test/resources/mapping/proguard.txt");
        StubJars.Table table = StubJars.Table.read(proguard, 1, 0);
        assertEquals("net/example/Client", table.map("a"));
        assertEquals("net/example/Player", table.map("d"));
        assertEquals("player", table.mapFieldName("a", "b", "Ld;"));
        assertEquals("tick", table.mapMethodName("a", "c", "()V"));
        assertEquals("jump", table.mapMethodName("d", "g", "(La;I)V"), "the ProGuard descriptor is in Mojang names; the lookup is in obf names");
        assertEquals("unused", table.mapMethodName("a", "z", "()V"));
    }

    @Test
    void unmappedNamesStayAsTheyAre() throws Exception {
        StubJars.Table table = twoStages().get(0);
        assertEquals("java/util/List", table.map("java/util/List"));
        assertEquals("zzz", table.mapMethodName("a", "zzz", "()V"));
        assertEquals("f", table.mapFieldName("a", "f", "Ljava/lang/String;"), "a field with a different descriptor has no row");
    }

    @Test
    void legacyYarnUrlIsTheV2JarOfTheLegacyFabricMaven() {
        assertEquals("https://maven.legacyfabric.net/net/legacyfabric/yarn/1.8.9+build.604/yarn-1.8.9+build.604-v2.jar",
                Urls.legacyYarn("net.legacyfabric:yarn:1.8.9+build.604"));
        assertThrows(IllegalArgumentException.class, () -> Urls.legacyYarn("net.legacyfabric:yarn"));
    }

    @Test
    void setFilesAreReadStrictly() throws Exception {
        Path file = dir.resolve("adapter-set.json");
        Files.writeString(file, "{\"schema\":1,\"name\":\"legacy\",\"stubs\":\"1.8.9\",\"authoring\":\"yarn-legacy\","
                + "\"authoringMappings\":\"g:a:1\",\"shipping\":\"lf\",\"covers\":{\"mc\":\"1.8.9\"}}");
        StubJars.AdapterSet set = StubJars.readSet(file);
        assertEquals(new StubJars.AdapterSet("legacy", "1.8.9", "yarn-legacy", "g:a:1", "lf"), set);

        Files.writeString(file, "{\"schema\":1,\"name\":\"legacy\",\"stubs\":\"1.8.9\",\"authoring\":\"yarn-legacy\",\"shipping\":\"lf\"}");
        BindingException missing = assertThrows(BindingException.class, () -> StubJars.readSet(file));
        assertTrue(missing.getMessage().contains("$.authoringMappings"), missing.getMessage());

        Files.writeString(file, "{\"schema\":1,\"name\":\"x\",\"stubs\":\"1\",\"authoring\":\"mojang\",\"shipping\":\"intermediary\",\"stubz\":1}");
        BindingException unknown = assertThrows(BindingException.class, () -> StubJars.readSet(file));
        assertTrue(unknown.getMessage().contains("$.stubz"), unknown.getMessage());

        Files.writeString(file, "{\"schema\":1,\"name\":\"x\",\"stubs\":\"1\",\"authoring\":\"srg\",\"shipping\":\"lf\"}");
        BindingException authoring = assertThrows(BindingException.class, () -> StubJars.readSet(file));
        assertTrue(authoring.getMessage().contains("$.authoring"), authoring.getMessage());
    }

    @Test
    void theRealSetsAreReadAndKeepAdapterSetOrder() throws Exception {
        List<StubJars.AdapterSet> sets = StubJars.readSets(Path.of("..", "blanket-bindings"));
        assertEquals(List.of("legacy", "modern", "unobf"), sets.stream().map(StubJars.AdapterSet::name).toList());
        assertEquals("net.legacyfabric:yarn:1.8.9+build.604", sets.get(0).authoringMappings());
        assertNull(sets.get(1).authoringMappings());
        assertNotNull(sets.get(2).stubs());
    }

    @Test
    void aBadCommandLineExitsTwoAndNamesTheProblem() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StubJarsMain.run(new String[] {"--offline"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertEquals(2, code);
        assertTrue(err.toString().contains("--classes is required"), err.toString());
        assertFalse(err.toString().isEmpty());
    }
}
