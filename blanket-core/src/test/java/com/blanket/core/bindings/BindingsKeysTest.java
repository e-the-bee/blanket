package com.blanket.core.bindings;
import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Op;
import com.blanket.core.plan.Plan;
import com.blanket.core.plan.PlanSource;
import com.blanket.core.plan.Planner;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.testsupport.RealData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-KEYS: a fixture mod uses KeyBinding.register/consumePress/isDown and Input.isKeyDown; on the real bindings and
 * real game data (skipped offline) {@code register} and {@code isKeyDown} are adapters of the target's set and
 * {@code consumePress}/{@code isDown} are one direct invoke with the names {@code lookup} printed.
 */
class BindingsKeysTest implements Opcodes {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final String A = "com/blanket/api/";
    private static final List<String> TARGETS = List.of("legacy-fabric:1.8.9", "forge:1.8.9", "fabric:1.21.1", "neoforge:1.21.1", "fabric:26.1.2", "forge:1.20.1");
    /** Adapter set per target. */
    private static final String[] SET = {"legacy", "legacy", "modern", "modern", "unobf", "modern"};
    /** Runtime class of the key mapping per target. */
    private static final String[] KEY = {"net/minecraft/class_327", "avb", "net/minecraft/class_304", "net/minecraft/client/KeyMapping", "net/minecraft/client/KeyMapping", "net/minecraft/client/KeyMapping"};
    private static final String[] CONSUME = {"method_841", "f", "method_1436", "consumeClick", "consumeClick", "m_90859_"};
    private static final String[] DOWN = {"method_6619", "d", "method_1434", "isDown", "isDown", "m_90857_"};

    @TempDir
    Path tmp;

    private Path mod() throws IOException {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, ACC_PUBLIC, "com/example/keys/KeysMod", null, "java/lang/Object", new String[] {A + "ClientMod"});
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
        m.visitCode();
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKESTATIC, A + "KeyBinding", "register", "(Ljava/lang/String;L" + A + "Key;Ljava/lang/String;)L" + A + "KeyBinding;", false);
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKEVIRTUAL, A + "KeyBinding", "consumePress", "()Z", false);
        m.visitInsn(POP);
        m.visitMethodInsn(INVOKEVIRTUAL, A + "KeyBinding", "isDown", "()Z", false);
        m.visitInsn(POP);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKESTATIC, A + "Input", "isKeyDown", "(L" + A + "Key;)Z", false);
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        Path jar = tmp.resolve("keys.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("com/example/keys/KeysMod.class"));
            z.write(w.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    private static void adapter(Lowered l, String set, String name, String desc, String where) {
        assertTrue(l instanceof Lowered.Adapter, where + ": " + l);
        Lowered.Adapter a = (Lowered.Adapter) l;
        assertEquals("com/blanket/adapters/" + set + "/Keys", a.owner(), where);
        assertEquals(name, a.name(), where);
        assertEquals(desc, a.desc(), where);
    }

    private static void invoke(Lowered l, String owner, String name, String where) {
        assertTrue(l instanceof Lowered.Inline, where + ": " + l);
        List<Op> ops = ((Lowered.Inline) l).ops();
        assertEquals(1, ops.size(), where + ": " + ops);
        Op o = ops.get(0);
        assertEquals(INVOKEVIRTUAL, o.opcode(), where);
        assertEquals(owner, o.owner(), where);
        assertEquals(name, o.name(), where);
        assertEquals("()Z", o.desc(), where);
    }

    @Test
    void keyMembersAreAdaptersOrDirectInvokesOnTheSixTargets() throws IOException {
        PlanSource source;
        try {
            source = Planner.plan(mod(), BINDINGS, List.of());
        } catch (IllegalStateException e) {
            throw RealData.skipIfOffline(e);
        }
        List<String> ids = new ArrayList<>();
        for (TargetSpec t : source.targets()) ids.add(t.id());
        assertEquals(TARGETS, ids);
        String register = A + "KeyBinding.register(Ljava/lang/String;L" + A + "Key;Ljava/lang/String;)L" + A + "KeyBinding;";
        String registerDesc = "(Ljava/lang/String;L" + A + "Key;Ljava/lang/String;)L" + A + "KeyBinding;";
        for (int i = 0; i < TARGETS.size(); i++) {
            String t = TARGETS.get(i);
            Plan p = source.plan(source.targets().get(i));
            adapter(p.members().get(register), SET[i], "register", registerDesc, t + " register");
            adapter(p.members().get(A + "Input.isKeyDown(L" + A + "Key;)Z"), SET[i], "isKeyDown", "(L" + A + "Key;)Z", t + " isKeyDown");
            invoke(p.members().get(A + "KeyBinding.consumePress()Z"), KEY[i], CONSUME[i], t + " consumePress");
            invoke(p.members().get(A + "KeyBinding.isDown()Z"), KEY[i], DOWN[i], t + " isDown");
            assertEquals(KEY[i], p.types().get(A + "KeyBinding"), t);
        }
    }
}
