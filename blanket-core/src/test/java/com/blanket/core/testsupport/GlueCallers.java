// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.testsupport;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An embedded-classes jar for planner tests on the real bindings: the planner gives a glue member a body only when a
 * class of the used set calls it, and these tests plan without the real runtime, so this jar's {@code Dispatch} (a root)
 * stands in for the runtime classes that call the glue.
 */
public final class GlueCallers implements Opcodes {
    private GlueCallers() {}

    /**
     * Writes {@code jar} with a {@code com/blanket/runtime/Dispatch} whose {@code run} calls each named glue stub
     * ({@code GlueScreen.kind}) of the real runtime once, with null and zero arguments.
     */
    public static Path jar(Path jar, String... members) throws IOException {
        String dispatch = "com/blanket/runtime/Dispatch";
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, ACC_PUBLIC, dispatch, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        m.visitCode();
        for (String member : members) {
            Method stub = stub(member);
            for (Class<?> p : stub.getParameterTypes()) m.visitInsn(zero(Type.getType(p)));
            Type ret = Type.getReturnType(stub);
            m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(stub.getDeclaringClass()), stub.getName(), Type.getMethodDescriptor(stub), false);
            if (ret.getSize() > 0) m.visitInsn(ret.getSize() == 2 ? POP2 : POP);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry(dispatch + ".class"));
            z.write(w.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    /** The one public static method {@code Type.name} of {@code com.blanket.runtime.glue}. */
    private static Method stub(String member) {
        int dot = member.indexOf('.');
        String cls = "com.blanket.runtime.glue." + member.substring(0, dot), name = member.substring(dot + 1);
        List<Method> found = new ArrayList<>();
        try {
            for (Method m : Class.forName(cls).getMethods()) {
                if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers())) found.add(m);
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("no glue class " + cls, e);
        }
        if (found.size() != 1) throw new IllegalArgumentException(cls + " has " + found.size() + " static methods " + name);
        return found.get(0);
    }

    private static int zero(Type t) {
        return switch (t.getSort()) {
            case Type.LONG -> LCONST_0;
            case Type.FLOAT -> FCONST_0;
            case Type.DOUBLE -> DCONST_0;
            case Type.ARRAY, Type.OBJECT -> ACONST_NULL;
            default -> ICONST_0;
        };
    }
}
