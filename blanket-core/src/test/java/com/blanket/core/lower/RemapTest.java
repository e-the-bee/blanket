// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RemapTest implements Opcodes {
    private static final TypeMap MAP = new TypeMap(Map.of("com/blanket/api/Player", "net/game/Player"), "org/mod");

    private static ClassNode remapped(ClassNode in) {
        ClassNode out = new ClassNode();
        in.accept(new ClassRemapper(out, MAP));
        return out;
    }

    @Test
    void mapsEachGroup() {
        assertEquals("net/game/Player", MAP.map("com/blanket/api/Player"));
        assertEquals("org/mod/blanket/runtime/Gl", MAP.map("com/blanket/runtime/Gl"));
        assertEquals("org/mod/blanket/adapters/Draw", MAP.map("com/blanket/adapters/modern/Draw"));
        assertEquals("org/mod/blanket/api/Screen", MAP.map("com/blanket/api/Screen"));
        assertEquals("java/lang/String", MAP.map("java/lang/String"));
        assertEquals("[Lnet/game/Player;", MAP.mapType("[Lcom/blanket/api/Player;"));
    }

    @Test
    void signatureHandleMethodTypeLdcAndString() {
        byte[] cf = LowerFixtures.define("org/mod/M", "java/lang/Object", cv -> {
            cv.visitField(ACC_PUBLIC, "f", "Ljava/util/List;", "Ljava/util/List<Lcom/blanket/api/Player;>;", null);
            LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "m", "()V", mv -> {
                mv.visitLdcInsn(Type.getObjectType("com/blanket/api/Player"));
                mv.visitInsn(POP);
                mv.visitLdcInsn("com/blanket/api/Player");
                mv.visitInsn(POP);
                mv.visitLdcInsn(Type.getMethodType("(Lcom/blanket/api/Player;)V"));
                mv.visitInsn(POP);
                mv.visitLdcInsn(new Handle(H_INVOKESTATIC, "com/blanket/runtime/Gl", "x", "(Lcom/blanket/api/Player;)V", false));
                mv.visitInsn(POP);
                mv.visitInsn(RETURN);
            });
        });
        ClassNode out = remapped(LowerFixtures.node(cf));
        assertEquals("Ljava/util/List<Lnet/game/Player;>;", out.fields.get(0).signature);
        List<Object> ldc = new ArrayList<>();
        out.methods.stream().filter(m -> m.name.equals("m")).findFirst().orElseThrow().instructions.forEach(i -> {
            if (i instanceof org.objectweb.asm.tree.LdcInsnNode l) ldc.add(l.cst);
        });
        assertEquals(Type.getObjectType("net/game/Player"), ldc.get(0));
        assertEquals("com/blanket/api/Player", ldc.get(1));
        assertEquals(Type.getMethodType("(Lnet/game/Player;)V"), ldc.get(2));
        assertEquals(new Handle(H_INVOKESTATIC, "org/mod/blanket/runtime/Gl", "x", "(Lnet/game/Player;)V", false), ldc.get(3));
    }

    @Test
    void indyBootstrapArgumentsAreMapped() {
        ClassNode n = new ClassNode();
        n.visit(V21, ACC_PUBLIC, "org/mod/L", null, "java/lang/Object", null);
        MethodNode m = new MethodNode(ACC_PUBLIC | ACC_STATIC, "m", "()Ljava/util/function/Consumer;", null, null);
        m.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;",
                new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false),
                Type.getMethodType("(Ljava/lang/Object;)V"),
                new Handle(H_INVOKESTATIC, "com/blanket/api/Player", "t", "(Lcom/blanket/api/Player;)V", false),
                Type.getMethodType("(Lcom/blanket/api/Player;)V"));
        m.visitInsn(ARETURN);
        n.methods.add(m);
        ClassNode out = remapped(n);
        var indy = (org.objectweb.asm.tree.InvokeDynamicInsnNode) out.methods.get(0).instructions.getFirst();
        assertEquals(new Handle(H_INVOKESTATIC, "net/game/Player", "t", "(Lnet/game/Player;)V", false), indy.bsmArgs[1]);
        assertEquals(Type.getMethodType("(Lnet/game/Player;)V"), indy.bsmArgs[2]);
    }

    private static Hierarchy stubs() {
        return Hierarchy.layered(Map.of(
                "g/Base", new Hierarchy.Facts("java/lang/Object", List.of(), false),
                "g/A", new Hierarchy.Facts("g/Base", List.of(), false),
                "g/B", new Hierarchy.Facts("g/Base", List.of(), false),
                "g/I", new Hierarchy.Facts("java/lang/Object", List.of(), true)), Hierarchy.empty());
    }

    private static byte[] mergeClass(ClassWriter cw) {
        cw.visit(V21, ACC_PUBLIC, "t/Merge", null, "java/lang/Object", null);
        LowerFixtures.method(cw, ACC_PUBLIC | ACC_STATIC, "m", "(ZLg/A;Lg/B;)V", mv -> {
            Label other = new Label(), join = new Label();
            mv.visitVarInsn(ILOAD, 0);
            mv.visitJumpInsn(IFEQ, other);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitJumpInsn(GOTO, join);
            mv.visitLabel(other);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitLabel(join);
            mv.visitMethodInsn(INVOKEVIRTUAL, "g/Base", "hello", "()V", false);
            mv.visitInsn(RETURN);
        });
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void framesUseTheHierarchyForMerges() {
        // g/A and g/B merge to g/Base: the call on g/Base verifies structurally (frame says g/Base).
        String good = frames(mergeClass(Frames.writer(stubs())));
        ClassWriter objectOnly = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        String bad = frames(mergeClass(objectOnly));
        assertTrue(good.contains("g/Base") && !good.contains("java/lang/Object"), good);
        assertTrue(bad.contains("java/lang/Object") && !bad.contains("g/Base"), bad);
    }

    private static String frames(byte[] cf) {
        return LowerFixtures.dump(cf).lines().filter(l -> l.contains("FRAME")).reduce("", (x, y) -> x + y + "\n");
    }

    @Test
    void commonSuperRules() {
        Hierarchy h = stubs();
        assertEquals("g/Base", Frames.commonSuper(h, "g/A", "g/B"));
        assertEquals("g/Base", Frames.commonSuper(h, "g/A", "g/Base"));
        assertEquals("java/lang/Object", Frames.commonSuper(h, "g/A", "g/I"));
        assertThrows(TypeNotPresentException.class, () -> Frames.commonSuper(h, "g/A", "g/Unknown"));
    }

    @Test
    void removedCodeConstantsLeaveThePool() {
        ClassNode n = new ClassNode();
        n.visit(V21, ACC_PUBLIC, "t/Pool", null, "java/lang/Object", null);
        MethodNode m = new MethodNode(ACC_PUBLIC | ACC_STATIC, "m", "()V", null, null);
        m.visitLdcInsn("DEAD_CONSTANT_STRING");
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        n.methods.add(m);
        byte[] first = write(n);
        assertTrue(new String(first, java.nio.charset.StandardCharsets.ISO_8859_1).contains("DEAD_CONSTANT_STRING"));
        m.instructions = new InsnList();
        m.visitInsn(RETURN);
        byte[] second = write(n);
        assertFalse(new String(second, java.nio.charset.StandardCharsets.ISO_8859_1).contains("DEAD_CONSTANT_STRING"));
    }

    private static byte[] write(ClassNode n) {
        ClassWriter cw = Frames.writer(Hierarchy.empty());
        n.accept(cw);
        return cw.toByteArray();
    }

    @Test
    void collisionsReportBothSignatures() {
        ClassNode n = new ClassNode();
        n.visit(V21, ACC_PUBLIC, "org/mod/C", null, "java/lang/Object", null);
        n.visitMethod(ACC_PUBLIC, "f", "(Lcom/blanket/api/Player;)V", null, null);
        n.visitMethod(ACC_PUBLIC, "f", "(Lnet/game/Player;)V", null, null);
        n.visitMethod(ACC_PUBLIC, "g", "(Lnet/game/Player;)V", null, null);
        List<Diagnostic> errs = new ArrayList<>();
        Collisions.check(n, MAP, "fabric:1.21.1", errs);
        assertEquals(1, errs.size());
        Diagnostic d = errs.get(0);
        assertEquals(ErrorCode.COLLISION, d.code());
        assertEquals("fabric:1.21.1", d.target());
        assertTrue(d.detail().contains("f(Lcom/blanket/api/Player;)V") && d.detail().contains("f(Lnet/game/Player;)V"), d.detail());
        List<Diagnostic> ok = new ArrayList<>();
        ClassNode clean = new ClassNode();
        clean.visit(V21, ACC_PUBLIC, "org/mod/D", null, "java/lang/Object", null);
        clean.visitMethod(ACC_PUBLIC, "f", "(Lcom/blanket/api/Player;)V", null, null);
        clean.visitMethod(ACC_PUBLIC, "f", "(I)V", null, null);
        Collisions.check(clean, MAP, "t", ok);
        assertTrue(ok.isEmpty());
    }

    @Test
    void classVersions() {
        assertEquals(52, ClassVersions.max(8));
        assertEquals(60, ClassVersions.max(16));
        assertEquals(61, ClassVersions.max(17));
        assertEquals(65, ClassVersions.max(21));
        assertEquals(69, ClassVersions.max(25));
        ClassWriter cw = new ClassWriter(0);
        cw.visit(53, ACC_PUBLIC, "m/Old", null, "java/lang/Object", null);
        cw.visitEnd();
        byte[] v53 = cw.toByteArray();
        List<Diagnostic> errs = new ArrayList<>();
        ClassVersions.check("m/Old", v53, ClassVersions.max(8), "forge:1.8.9", errs);
        assertEquals(1, errs.size());
        assertEquals(ErrorCode.VERSION, errs.get(0).code());
        String d = errs.get(0).detail();
        assertTrue(d.contains("m/Old") && d.contains("53") && d.contains("forge:1.8.9"), d);
        errs.clear();
        ClassVersions.check("m/Old", v53, ClassVersions.max(17), "fabric:1.21.1", errs);
        assertTrue(errs.isEmpty());
    }
}
