// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an adapter class whose references resolve only on part of its set's range (binding-format.md example 5):
 * {@code compileAdapters} compiles it in its own javac run against the stub jar of {@link #value()} instead of the
 * set's stubs version, and {@code remapAdapters} remaps it with that version's mappings. Binding rules point at such a
 * class only on the {@code when} ranges it was written for. Compile-only: the annotation is removed when the classes
 * are remapped, and this class never ships.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Stubs {
    /** The Minecraft version whose stub jar the class is compiled against, for example {@code "1.20.1"}. */
    String value();
}
