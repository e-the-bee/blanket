// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The public members of API and glue classes (compiled {@code blanket-api} and {@code blanket-runtime}), read without loading them. */
public interface ApiClasses {

    /** A public method declared by an API class. {@code desc} is in API names ({@code Lcom/blanket/api/Player;}). */
    record Method(String name, String desc, boolean isStatic) {}

    /** The public, non-synthetic methods the class declares itself (no constructors), or empty when the class is not found. */
    Optional<List<Method>> methods(String internalName);

    /** Reads {@code <internalName>.class} through {@code loader}. */
    static ApiClasses of(ClassLoader loader) {
        return internalName -> {
            try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
                if (in == null) return Optional.empty();
                List<Method> out = new ArrayList<>();
                new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        boolean visible = (access & Opcodes.ACC_PUBLIC) != 0 && (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0;
                        if (visible && !name.startsWith("<")) out.add(new Method(name, desc, (access & Opcodes.ACC_STATIC) != 0));
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return Optional.of(out);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read API class " + internalName + ": " + e.getMessage(), e);
            }
        };
    }
}
