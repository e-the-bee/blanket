// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.blanket.core.lower.FoldTestSupport.supports;
import static com.blanket.core.lower.FoldTestSupport.thenElse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FoldingTest implements Opcodes {
    private static int eval(Consumer<MethodVisitor> code, boolean a, boolean b) throws Throwable {
        ClassNode n = FoldTestSupport.classWith(code);
        List<Diagnostic> errors = new ArrayList<>();
        Folding.fold(n, Map.of("A", a, "B", b), "t", errors);
        DeadCode.remove(n);
        assertEquals(List.of(), errors);
        for (AbstractInsnNode i : n.methods.get(0).instructions) {
            assertFalse(i instanceof MethodInsnNode, "supports call survived: " + LowerFixtures.dump(FoldTestSupport.write(n)));
        }
        return FoldTestSupport.run(n);
    }

    @Test
    void trueAndFalse() throws Throwable {
        Consumer<MethodVisitor> code = mv -> thenElse(mv, els -> {
            supports(mv, "A");
            mv.visitJumpInsn(IFEQ, els);
        });
        assertEquals(1, eval(code, true, false));
        assertEquals(2, eval(code, false, true));
    }

    @Test
    void negation() throws Throwable {
        // !supports(A): IFEQ L1; ICONST_0; GOTO L2; L1: ICONST_1; L2: IFEQ else
        Consumer<MethodVisitor> code = mv -> thenElse(mv, els -> {
            Label l1 = new Label();
            Label l2 = new Label();
            supports(mv, "A");
            mv.visitJumpInsn(IFEQ, l1);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, l2);
            mv.visitLabel(l1);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(l2);
            mv.visitJumpInsn(IFEQ, els);
        });
        assertEquals(2, eval(code, true, true));
        assertEquals(1, eval(code, false, true));
    }

    @Test
    void and() throws Throwable {
        Consumer<MethodVisitor> code = mv -> thenElse(mv, els -> {
            supports(mv, "A");
            mv.visitJumpInsn(IFEQ, els);
            supports(mv, "B");
            mv.visitJumpInsn(IFEQ, els);
        });
        assertEquals(1, eval(code, true, true));
        assertEquals(2, eval(code, true, false));
        assertEquals(2, eval(code, false, true));
    }

    @Test
    void or() throws Throwable {
        Consumer<MethodVisitor> code = mv -> {
            Label then = new Label();
            Label els = new Label();
            supports(mv, "A");
            mv.visitJumpInsn(IFNE, then);
            supports(mv, "B");
            mv.visitJumpInsn(IFEQ, els);
            mv.visitLabel(then);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
            mv.visitLabel(els);
            mv.visitInsn(ICONST_2);
            mv.visitInsn(IRETURN);
        };
        assertEquals(1, eval(code, true, false));
        assertEquals(1, eval(code, false, true));
        assertEquals(2, eval(code, false, false));
    }

    @Test
    void writeOnceLocal() throws Throwable {
        Consumer<MethodVisitor> code = mv -> thenElse(mv, els -> {
            supports(mv, "A");
            mv.visitVarInsn(ISTORE, 0);
            mv.visitVarInsn(ILOAD, 0);
            mv.visitJumpInsn(IFEQ, els);
        });
        assertEquals(1, eval(code, true, false));
        assertEquals(2, eval(code, false, false));
    }

    @Test
    void nonLiteralArgumentIsForbidden() {
        ClassNode n = LowerFixtures.node(LowerFixtures.define("mod/T", "java/lang/Object", cv ->
                LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "g", "(L" + FoldTestSupport.FEATURE + ";)Z", mv -> {
                    Label line = new Label();
                    mv.visitLabel(line);
                    mv.visitLineNumber(7, line);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKESTATIC, FoldTestSupport.BLANKET, "supports", "(L" + FoldTestSupport.FEATURE + ";)Z", false);
                    mv.visitInsn(IRETURN);
                })));
        List<Diagnostic> errors = new ArrayList<>();
        Folding.fold(n, Map.of("A", true), "fabric:9.9.9", errors);
        assertEquals(1, errors.size());
        Diagnostic d = errors.get(0);
        assertEquals(ErrorCode.FORBIDDEN, d.code());
        assertEquals("fabric:9.9.9", d.target());
        assertEquals(true, d.detail().startsWith("mod.T.g(Lcom/blanket/api/Feature;)Z:7 "), d.detail());
    }
}
