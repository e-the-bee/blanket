// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static com.blanket.core.lower.FakeGame.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pass 1 behaviour that is not one of the golden kinds: pass-through, unsupported, unbound, stack effects. */
class MemberLoweringTest implements Opcodes {

    private static List<MethodInsnNode> calls(ClassNode node, String method) {
        List<MethodInsnNode> out = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof MethodInsnNode mi) out.add(mi);
            }
        }
        return out;
    }

    @Test
    void objectAndEnumMembersStayForPassFive() {
        byte[] cls = LowerFixtures.define("mod/K", "java/lang/Object", cv -> LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "run",
                "(L" + PLAYER + ";L" + DIRECTION + ";)V", mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "hashCode", "()I", false);
                    mv.visitInsn(POP);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKEVIRTUAL, DIRECTION, "name", "()Ljava/lang/String;", false);
                    mv.visitInsn(POP);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKEVIRTUAL, DIRECTION, "ordinal", "()I", false);
                    mv.visitInsn(POP);
                    mv.visitMethodInsn(INVOKESTATIC, DIRECTION, "values", "()[L" + DIRECTION + ";", false);
                    mv.visitInsn(POP);
                    mv.visitLdcInsn("UP");
                    mv.visitMethodInsn(INVOKESTATIC, DIRECTION, "valueOf", "(Ljava/lang/String;)L" + DIRECTION + ";", false);
                    mv.visitInsn(POP);
                    mv.visitInsn(RETURN);
                }));
        ClassNode node = LowerFixtures.node(cls);
        List<Diagnostic> errors = new ArrayList<>();
        MemberLowering.lower(node, FakeGame.plan(), new Bridges(node), errors);
        assertEquals(List.of(), errors);
        assertEquals(5, calls(node, "run").size());
        for (MethodInsnNode mi : calls(node, "run")) assertTrue(mi.owner.startsWith("com/blanket/api/"), mi.owner);
    }

    @Test
    void unsupportedIsLeftInPlace() {
        byte[] cls = LowerFixtures.define("mod/K", "java/lang/Object", cv -> LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "run",
                "(L" + PLAYER + ";)V", mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "gone", "()V", false);
                    mv.visitInsn(RETURN);
                }));
        ClassNode node = LowerFixtures.node(cls);
        List<Diagnostic> errors = new ArrayList<>();
        MemberLowering.lower(node, FakeGame.plan(), new Bridges(node), errors);
        assertEquals(List.of(), errors);
        MethodInsnNode only = calls(node, "run").get(0);
        assertEquals(PLAYER, only.owner);
        assertEquals("gone", only.name);
    }

    @Test
    void aliasMemberWithoutPlanEntryIsUnboundWithClassMethodAndLine() {
        byte[] cls = LowerFixtures.define("mod/K", "java/lang/Object", cv -> LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "run",
                "(L" + PLAYER + ";)D", mv -> {
                    Label l = new Label();
                    mv.visitLabel(l);
                    mv.visitLineNumber(42, l);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "z", "()D", false);
                    mv.visitInsn(DRETURN);
                }));
        ClassNode node = LowerFixtures.node(cls);
        List<Diagnostic> errors = new ArrayList<>();
        MemberLowering.lower(node, FakeGame.plan(), new Bridges(node), errors);
        assertEquals(1, errors.size());
        Diagnostic d = errors.get(0);
        assertEquals(ErrorCode.UNBOUND, d.code());
        assertTrue(d.detail().contains("mod/K.run:42"), d.detail());
        assertEquals(PLAYER, calls(node, "run").get(0).owner, "the call is left as it was");
    }

    @Test
    void ownedApiClassWithoutAliasIsUntouched() {
        byte[] cls = LowerFixtures.define("mod/K", "java/lang/Object", cv -> LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "run",
                "()V", mv -> {
                    mv.visitMethodInsn(INVOKESTATIC, "com/blanket/api/Blanket", "log", "()V", false);
                    mv.visitInsn(RETURN);
                }));
        ClassNode node = LowerFixtures.node(cls);
        List<Diagnostic> errors = new ArrayList<>();
        MemberLowering.lower(node, FakeGame.plan(), new Bridges(node), errors);
        assertEquals(List.of(), errors);
        assertEquals("com/blanket/api/Blanket", calls(node, "run").get(0).owner);
    }

    @Test
    void constantLoweringPopsTheReceiverAndKeepsTheStackBalanced() throws Throwable {
        byte[] cls = LowerFixtures.define("mod/K", "java/lang/Object", cv -> LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "run",
                "(Lgame/LocalPlayer;)Z", mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "isFake", "()Z", false);
                    mv.visitInsn(IRETURN);
                }));
        ClassNode node = LowerFixtures.node(cls);
        List<Diagnostic> errors = new ArrayList<>();
        MemberLowering.lower(node, FakeGame.plan(), new Bridges(node), errors);
        assertEquals(List.of(), errors);
        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>(FakeGame.gameClasses());
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        node.accept(cw);
        classes.put("mod/K", cw.toByteArray());
        Class<?> c = LowerFixtures.load(classes, "mod/K");
        Class<?> player = c.getClassLoader().loadClass("game.LocalPlayer");
        Object p = player.getDeclaredConstructor().newInstance();
        assertEquals(false, LowerFixtures.invokeStatic(c, "run", new Class<?>[] {player}, p));
    }
}
