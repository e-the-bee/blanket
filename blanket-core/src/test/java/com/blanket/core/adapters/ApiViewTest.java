// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blanket.core.bindings.Targets;
import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.validate.BindingSet;
import java.io.IOException;
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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The legacy API view of fixture API classes: alias types are replaced by the authoring-name class of their
 * representation, owned types stay, and every descriptor that mentions an alias is rewritten.
 */
class ApiViewTest implements Opcodes {
    private static final Path REAL_BINDINGS = Path.of("..", "blanket-bindings");
    private static final String NAMED_PLAYER = "fx/yarn/NamedPlayer";
    private static final String NAMED_POS = "fx/yarn/NamedPos";
    private static final String NAMED_SIDE = "fx/yarn/NamedSide";

    @TempDir
    Path dir;

    private static final String LEGACY_SET = "{\"schema\":1,\"name\":\"legacy\",\"stubs\":\"1.8.9\",\"authoring\":\"yarn-legacy\","
            + "\"authoringMappings\":\"g:a:1\",\"shipping\":\"lf\",\"covers\":{\"mc\":\"1.8.9\"}}";

    private StubJars.AdapterSet legacy() throws IOException {
        Path file = dir.resolve("adapter-set.json");
        Files.writeString(file, LEGACY_SET);
        return StubJars.readSet(file);
    }

    private static String type(String api, String representation) {
        return "{\"schema\":1,\"api\":\"" + api + "\",\"representation\":" + representation + "}";
    }

    /** targets.json of the real bindings plus three alias type files shaped like the real ones, and an owned facade. */
    private BindingSet bindings() throws IOException {
        Path b = dir.resolve("bindings");
        Files.createDirectories(b.resolve("types"));
        Files.copy(REAL_BINDINGS.resolve("targets.json"), b.resolve("targets.json"));
        Files.writeString(b.resolve("types/Player.json"), type("com.blanket.api.Player", "["
                + "{\"when\":{\"mc\":\"..1.13.2\"},\"alias\":\"lf:net/minecraft/class_518\"},"
                + "{\"when\":{\"mc\":\"26.1..\"},\"alias\":\"official:net.minecraft.client.player.LocalPlayer\"},"
                + "{\"when\":{},\"alias\":\"net.minecraft.client.player.LocalPlayer\"}]"));
        Files.writeString(b.resolve("types/BlockPos.json"), type("com.blanket.api.BlockPos", "["
                + "{\"when\":{\"mc\":\"..1.13.2\"},\"alias\":\"lf:net/minecraft/class_2552\"},"
                + "{\"when\":{\"mc\":\"26.1..\"},\"alias\":\"official:net.minecraft.core.BlockPos\"},"
                + "{\"when\":{},\"alias\":\"net.minecraft.core.BlockPos\"}]"));
        Files.writeString(b.resolve("types/Direction.json"), type("com.blanket.api.Direction", "["
                + "{\"when\":{\"mc\":\"..1.13.2\"},\"alias\":\"lf:net/minecraft/class_9999\"},"
                + "{\"when\":{\"mc\":\"26.1..\"},\"alias\":\"official:net.minecraft.core.Direction\"},"
                + "{\"when\":{},\"alias\":\"net.minecraft.core.Direction\"}]"));
        Files.writeString(b.resolve("types/Input.json"), "{\"schema\":1,\"api\":\"com.blanket.api.Input\",\"members\":{}}");
        return BindingSet.load(b);
    }

    private List<StubJars.Table> yarn() throws IOException {
        Path yarn = dir.resolve("yarn.tiny");
        Files.writeString(yarn, String.join("\n",
                "tiny\t2\t0\tintermediary\tnamed",
                "c\tnet/minecraft/class_518\t" + NAMED_PLAYER,
                "c\tnet/minecraft/class_2552\t" + NAMED_POS,
                "c\tnet/minecraft/class_9999\t" + NAMED_SIDE,
                ""));
        return List.of(StubJars.Table.read(yarn, 0, 1));
    }

    private void emptyClass(Path root, String name, int access, String desc, String body) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER | access, name, null, "java/lang/Object", null);
        if (body != null) {
            cw.visitField(ACC_PUBLIC, "owner", "Lcom/blanket/api/Player;", null, null).visitEnd();
            MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, body, desc, null, null);
            m.visitCode();
            m.visitTypeInsn(NEW, "java/lang/AssertionError");
            m.visitInsn(DUP);
            m.visitLdcInsn("blanket stub");
            m.visitMethodInsn(INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false);
            m.visitInsn(ATHROW);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        cw.visitEnd();
        Path file = root.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, cw.toByteArray());
    }

    /** blanket-api and blanket-runtime classes in API names: three alias types, a nested alias class, owned Target, a runtime class. */
    private Path apiClasses() throws IOException {
        Path root = dir.resolve("classes");
        emptyClass(root, "com/blanket/api/Player", ACC_FINAL, null, null);
        emptyClass(root, "com/blanket/api/Player$Inner", 0, null, null);
        emptyClass(root, "com/blanket/api/BlockPos", ACC_FINAL, null, null);
        emptyClass(root, "com/blanket/api/Direction", ACC_FINAL, null, null);
        emptyClass(root, "com/blanket/api/Target", 0, "(Lcom/blanket/api/BlockPos;Lcom/blanket/api/Direction;)Lcom/blanket/api/Target;", "block");
        emptyClass(root, "com/blanket/runtime/Dispatch", 0, "(Lcom/blanket/api/Player;[Lcom/blanket/api/BlockPos;)V", "hud");
        Files.writeString(root.resolve("com/blanket/api/readme.txt"), "not a class");
        return root;
    }

    private static Map<String, byte[]> entries(Path jar) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) out.put(e.getName(), zip.getInputStream(e).readAllBytes());
        }
        return out;
    }

    private static List<String> declared(byte[] classFile) {
        List<String> out = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                out.add("field " + name + " " + desc);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                out.add("method " + name + desc);
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitCode() {
                        out.add("CODE in " + name);
                    }
                };
            }
        }, 0);
        return out;
    }

    @Test
    void theLegacyViewReplacesAliasTypesAndKeepsOwnedOnes() throws Exception {
        StubJars.AdapterSet set = legacy();
        BindingSet bindings = bindings();
        TargetSpec target = ApiView.target(bindings.targets(), set);
        assertEquals("legacy-fabric:1.8.9", target.id());

        Map<String, String> aliases = ApiView.aliases(bindings, target, set, yarn());
        assertEquals(Map.of("com/blanket/api/Player", NAMED_PLAYER, "com/blanket/api/BlockPos", NAMED_POS, "com/blanket/api/Direction", NAMED_SIDE), aliases,
                "alias types only: the owned facade Input (no representation) is not in the map");

        Path out = dir.resolve("out/legacy-api.jar");
        int n = ApiView.write(out, List.of(apiClasses()), aliases);
        Map<String, byte[]> e = entries(out);
        assertEquals(2, n);
        assertEquals(List.of("com/blanket/api/Target.class", "com/blanket/runtime/Dispatch.class"), new ArrayList<>(e.keySet()),
                "alias types and their nested classes are gone; non-class files are not copied");

        assertEquals(List.of("field owner L" + NAMED_PLAYER + ";", "method block(L" + NAMED_POS + ";L" + NAMED_SIDE + ";)Lcom/blanket/api/Target;"),
                declared(e.get("com/blanket/api/Target.class")), "Target keeps its name and loses its code; the descriptor is rewritten");
        assertEquals(List.of("field owner L" + NAMED_PLAYER + ";", "method hud(L" + NAMED_PLAYER + ";[L" + NAMED_POS + ";)V"),
                declared(e.get("com/blanket/runtime/Dispatch.class")), "array descriptors are rewritten too");
    }

    @Test
    void theFirstSourceHoldingAClassWins() throws Exception {
        Path first = dir.resolve("first");
        Path second = dir.resolve("second");
        emptyClass(first, "com/blanket/api/Target", 0, "()V", "one");
        emptyClass(second, "com/blanket/api/Target", 0, "()V", "two");
        emptyClass(second, "com/blanket/api/Other", 0, "()V", "three");
        Path out = dir.resolve("view.jar");
        assertEquals(2, ApiView.write(out, List.of(first, second), Map.of()));
        assertTrue(declared(entries(out).get("com/blanket/api/Target.class")).contains("method one()V"));
    }

    @Test
    void theViewOfEachSetUsesTheStubsVersionsTarget() throws Exception {
        Targets targets = BindingFiles.readTargets(REAL_BINDINGS.resolve("targets.json"));
        assertEquals("legacy-fabric:1.8.9", ApiView.target(targets, new StubJars.AdapterSet("legacy", "1.8.9", "yarn-legacy", "g:a:1", "lf")).id());
        assertEquals("fabric:1.21.1", ApiView.target(targets, new StubJars.AdapterSet("modern", "1.21.1", "mojang", null, "intermediary")).id());
        assertEquals("fabric:26.1.2", ApiView.target(targets, new StubJars.AdapterSet("unobf", "26.1.2", "official", null, "official")).id());
        assertThrows(IllegalArgumentException.class,
                () -> ApiView.target(targets, new StubJars.AdapterSet("x", "1.7.10", "mojang", null, "intermediary")));
    }

    @Test
    void aliasesMustBeWrittenInTheNamespaceOfTheSet() throws Exception {
        StubJars.AdapterSet modern = new StubJars.AdapterSet("modern", "1.21.1", "mojang", null, "intermediary");
        Ref lf = Ref.parse("lf:net/minecraft/class_518", "types/Player.json", "$");
        IllegalArgumentException wrongNs = assertThrows(IllegalArgumentException.class,
                () -> StubJars.authoringClass(modern, lf, "1.21.1", List.of()));
        assertTrue(wrongNs.getMessage().contains("needs mojang names"), wrongNs.getMessage());

        Ref old = Ref.parse("mojang@1.20.1:net.minecraft.client.player.LocalPlayer", "types/Player.json", "$");
        IllegalArgumentException wrongVersion = assertThrows(IllegalArgumentException.class,
                () -> StubJars.authoringClass(modern, old, "1.21.1", List.of()));
        assertTrue(wrongVersion.getMessage().contains("stubs are 1.21.1"), wrongVersion.getMessage());

        Ref anchor = Ref.parse("net.minecraft.client.player.LocalPlayer", "types/Player.json", "$");
        assertEquals("net/minecraft/client/player/LocalPlayer", StubJars.authoringClass(modern, anchor, "1.21.1", List.of()),
                "no @version means the anchor, which is the stubs version here");
        assertFalse(StubJars.authoringClass(modern, anchor, "1.21.1", List.of()).contains("."));
    }
}
