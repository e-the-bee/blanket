// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;

import java.util.List;

/** Class-file version check (lowering-and-adapters.md section 7, D-024). */
public final class ClassVersions {
    private ClassVersions() {}

    /** The highest class-file major version a Java level reads: 8 gives 52, 17 gives 61, 21 gives 65. */
    public static int max(int javaLevel) {
        if (javaLevel < 1) throw new IllegalArgumentException("java level " + javaLevel);
        return 44 + javaLevel;
    }

    /** Adds a {@code VERSION} error when {@code classFile}'s major version is above {@code max}. */
    public static void check(String name, byte[] classFile, int max, String target, List<Diagnostic> errors) {
        if (classFile.length < 8) throw new IllegalArgumentException("not a class file: " + name);
        int major = ((classFile[6] & 0xFF) << 8) | (classFile[7] & 0xFF);
        if (major <= max) return;
        errors.add(new Diagnostic(ErrorCode.VERSION, target, name, null, null,
                "class-file version: " + name + " has major version " + major + " but target " + target
                        + " accepts at most " + max + " (Java " + (max - 44) + ")",
                "compile the mod with --release " + (max - 44) + " or lower, or drop this target"));
    }
}
