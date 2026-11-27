// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.validate.Diagnostic;

import java.util.List;

/** A lowering pass found errors in the mod; the message is the {@link Diagnostic} lines. */
public final class LowerException extends RuntimeException {
    private final List<Diagnostic> diagnostics;

    public LowerException(List<Diagnostic> diagnostics) {
        super(message(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    private static String message(List<Diagnostic> ds) {
        StringBuilder b = new StringBuilder();
        for (Diagnostic d : ds) b.append(b.length() == 0 ? "" : "\n").append(d.format());
        return b.toString();
    }
}
