// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.Selector;
import com.blanket.core.bindings.Version;
import com.blanket.core.resolve.TargetSpec;

import java.util.List;
import java.util.function.Function;

/** Rule-list helpers shared by the checks: first match wins (binding-format.md section 2.2). */
final class Rules {
    private Rules() {}

    static boolean matches(Selector s, TargetSpec t) {
        return s.matches(t.loader(), Version.parse(t.mc()));
    }

    /** The index of the first rule that matches the target, or -1. */
    static <R> int first(List<R> rules, Function<R, Selector> when, TargetSpec t) {
        for (int i = 0; i < rules.size(); i++) {
            if (matches(when.apply(rules.get(i)), t)) return i;
        }
        return -1;
    }

    /** {@code com.blanket.api.Player} to {@code Player}. */
    static String simple(String dotted) {
        return dotted.substring(Math.max(dotted.lastIndexOf('.'), dotted.lastIndexOf('/')) + 1);
    }

    static String internal(String dotted) {
        return dotted.replace('.', '/');
    }

    /** How to write a reference for this target, for next-step hints. */
    static String refAdvice(TargetSpec t) {
        if (t.hasLegacyIntermediary()) return "an `lf:` reference (Legacy Fabric ids)";
        if (t.isOfficial()) return "an `official:` reference";
        return "a Mojang-named reference";
    }

    /** {@code ./gradlew lookup --ref "..."} with the reference quoted. */
    static String lookupCommand(String ref) {
        return "`./gradlew lookup --ref \"" + ref.replace("\"", "\\\"") + "\"`";
    }
}
