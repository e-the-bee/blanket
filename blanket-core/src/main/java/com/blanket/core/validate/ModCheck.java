// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.EventBinding;
import com.blanket.core.bindings.Hooks;
import com.blanket.core.bindings.Lowering;
import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.mapping.Desc;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.scan.Scan;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code blanket check}: validates only what one mod uses. The used API members and events come from {@link Scan}; for each
 * target the first matching rule of each of them is checked for coverage ({@link ErrorCode#UNBOUND}), and then the
 * references of those rules, the shape of the lowerings and the hooks of the used events go through the same
 * {@link TargetChecks} that {@link Validator} runs on the whole binding set (C2, C3, C7).
 *
 * <p>The binding set is narrowed first: a type file keeps only the member rule lists the mod uses (plus the types that
 * appear in the descriptors of used members, which {@code TargetChecks} needs for their aliases), an events file is kept
 * only for a used event, hooks only when a used event's recipe names them, glue files only for the members a used event
 * passes as payload.
 */
public final class ModCheck {
    private ModCheck() {}

    /** One used API member and the member key of its type file (null when the file has none), by the keys of {@code narrowed}. */
    private record Need(TypeBinding type, Scan.ApiMember member, String key) {
        String label() {
            return Rules.simple(type.api()) + "." + (key != null ? key : member.name());
        }
    }

    /**
     * Checks everything {@code scan} says the mod uses on every target. Every target is checked and all failures come
     * back together.
     *
     * @param classes where API classes are read from when the mod's own use does not give them (glue classes)
     * @return the errors sorted by target and member, and the warnings
     * @throws IOException when game data cannot be obtained
     */
    public static Report run(BindingSet bindings, TargetData data, ApiClasses classes, Scan scan, List<TargetSpec> targets) throws IOException {
        List<Need> needs = new ArrayList<>();
        BindingSet narrowed = narrow(bindings, scan, needs);
        Map<String, List<ApiClasses.Method>> used = new HashMap<>();
        for (Scan.ApiMember m : scan.members()) {
            if (!m.isField()) used.computeIfAbsent(m.owner(), k -> new ArrayList<>()).add(new ApiClasses.Method(m.name(), m.desc(), m.isStatic()));
        }
        ApiClasses api = owner -> used.containsKey(owner) ? Optional.of(used.get(owner)) : classes.methods(owner);

        List<Diagnostic> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (TargetSpec t : targets) {
            coverage(narrowed, needs, scan, t, errors);
            new TargetChecks(narrowed, data, api, t, errors, warnings).run();
        }
        errors.sort(Comparator.comparing(Diagnostic::target).thenComparing(Diagnostic::member)
                .thenComparing(d -> d.code().ordinal())
                .thenComparing(d -> d.reference() == null ? "" : d.reference()));
        return new Report(new ArrayList<>(new LinkedHashSet<>(errors)), new ArrayList<>(new LinkedHashSet<>(warnings)));
    }

    // ---------------------------------------------------------------------------------------- narrowing

    /** The binding set reduced to what the scan uses; fills {@code needs} with the used members of bound types. */
    private static BindingSet narrow(BindingSet all, Scan scan, List<Need> needs) {
        // The overloads of a name that the mod uses: more than one means the member key carries the descriptor (4.2).
        Map<String, Set<String>> descsByName = new HashMap<>();
        for (Scan.ApiMember m : scan.members()) {
            if (!m.isField()) descsByName.computeIfAbsent(m.owner() + "." + m.name(), k -> new HashSet<>()).add(m.desc());
        }

        // Type file -> member key as the narrowed copy names it -> rule list as the original names it.
        Map<String, Map<String, String>> keep = new LinkedHashMap<>();
        for (Map.Entry<String, TypeBinding> e : all.types().entrySet()) {
            TypeBinding tb = e.getValue();
            String owner = Rules.internal(tb.api());
            Map<String, String> keys = new LinkedHashMap<>();
            boolean wanted = false;
            for (Scan.ApiMember m : scan.members()) {
                if (m.owner().equals(owner)) wanted = true;
                if (!m.owner().equals(owner)) {
                    if (!m.isField() && Desc.classesIn(m.desc()).contains(owner)) wanted = true;
                    continue;
                }
                if (m.isField()) {
                    needs.add(new Need(tb, m, null));
                    continue;
                }
                boolean overloaded = descsByName.get(owner + "." + m.name()).size() > 1;
                String key = null;
                if (tb.members().containsKey(m.name() + m.desc())) key = m.name() + m.desc();
                else if (!overloaded && tb.members().containsKey(m.name())) key = m.name();
                needs.add(new Need(tb, m, key));
                if (key != null) keys.put(overloaded ? m.name() + m.desc() : m.name(), key);
            }
            if (wanted) keep.put(e.getKey(), keys);
        }
        Map<String, TypeBinding> types = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : keep.entrySet()) {
            types.put(e.getKey(), only(all.types().get(e.getKey()), e.getValue()));
        }

        Map<String, EventBinding> events = new LinkedHashMap<>();
        for (Map.Entry<String, EventBinding> e : all.events().entrySet()) {
            if (scan.events().contains(e.getValue().event())) events.put(e.getKey(), e.getValue());
        }

        Set<String> hookIds = new TreeSet<>();
        Map<String, Set<String>> glueRefs = new TreeMap<>();   // glue type simple name -> member texts
        for (EventBinding eb : events.values()) {
            for (EventBinding.Recipe r : eb.recipes()) {
                for (EventBinding.Injection in : r.injections()) {
                    if (in.hook() != null) hookIds.add(in.hook());
                    for (EventBinding.Source s : in.payload().values()) glueSources(s, glueRefs);
                    for (EventBinding.Source s : in.before()) glueSources(s, glueRefs);
                }
            }
        }
        Hooks hooks = null;
        if (all.hooks() != null && !hookIds.isEmpty()) {
            JsonObject json = all.hooks().toJson();
            JsonObject kept = new JsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> h : json.getAsJsonObject("hooks").entrySet()) {
                if (hookIds.contains(h.getKey())) kept.add(h.getKey(), h.getValue());
            }
            json.add("hooks", kept);
            hooks = Hooks.parse(json, "hooks.json");
        }

        Map<String, TypeBinding> glue = new LinkedHashMap<>();
        for (Map.Entry<String, TypeBinding> e : all.glue().entrySet()) {
            Set<String> refs = glueRefs.get(Rules.simple(e.getValue().api()));
            if (refs == null) continue;
            Map<String, String> keys = new LinkedHashMap<>();
            for (String key : e.getValue().members().keySet()) {
                for (String ref : refs) {
                    if (key.equals(ref) || (ref.indexOf('(') < 0 && key.startsWith(ref + "("))) keys.put(key, key);
                }
            }
            glue.put(e.getKey(), only(e.getValue(), keys));
        }
        return new BindingSet(all.dir(), all.targets(), types, glue, hooks, events, all.features());
    }

    /** {@code type} keeping only the rule lists in {@code keys} (new key to old key). */
    private static TypeBinding only(TypeBinding type, Map<String, String> keys) {
        JsonObject json = type.toJson();
        JsonObject old = json.has("members") ? json.getAsJsonObject("members") : new JsonObject();
        json.remove("members");
        if (!keys.isEmpty()) {
            JsonObject members = new JsonObject();
            for (Map.Entry<String, String> k : keys.entrySet()) members.add(k.getKey(), old.get(k.getValue()));
            json.add("members", members);
        }
        return TypeBinding.parse(json, type.file());
    }

    /** Glue members a payload source calls: {@code GlueType#member} texts by the glue type's simple name. */
    private static void glueSources(EventBinding.Source s, Map<String, Set<String>> out) {
        if (s.kind().equals("glue")) {
            String ref = s.value().getAsString();
            int hash = ref.indexOf('#');
            out.computeIfAbsent(ref.substring(0, hash), k -> new TreeSet<>()).add(ref.substring(hash + 1));
        }
        for (EventBinding.Source arg : s.args()) glueSources(arg, out);
    }

    // ---------------------------------------------------------------------------------------- coverage of the used set

    private static void coverage(BindingSet narrowed, List<Need> needs, Scan scan, TargetSpec t, List<Diagnostic> errors) {
        Set<String> noRepresentation = new HashSet<>();
        for (Need n : needs) {
            TypeBinding tb = n.type();
            String simple = Rules.simple(tb.api());
            int ri = Rules.first(tb.representation(), TypeBinding.Representation::when, t);
            if (ri < 0) {
                if (noRepresentation.add(tb.file())) {
                    errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), simple + " (representation)", null, null,
                            "C1 coverage: " + tb.file() + " has no representation rule for " + t.id(),
                            "add a representation rule whose \"when\" matches " + t.id() + " to " + tb.file() + ": an `alias` written with "
                                    + Rules.refAdvice(t) + " (see " + Rules.lookupCommand("<Minecraft class>") + ") or `owned`"));
                }
                continue;
            }
            if (tb.representation().get(ri).owned() != null) continue;   // an owned type needs no member rules (4.1)
            Scan.ApiMember m = n.member();
            if (m.isField()) {
                errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), simple + "." + m.name(), null, null,
                        "C1 coverage: the mod reads the field " + m.owner() + "." + m.name() + ", but API types have accessors only",
                        "use the accessor method of " + simple + " instead of the field"));
                continue;
            }
            if (n.key() == null) {
                errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), n.label(), null, null,
                        "C1 coverage: used member " + n.label() + " has no rule list in " + tb.file(),
                        "add \"" + m.name() + "\": [ { \"when\": {}, \"lower\": ... } ] under \"members\" in " + tb.file()
                                + "; " + Rules.lookupCommand("<Minecraft class>#<member>") + " shows the names on every target"));
                continue;
            }
            List<TypeBinding.MemberRule> rules = tb.members().get(n.key());
            int i = Rules.first(rules, TypeBinding.MemberRule::when, t);
            if (i < 0) {
                errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), n.label(), null, null,
                        "C1 coverage: " + n.label() + " has no rule for " + t.id() + " in " + tb.file(),
                        "add a rule whose \"when\" matches " + t.id() + " to member \"" + n.key() + "\" in " + tb.file() + ", written with "
                                + Rules.refAdvice(t) + "; " + Rules.lookupCommand("<Minecraft class>#<member>")));
                continue;
            }
            Lowering lower = rules.get(i).lower();
            if (lower.kind() == Lowering.Kind.UNSUPPORTED) {
                errors.add(new Diagnostic(ErrorCode.UNSUPPORTED, t.id(), n.label(), null, null,
                        "C1 coverage: " + tb.file() + " member \"" + n.key() + "\" rule " + i + " marks this member unsupported on " + t.id() + ": " + lower.ref(),
                        "do not use " + n.label() + " on " + t.id() + ", or bind it there"));
            }
        }
        for (String event : scan.events()) {
            String label = "event " + event;
            EventBinding eb = null;
            String file = null;
            for (Map.Entry<String, EventBinding> e : narrowed.events().entrySet()) {
                if (e.getValue().event().equals(event)) {
                    eb = e.getValue();
                    file = e.getKey();
                }
            }
            if (eb == null) {
                errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                        "C1 coverage: no events/ file binds " + label,
                        "add an events file for " + event + " with a recipe whose \"when\" matches " + t.id() + " (contributing.md 3.3)"));
                continue;
            }
            int i = Rules.first(eb.recipes(), EventBinding.Recipe::when, t);
            if (i < 0) {
                errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                        "C1 coverage: " + label + " has no recipe for " + t.id() + " in " + file,
                        "add a recipe whose \"when\" matches " + t.id() + " to " + file + ": a `mixin` on a hook of hooks.json, or a `native` loader event (contributing.md 3.3)"));
                continue;
            }
            for (EventBinding.Injection in : eb.recipes().get(i).injections()) {
                String hook = in.hook();
                if (hook == null) continue;
                List<Hooks.Rule> rules = narrowed.hooks() == null ? null : narrowed.hooks().hooks().get(hook);
                if (rules == null) {
                    errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                            "C1 coverage: " + label + " uses hook '" + hook + "' on " + t.id() + ", which hooks.json does not define",
                            "add \"" + hook + "\": [ { \"when\": ..., \"method\": ..., \"at\": ... } ] under \"hooks\" in hooks.json"));
                } else if (Rules.first(rules, Hooks.Rule::when, t) < 0) {
                    errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                            "C1 coverage: hook '" + hook + "' (used by " + label + " in " + file + ") has no rule for " + t.id(),
                            "add a rule whose \"when\" matches " + t.id() + " to hook \"" + hook + "\" in hooks.json, with its method written with "
                                    + Rules.refAdvice(t) + "; " + Rules.lookupCommand("<Minecraft class>#<method>")));
                }
            }
        }
    }
}
