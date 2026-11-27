// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import org.objectweb.asm.ClassWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Class writers whose frame computation answers from a {@link Hierarchy} (lowering-and-adapters.md section 6). */
public final class Frames {
    private static final String OBJECT = "java/lang/Object";

    private Frames() {}

    /**
     * A writer with {@code COMPUTE_FRAMES | COMPUTE_MAXS}, built without a {@code ClassReader} so constants of removed
     * code leave the pool. {@code getCommonSuperClass} follows ASM's algorithm over {@code hierarchy} and never loads
     * a class; a class the hierarchy does not know raises {@link TypeNotPresentException}.
     */
    public static ClassWriter writer(Hierarchy hierarchy) {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return commonSuper(hierarchy, a, b);
            }
        };
    }

    static String commonSuper(Hierarchy h, String a, String b) {
        if (a.equals(b)) return a;
        if (isAssignable(h, a, b)) return a;
        if (isAssignable(h, b, a)) return b;
        if (facts(h, a).isInterface() || facts(h, b).isInterface()) return OBJECT;
        String c = a;
        do {
            c = facts(h, c).superName();
            if (c == null) return OBJECT;
        } while (!isAssignable(h, c, b));
        return c;
    }

    /** True when a value of class {@code sub} can be stored as {@code sup}. */
    private static boolean isAssignable(Hierarchy h, String sup, String sub) {
        if (sup.equals(OBJECT) || sup.equals(sub)) return true;
        List<String> todo = new ArrayList<>();
        todo.add(sub);
        for (int i = 0; i < todo.size(); i++) {
            String n = todo.get(i);
            if (n.equals(sup)) return true;
            if (n.equals(OBJECT)) continue;
            Hierarchy.Facts f = facts(h, n);
            if (f.superName() != null) todo.add(f.superName());
            todo.addAll(f.interfaces());
        }
        return false;
    }

    private static Hierarchy.Facts facts(Hierarchy h, String name) {
        if (name.equals(OBJECT)) return new Hierarchy.Facts(null, List.of(), false);
        Optional<Hierarchy.Facts> f = h.get(name);
        if (f.isEmpty()) {
            throw new TypeNotPresentException(name.replace('/', '.'),
                    new IllegalStateException("class not in the hierarchy: " + name));
        }
        return f.get();
    }
}
