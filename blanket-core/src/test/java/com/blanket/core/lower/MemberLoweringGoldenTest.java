// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Plan;
import com.blanket.core.validate.Diagnostic;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.blanket.core.lower.FakeGame.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pass 1 on one method per lowering kind, compared with a Textifier dump under {@code golden/lower/}. Set the
 * environment variable {@code BLANKET_GOLDEN_WRITE} to print the dumps (between {@code =====GOLDEN name} and
 * {@code =====END} lines) instead of comparing; the files are written from that output, with LF line endings.
 */
class MemberLoweringGoldenTest implements Opcodes {

    /** A class {@code mod/K} with the public static method {@code run} of descriptor {@code desc}. */
    private static byte[] mod(String desc, Consumer<MethodVisitor> code) {
        return LowerFixtures.define("mod/K", "java/lang/Object",
                cv -> LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "run", desc, code));
    }

    /** Lowers the class with pass 1 and returns its dump (maxima recomputed, so the text is what a writer would emit). */
    static String lowered(byte[] cls, Plan plan) {
        ClassNode node = LowerFixtures.node(cls);
        List<Diagnostic> errors = new ArrayList<>();
        MemberLowering.lower(node, plan, new Bridges(node), errors);
        assertTrue(errors.isEmpty(), errors.toString());
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(cw);
        return LowerFixtures.dump(cw.toByteArray());
    }

    private static void golden(String name, byte[] cls) throws IOException {
        String actual = lowered(cls, FakeGame.plan()).replace("\r\n", "\n");
        if (System.getenv("BLANKET_GOLDEN_WRITE") != null) {
            System.out.println("=====GOLDEN " + name + "\n" + actual + "=====END");
            return;
        }
        try (InputStream in = MemberLoweringGoldenTest.class.getResourceAsStream("/golden/lower/" + name + ".txt")) {
            assertTrue(in != null, "missing golden file " + name + ".txt");
            assertEquals(new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), actual, name);
        }
    }

    @Test
    void invokeVirtual() throws IOException {
        golden("invoke-virtual", mod("(L" + PLAYER + ";)D", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "x", "()D", false);
            mv.visitInsn(DRETURN);
        }));
    }

    @Test
    void invokeStatic() throws IOException {
        golden("invoke-static", mod("()L" + VEC3 + ";", mv -> {
            mv.visitMethodInsn(INVOKESTATIC, VEC3, "zero", "()L" + VEC3 + ";", false);
            mv.visitInsn(ARETURN);
        }));
    }

    @Test
    void invokeInterface() throws IOException {
        golden("invoke-interface", mod("(L" + BLOCK_STATE + ";)I", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK_STATE, "id", "()I", false);
            mv.visitInsn(IRETURN);
        }));
    }

    @Test
    void getField() throws IOException {
        golden("get-field", mod("(L" + PLAYER + ";)D", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "y", "()D", false);
            mv.visitInsn(DRETURN);
        }));
    }

    @Test
    void putField() throws IOException {
        golden("put-field", mod("(L" + PLAYER + ";D)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(DLOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "setY", "(D)V", false);
            mv.visitInsn(RETURN);
        }));
    }

    @Test
    void getStatic() throws IOException {
        golden("get-static", mod("()L" + VEC3 + ";", mv -> {
            mv.visitMethodInsn(INVOKESTATIC, VEC3, "origin", "()L" + VEC3 + ";", false);
            mv.visitInsn(ARETURN);
        }));
    }

    @Test
    void putStatic() throws IOException {
        golden("put-static", mod("(L" + VEC3 + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, VEC3, "setOrigin", "(L" + VEC3 + ";)V", false);
            mv.visitInsn(RETURN);
        }));
    }

    @Test
    void constant() throws IOException {
        golden("const", mod("(L" + PLAYER + ";)Z", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "isFake", "()Z", false);
            mv.visitInsn(IRETURN);
        }));
    }

    @Test
    void chainWithConstNewarrayAndCast() throws IOException {
        golden("chain", mod("(L" + CLIENT + ";)I", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, CLIENT, "score", "()I", false);
            mv.visitInsn(IRETURN);
        }));
    }

    @Test
    void adapter() throws IOException {
        golden("adapter", mod("(L" + PLAYER + ";)Ljava/lang/String;", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "name", "()Ljava/lang/String;", false);
            mv.visitInsn(ARETURN);
        }));
    }

    @Test
    void newBridge() throws IOException {
        golden("new-bridge", mod("(III)L" + BLOCK_POS + ";", mv -> {
            mv.visitVarInsn(ILOAD, 0);
            mv.visitVarInsn(ILOAD, 1);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, BLOCK_POS, "of", "(III)L" + BLOCK_POS + ";", false);
            mv.visitInsn(ARETURN);
        }));
    }

    @Test
    void enumConstant() throws IOException {
        golden("enum-constant", mod("()L" + DIRECTION + ";", mv -> {
            mv.visitFieldInsn(GETSTATIC, DIRECTION, "UP", "L" + DIRECTION + ";");
            mv.visitInsn(ARETURN);
        }));
    }
}
