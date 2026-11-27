// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;
import java.util.function.Consumer;

/** Shared by the FOLD tests: tiny classes with a static method {@code f()I}, passes applied, result run. */
final class FoldTestSupport implements Opcodes {
    private FoldTestSupport() {}

    static final String FEATURE = "com/blanket/api/Feature";
    static final String BLANKET = "com/blanket/api/Blanket";

    static void supports(MethodVisitor mv, String feature) {
        mv.visitFieldInsn(GETSTATIC, FEATURE, feature, "L" + FEATURE + ";");
        mv.visitMethodInsn(INVOKESTATIC, BLANKET, "supports", "(L" + FEATURE + ";)Z", false);
    }

    /** The class {@code mod/T} with {@code public static int f()} written by {@code code}. */
    static ClassNode classWith(Consumer<MethodVisitor> code) {
        byte[] b = LowerFixtures.define("mod/T", "java/lang/Object", cv ->
                LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "f", "()I", code));
        return LowerFixtures.node(b);
    }

    /** Writes the node with computed frames and a hierarchy-free writer, as the pipeline's last step does. */
    static byte[] write(ClassNode node) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        node.accept(cw);
        return cw.toByteArray();
    }

    /** Runs {@code f()} of the written class in a fresh loader (the JVM verifier checks it). */
    static int run(ClassNode node) throws Throwable {
        Map<String, byte[]> classes = Map.of("mod/T", write(node));
        Class<?> c = LowerFixtures.load(classes, "mod/T");
        return (Integer) LowerFixtures.invokeStatic(c, "f", new Class<?>[0]);
    }

    /** {@code if (<cond>) return 1; else return 2;} where cond jumps to {@code els} when false. */
    static void thenElse(MethodVisitor mv, Consumer<Label> cond) {
        Label els = new Label();
        cond.accept(els);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(els);
        mv.visitInsn(ICONST_2);
        mv.visitInsn(IRETURN);
    }
}
