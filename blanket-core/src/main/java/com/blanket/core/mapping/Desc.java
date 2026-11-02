// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.mapping;

import java.util.function.UnaryOperator;

/** JVM descriptor helpers: class names inside {@code L...;} are the only parts that change between namespaces. */
public final class Desc {
    private Desc() {}

    /** Applies {@code fn} to every internal class name in a descriptor or a method signature. */
    public static String mapClasses(String desc, UnaryOperator<String> fn) {
        StringBuilder out = new StringBuilder(desc.length());
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) throw new IllegalArgumentException("unterminated class in descriptor " + desc);
                out.append('L').append(fn.apply(desc.substring(i + 1, end))).append(';');
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Internal class names of a descriptor, in order (duplicates kept). */
    public static java.util.List<String> classesIn(String desc) {
        java.util.List<String> out = new java.util.ArrayList<>();
        mapClasses(desc, c -> { out.add(c); return c; });
        return out;
    }

    /** Converts a dotted or slashed Java name into an internal name. */
    public static String internal(String name) {
        return name.replace('.', '/');
    }
}
