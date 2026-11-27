// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Glue;
import com.blanket.core.plan.Hook;
import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Plan;
import com.blanket.core.resolve.TargetSpec;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Helpers for tests of the lowering passes: a {@link Plan} built by hand, classes defined with ASM, loaded in a fresh
 * class loader (so the JVM verifier checks them) and dumped as text.
 */
public final class LowerFixtures implements Opcodes {
    private LowerFixtures() {}

    /** The target of hand-built plans: invented, Java 21, class files up to 65. */
    public static final TargetSpec TARGET = new TargetSpec("fabric", "9.9.9", "intermediary", "fabric", 21, null);

    /** A plan with the given alias types and member lowerings and nothing else. */
    public static Plan plan(Map<String, String> types, Map<String, Lowered> members) {
        return plan(types, members, Map.of());
    }

    /** A plan with the given alias types, member lowerings and feature availability; class files up to 65. */
    public static Plan plan(Map<String, String> types, Map<String, Lowered> members, Map<String, Boolean> features) {
        return new Plan(TARGET, "fabric-int", "mod/Main", "com/blanket/runtime/Lwjgl3Gl", true, types, members, features, 65,
                Map.of(), List.<Glue>of(), List.<Hook>of());
    }

    /** The same plan with adapter classes. */
    public static Plan withAdapters(Plan p, Map<String, byte[]> adapters) {
        return new Plan(p.target(), p.era(), p.entry(), p.glImpl(), p.core(), p.types(), p.members(), p.features(),
                p.classVersionMax(), adapters, p.glue(), p.hooks());
    }

    /**
     * A class file of the given name (Java 21, public), computed frames. {@code body} adds fields and methods, for
     * example through {@link #method}.
     */
    public static byte[] define(String name, String superName, Consumer<ClassVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, superName, null);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Adds a method whose code {@code code} writes; the method visitor's {@code visitMaxs} is done here. */
    public static void method(ClassVisitor cv, int access, String name, String desc, Consumer<MethodVisitor> code) {
        MethodVisitor mv = cv.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        code.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Adds the public no-argument constructor that calls {@code superName.<init>()V}. */
    public static void defaultConstructor(ClassVisitor cv, String superName) {
        method(cv, ACC_PUBLIC, "<init>", "()V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
            mv.visitInsn(RETURN);
        });
    }

    /** The class file as a tree. */
    public static ClassNode node(byte[] classFile) {
        ClassNode n = new ClassNode();
        new ClassReader(classFile).accept(n, 0);
        return n;
    }

    /** The class file as ASM's {@code Textifier} text. */
    public static String dump(byte[] classFile) {
        StringWriter out = new StringWriter();
        new ClassReader(classFile).accept(new TraceClassVisitor(null, new Textifier(), new PrintWriter(out)), 0);
        return out.toString();
    }

    /** A fresh loader that defines the given classes (internal name to bytes) and delegates the rest to the test's loader. */
    public static ClassLoader loader(Map<String, byte[]> classes) {
        return new ClassLoader(LowerFixtures.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classes.get(name.replace('.', '/'));
                if (b == null) throw new ClassNotFoundException(name);
                return defineClass(name, b, 0, b.length);
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null && classes.containsKey(name.replace('.', '/'))) c = findClass(name);
                    if (c != null) {
                        if (resolve) resolveClass(c);
                        return c;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        };
    }

    /**
     * Loads {@code name} from {@code classes} in a fresh loader and initializes it, which links it and runs the verifier;
     * a {@link VerifyError} or {@link LinkageError} propagates.
     */
    public static Class<?> load(Map<String, byte[]> classes, String name) {
        try {
            return Class.forName(name.replace('/', '.'), true, loader(classes));
        } catch (ClassNotFoundException e) {
            throw new AssertionError("class not loadable: " + name, e);
        }
    }

    /** Calls the public static method {@code name} of {@code c}; an exception thrown by it is rethrown unwrapped. */
    public static Object invokeStatic(Class<?> c, String name, Class<?>[] paramTypes, Object... args) throws Throwable {
        Method m = c.getMethod(name, paramTypes);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
