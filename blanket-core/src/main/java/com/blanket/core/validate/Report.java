// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import java.util.List;

/**
 * The outcome of {@link Validator#run()}: failures, and warnings that do not fail the build (dead rules, checks that
 * could not run because data was missing).
 */
public record Report(List<Diagnostic> errors, List<String> warnings) {

    public Report {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public boolean ok() {
        return errors.isEmpty();
    }

    /** Every error on its own line, then every warning prefixed {@code warning:}, then a count line. */
    public String format() {
        StringBuilder b = new StringBuilder();
        for (Diagnostic d : errors) b.append(d.format()).append('\n');
        for (String w : warnings) b.append("warning: ").append(w).append('\n');
        b.append(errors.size()).append(errors.size() == 1 ? " error, " : " errors, ")
                .append(warnings.size()).append(warnings.size() == 1 ? " warning" : " warnings").append('\n');
        return b.toString();
    }
}
