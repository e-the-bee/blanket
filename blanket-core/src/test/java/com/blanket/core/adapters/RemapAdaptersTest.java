// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blanket.core.bindings.BindingFiles;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * remapAdapters on a fixture set: authoring ("named") names become shipping ("intermediary") names through a chain of
 * two mapping files, the package moves to the set's, overriding declarations are renamed with the methods they
 * override, and adapter-refs.json lists the references whose bytecode owner is not the declaring class.
 *
 * <p>Fixture game, authoring names: interface {@code net/minecraft/Listener} declares {@code click(I)Z};
 * {@code net/minecraft/Screen implements Listener} overrides {@code click(I)Z} and declares {@code init()V} and the
 * field {@code width:I}; {@code net/minecraft/Panel extends Screen} declares nothing. The intermediary file has the
 * {@code click} row only on {@code Listener}, as Fabric intermediary does for an override family.
 */
class RemapAdaptersTest implements Opcodes {
    private static final String SET = "fix";

    @TempDir
    Path dir;

    private Path stubJar() throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        ClassWriter listener = new ClassWriter(0);
        listener.visit(V21, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "net/minecraft/Listener", null, "java/lang/Object", null);
        listener.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "click", "(I)Z", null, null).visitEnd();
        listener.visitEnd();
        classes.put("net/minecraft/Listener.class", listener.toByteArray());

        ClassWriter screen = new ClassWriter(0);
        screen.visit(V21, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, "net/minecraft/Screen", null, "java/lang/Object", new String[] {"net/minecraft/Listener"});
        screen.visitField(ACC_PUBLIC, "width", "I", null, null).visitEnd();
        screen.visitMethod(ACC_PROTECTED, "<init>", "()V", null, null).visitEnd();
        screen.visitMethod(ACC_PUBLIC, "click", "(I)Z", null, null).visitEnd();
        screen.visitMethod(ACC_PROTECTED, "init", "()V", null, null).visitEnd();
        screen.visitEnd();
        classes.put("net/minecraft/Screen.class", screen.toByteArray());

        ClassWriter panel = new ClassWriter(0);
        panel.visit(V21, ACC_PUBLIC | ACC_SUPER, "net/minecraft/Panel", null, "net/minecraft/Screen", null);
        panel.visitEnd();
        classes.put("net/minecraft/Panel.class", panel.toByteArray());

        Path jar = dir.resolve("stubs.jar");
        StubJars.writeJar(jar, classes);
        return jar;
    }

    /** named to obf (every class of a family has a row, as in Mojang's ProGuard file), then obf to intermediary (root rows only). */
    private List<StubJars.Table> stages() throws IOException {
        Path named = dir.resolve("named.tiny");
        Files.writeString(named, String.join("\n",
                "tiny\t2\t0\tnamed\tofficial",
                "c\tnet/minecraft/Listener\ta",
                "\tm\t(I)Z\tclick\tx",
                "c\tnet/minecraft/Screen\tb",
                "\tf\tI\twidth\tw",
                "\tm\t(I)Z\tclick\tx",
                "\tm\t()V\tinit\ty",
                "c\tnet/minecraft/Panel\tc",
                ""));
        Path intermediary = dir.resolve("intermediary.tiny");
        Files.writeString(intermediary, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tm\t(I)Z\tx\tmethod_10",
                "c\tb\tnet/minecraft/class_2",
                "\tf\tI\tw\tfield_30",
                "\tm\t()V\ty\tmethod_20",
                "c\tc\tnet/minecraft/class_3",
                ""));
        return List.of(StubJars.Table.read(named, 0, 1), StubJars.Table.read(intermediary, 0, 1));
    }

    /**
     * {@code com/blanket/adapters/Host extends net/minecraft/Screen} (with {@code @Stubs}, which must disappear):
     * overrides {@code click(I)Z} and {@code init()V}; {@code use(Panel)} calls {@code Panel.click} (declared on
     * Screen, mapped on Listener), reads {@code this.width} (declared on Screen) and calls {@code Util.help()}.
     */
    private Map<String, byte[]> adapters() {
        Map<String, byte[]> classes = new TreeMap<>();
        ClassWriter host = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        host.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, "com/blanket/adapters/Host", null, "net/minecraft/Screen", null);
        host.visitAnnotation(RemapAdapters.STUBS_DESC, false).visit("value", "9.9");
        MethodVisitor init = host.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "net/minecraft/Screen", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor click = host.visitMethod(ACC_PUBLIC, "click", "(I)Z", null, null);
        click.visitCode();
        click.visitInsn(ICONST_1);
        click.visitInsn(IRETURN);
        click.visitMaxs(0, 0);
        click.visitEnd();
        MethodVisitor in = host.visitMethod(ACC_PROTECTED, "init", "()V", null, null);
        in.visitCode();
        in.visitInsn(RETURN);
        in.visitMaxs(0, 0);
        in.visitEnd();
        MethodVisitor use = host.visitMethod(ACC_PUBLIC, "use", "(Lnet/minecraft/Panel;)I", null, null);
        use.visitCode();
        use.visitVarInsn(ALOAD, 1);
        use.visitInsn(ICONST_0);
        use.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/Panel", "click", "(I)Z", false);
        use.visitInsn(POP);
        use.visitMethodInsn(INVOKESTATIC, "com/blanket/adapters/Util", "help", "()V", false);
        use.visitVarInsn(ALOAD, 0);
        use.visitFieldInsn(GETFIELD, "com/blanket/adapters/Host", "width", "I");
        use.visitInsn(IRETURN);
        use.visitMaxs(0, 0);
        use.visitEnd();
        host.visitEnd();
        classes.put("com/blanket/adapters/Host", host.toByteArray());

        ClassWriter util = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        util.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, "com/blanket/adapters/Util", null, "java/lang/Object", null);
        MethodVisitor help = util.visitMethod(ACC_PUBLIC | ACC_STATIC, "help", "()V", null, null);
        help.visitCode();
        help.visitInsn(RETURN);
        help.visitMaxs(0, 0);
        help.visitEnd();
        util.visitEnd();
        classes.put("com/blanket/adapters/Util", util.toByteArray());
        return classes;
    }

    private AdapterRefs remap(Path out) throws IOException {
        Path jar = stubJar();
        List<StubJars.Table> stages = stages();
        return RemapAdapters.remapSet(SET, "intermediary", "1.0", adapters(), v -> new RemapAdapters.Era(jar, List.of(), stages), out);
    }

    private static ClassNode node(Path file) throws IOException {
        ClassNode n = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(n, 0);
        return n;
    }

    @Test
    void classesMoveToTheSetPackageAndGameNamesBecomeShippingNames() throws Exception {
        Path out = dir.resolve("out");
        remap(out);
        List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(out)) {
            s.filter(Files::isRegularFile).forEach(p -> files.add(out.relativize(p).toString().replace('\\', '/')));
        }
        files.sort(null);
        assertEquals(List.of("adapter-refs.json", "com/blanket/adapters/fix/Host.class", "com/blanket/adapters/fix/Util.class"), files);

        ClassNode host = node(out.resolve("com/blanket/adapters/fix/Host.class"));
        assertEquals("com/blanket/adapters/fix/Host", host.name);
        assertEquals("net/minecraft/class_2", host.superName);
        assertTrue(host.invisibleAnnotations == null || host.invisibleAnnotations.isEmpty(), "@Stubs is compile-only");

        MethodNode use = host.methods.stream().filter(m -> m.name.equals("use")).findFirst().orElseThrow();
        assertEquals("(Lnet/minecraft/class_3;)I", use.desc);
        List<String> refs = new ArrayList<>();
        for (AbstractInsnNode i : use.instructions) {
            if (i instanceof MethodInsnNode m) refs.add(m.owner + "." + m.name + m.desc);
            if (i instanceof FieldInsnNode f) refs.add(f.owner + "." + f.name + ":" + f.desc);
        }
        assertEquals(List.of("net/minecraft/class_3.method_10(I)Z", "com/blanket/adapters/fix/Util.help()V",
                "com/blanket/adapters/fix/Host.field_30:I"), refs);
    }

    @Test
    void overridingDeclarationsAreRenamedWithTheMethodTheyOverride() throws Exception {
        Path out = dir.resolve("out");
        remap(out);
        ClassNode host = node(out.resolve("com/blanket/adapters/fix/Host.class"));
        List<String> methods = host.methods.stream().map(m -> m.name + m.desc).sorted().toList();
        // click(I)Z is mapped only on the interface two levels up; init()V on the superclass; use() overrides nothing
        assertEquals(List.of("<init>()V", "method_10(I)Z", "method_20()V", "use(Lnet/minecraft/class_3;)I"), methods);
    }

    @Test
    void adapterRefsFollowTheContract() throws Exception {
        Path out = dir.resolve("out");
        AdapterRefs refs = remap(out);
        assertEquals(Map.of(
                // bytecode owner Panel (class_3), declared on Screen, mapping row on Listener (class_1)
                "net/minecraft/class_3#method_10(I)Z", "net/minecraft/class_1",
                // a field read through the adapter's own class
                "com/blanket/adapters/fix/Host#field_30:I", "net/minecraft/class_2"), refs.declaring());
        assertEquals(Map.of(
                "com/blanket/adapters/fix/Host#method_10(I)Z", "net/minecraft/class_1",
                "com/blanket/adapters/fix/Host#method_20()V", "net/minecraft/class_2"), refs.overrides());

        Path file = out.resolve(AdapterRefs.FILE);
        JsonObject json = BindingFiles.readJson(file).getAsJsonObject();
        assertEquals(List.of("schema", "set", "namespace", "declaring", "overrides"), new ArrayList<>(json.keySet()));
        assertEquals(1, json.get("schema").getAsInt());
        assertEquals(SET, json.get("set").getAsString());
        assertEquals("intermediary", json.get("namespace").getAsString());
        assertEquals(refs, AdapterRefs.read(file));
    }

    @Test
    void aRerunGivesTheSameBytesAndRemovesStaleClasses() throws Exception {
        Path out = dir.resolve("out");
        remap(out);
        byte[] first = Files.readAllBytes(out.resolve("com/blanket/adapters/fix/Host.class"));
        String refs = Files.readString(out.resolve(AdapterRefs.FILE));
        Path stale = out.resolve("com/blanket/adapters/fix/Gone.class");
        Files.write(stale, new byte[] {1});
        remap(out);
        assertFalse(Files.exists(stale));
        assertTrue(java.util.Arrays.equals(first, Files.readAllBytes(out.resolve("com/blanket/adapters/fix/Host.class"))));
        assertEquals(refs, Files.readString(out.resolve(AdapterRefs.FILE)));
    }

    @Test
    void withoutStagesNamesStayAndOnlyThePackageMoves() throws Exception {
        Path jar = stubJar();
        Path out = dir.resolve("official");
        AdapterRefs refs = RemapAdapters.remapSet(SET, "official", "1.0", adapters(), v -> new RemapAdapters.Era(jar, List.of(), List.of()), out);
        ClassNode host = node(out.resolve("com/blanket/adapters/fix/Host.class"));
        assertEquals("net/minecraft/Screen", host.superName);
        assertNotNull(host.methods.stream().filter(m -> (m.name + m.desc).equals("click(I)Z")).findFirst().orElse(null));
        // no mapping rows: the declaring class is the one the JVM resolves to, the nearest declarer
        assertEquals("net/minecraft/Screen", refs.declaring().get("net/minecraft/Panel#click(I)Z"));
        assertEquals("net/minecraft/Screen", refs.overrides().get("com/blanket/adapters/fix/Host#click(I)Z"));
    }

    @Test
    void stubsVersionIsReadFromTheAnnotation() {
        assertEquals("9.9", RemapAdapters.stubsVersion(adapters().get("com/blanket/adapters/Host")));
        assertEquals(null, RemapAdapters.stubsVersion(adapters().get("com/blanket/adapters/Util")));
    }
}
