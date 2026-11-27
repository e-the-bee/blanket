// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;
import com.blanket.core.bindings.EventBinding;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.scan.Scan;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which recipe delivers an event on a target (binding-format.md section 7): the event's first matching recipe, or, when
 * that recipe is {@code via}, the named event's first matching recipe. The named event's recipe must be a Mixin or native
 * one: a via chain or cycle is a C1 error. Shared by C1 ({@link Coverage}, {@link ModCheck}) and the planner.
 */
public final class EventRecipes {
    private EventRecipes() {}

    /**
     * The recipe whose hooks deliver an event on one target.
     *
     * @param event  the event whose hooks these are: the used event itself, or the one it goes via
     * @param file   the events file of {@code event}
     * @param recipe a Mixin or native recipe
     */
    public record Wired(String event, String file, EventBinding.Recipe recipe) {}

    /** The events file that binds {@code event} (file name to binding), or null when none does. */
    public static Map.Entry<String, EventBinding> file(Map<String, EventBinding> events, String event) {
        Map.Entry<String, EventBinding> found = null;
        for (Map.Entry<String, EventBinding> e : events.entrySet()) {
            if (e.getValue().event().equals(event)) found = e;
        }
        return found;
    }

    /**
     * The events whose recipes a mod wires: the {@code Events} constants it reads, and every hand-over whose
     * {@code usedBy} API method it calls ({@code KEY_HANDOVER} for {@code KeyBinding.register}). Sorted.
     */
    public static Set<String> used(Map<String, EventBinding> events, Scan scan) {
        Set<String> out = new TreeSet<>(scan.events());
        for (EventBinding eb : events.values()) {
            if (eb.usedBy() == null) continue;
            for (Scan.ApiMember m : scan.members()) {
                if (!m.isField() && eb.usedBy(m.owner(), m.name())) out.add(eb.event());
            }
        }
        return out;
    }

    /**
     * The recipe that delivers {@code event} on {@code t}, following one {@code via}; null after adding the C1 error to
     * {@code errors} when there is none.
     */
    public static Wired wire(Map<String, EventBinding> events, String event, TargetSpec t, List<Diagnostic> errors) {
        String label = "event " + event;
        Map.Entry<String, EventBinding> f = file(events, event);
        if (f == null) {
            errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                    "C1 coverage: no events/ file binds " + label,
                    "add an events file for " + event + " with a recipe whose \"when\" matches " + t.id() + " (contributing.md 3.3)"));
            return null;
        }
        int i = Rules.first(f.getValue().recipes(), EventBinding.Recipe::when, t);
        if (i < 0) {
            errors.add(noRecipe(label, f.getKey(), t));
            return null;
        }
        EventBinding.Recipe r = f.getValue().recipes().get(i);
        if (r.via() == null) return new Wired(event, f.getKey(), r);
        return via(events, event, f.getKey(), r.via(), t, errors);
    }

    /**
     * The recipe of {@code via} on {@code t}, which a via recipe of {@code event} in {@code file} names; null after adding
     * the C1 error when {@code via} has no events file, no recipe for {@code t}, or a via recipe there (a chain or cycle).
     */
    static Wired via(Map<String, EventBinding> events, String event, String file, String via, TargetSpec t, List<Diagnostic> errors) {
        String label = "event " + event;
        Map.Entry<String, EventBinding> f = file(events, via);
        if (f == null) {
            errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                    "C1 coverage: " + label + " goes via " + via + " on " + t.id() + " (" + file + "), which no events/ file binds",
                    "add an events file for " + via + " or name another event in \"via\""));
            return null;
        }
        int i = Rules.first(f.getValue().recipes(), EventBinding.Recipe::when, t);
        if (i < 0) {
            errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                    "C1 coverage: " + label + " goes via " + via + " on " + t.id() + " (" + file + "), which has no recipe for " + t.id() + " in " + f.getKey(),
                    "add a recipe whose \"when\" matches " + t.id() + " to " + f.getKey()));
            return null;
        }
        EventBinding.Recipe r = f.getValue().recipes().get(i);
        if (r.via() != null) {
            errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                    "C1 coverage: " + label + " goes via " + via + " on " + t.id() + " (" + file + "), whose recipe there goes via " + r.via()
                            + ": a via chain or cycle",
                    "make " + label + " go via an event with a `mixin` or `native` recipe on " + t.id()));
            return null;
        }
        return new Wired(via, f.getKey(), r);
    }

    static Diagnostic noRecipe(String label, String file, TargetSpec t) {
        return new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                "C1 coverage: " + label + " has no recipe for " + t.id() + " in " + file,
                "add a recipe whose \"when\" matches " + t.id() + " to " + file + ": a `mixin` on a hook of hooks.json, a `native` loader event,"
                        + " or `via` another event (contributing.md 3.3)");
    }
}
