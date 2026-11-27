// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeadCodeTest implements Opcodes {
    /** A class-file-49 class (no frames needed, so the dead code stays in the file) with {@code f()I} and {@code g()I}. */
    private static ClassNode withDeadCode() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_5, ACC_PUBLIC | ACC_SUPER, "mod/T", null, "java/lang/Object", null);
        var f = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "f", "()I", null, null);
        f.visitCode();
        f.visitInsn(ICONST_1);
        f.visitInsn(IRETURN);
        Label dead = new Label();
        f.visitLabel(dead);
        f.visitInsn(ICONST_2);
        f.visitInsn(ICONST_3);
        f.visitInsn(IADD);
        f.visitInsn(IRETURN);
        f.visitMaxs(0, 0);
        f.visitEnd();

        // g: a try block that covers only dead code, with a dead handler; then a live try block with a live handler
        var g = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "g", "()I", null, null);
        g.visitCode();
        Label ds = new Label();
        Label de = new Label();
        Label dh = new Label();
        Label ls = new Label();
        Label le = new Label();
        Label lh = new Label();
        g.visitTryCatchBlock(ds, de, dh, null);
        g.visitTryCatchBlock(ls, le, lh, null);
        g.visitJumpInsn(GOTO, ls);
        g.visitLabel(ds);
        g.visitInsn(ICONST_0);
        g.visitInsn(POP);
        g.visitLabel(de);
        g.visitInsn(ICONST_2);
        g.visitInsn(IRETURN);
        g.visitLabel(dh);
        g.visitInsn(POP);
        g.visitInsn(ICONST_3);
        g.visitInsn(IRETURN);
        g.visitLabel(ls);
        g.visitInsn(ICONST_1);
        g.visitInsn(IRETURN);
        g.visitLabel(le);
        g.visitLabel(lh);
        g.visitInsn(POP);
        g.visitInsn(ICONST_4);
        g.visitInsn(IRETURN);
        g.visitMaxs(0, 0);
        g.visitEnd();
        cw.visitEnd();
        return LowerFixtures.node(cw.toByteArray());
    }

    private static List<Integer> opcodes(MethodNode m) {
        List<Integer> out = new ArrayList<>();
        for (AbstractInsnNode i : m.instructions) {
            if (i.getOpcode() >= 0) out.add(i.getOpcode());
        }
        return out;
    }

    @Test
    void removedRangeIsNopsThenAthrow() {
        ClassNode n = withDeadCode();
        DeadCode.remove(n);
        assertEquals(List.of(ICONST_1, IRETURN, NOP, NOP, NOP, ATHROW), opcodes(n.methods.get(0)));
    }

    @Test
    void handlerForDeadCodeIsGoneAndClassStillLoads() throws Throwable {
        ClassNode n = withDeadCode();
        DeadCode.remove(n);
        MethodNode g = n.methods.get(1);
        assertEquals(1, g.tryCatchBlocks.size(), "only the live entry stays");
        assertEquals(List.of(GOTO, NOP, NOP, NOP, NOP, NOP, NOP, ATHROW, ICONST_1, IRETURN, POP, ICONST_4, IRETURN), opcodes(g));

        n.version = V21;
        byte[] bytes = FoldTestSupport.write(n);
        StringWriter log = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(log));
        assertEquals("", log.toString());
        Class<?> c = LowerFixtures.load(java.util.Map.of("mod/T", bytes), "mod/T");
        assertEquals(1, LowerFixtures.invokeStatic(c, "f", new Class<?>[0]));
        assertEquals(1, LowerFixtures.invokeStatic(c, "g", new Class<?>[0]));
    }
}
