// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import java.util.Objects;

/**
 * One validation failure (binding-format.md section 2.5). Every failure carries the target and ends with a next step.
 *
 * @param code          the error code
 * @param target        {@code loader:mc}, or {@code *} when the failure does not belong to one target
 * @param member        what failed: an API or glue member ({@code Player.x}), a hook id, an event, or a mapping file
 * @param reference     the reference that failed, or null
 * @param namespaceStep the resolver step that failed ({@code file|from|to}, {@code route}, {@code jar}), or null
 * @param detail        what is wrong, beginning with the check ({@code C2 resolution: ...})
 * @param nextStep      what to do about it; never empty
 */
public record Diagnostic(ErrorCode code, String target, String member, String reference, String namespaceStep,
                         String detail, String nextStep) {

    public Diagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(detail, "detail");
        if (nextStep == null || nextStep.isBlank()) {
            throw new IllegalArgumentException("a diagnostic needs a next step: " + code + " " + target + " " + member);
        }
    }

    /** One human line: {@code CODE target member [ref ...] [at step]: detail; next step: ...}. */
    public String format() {
        StringBuilder b = new StringBuilder();
        b.append(code).append(' ').append(target).append(' ').append(member);
        if (reference != null) b.append(" ref ").append(reference);
        if (namespaceStep != null) b.append(" at ").append(namespaceStep);
        b.append(": ").append(oneLine(detail)).append("; next step: ").append(oneLine(nextStep));
        return b.toString();
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s*\\R\\s*", " ").trim();
    }

    @Override
    public String toString() {
        return format();
    }
}
