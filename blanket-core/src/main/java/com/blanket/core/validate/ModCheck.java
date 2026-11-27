// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.EventBinding;
import com.blanket.core.bindings.Features;
import com.blanket.core.bindings.Hooks;
import com.blanket.core.bindings.Lowering;
import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.lower.DeadCode;
import com.blanket.core.lower.Folding;
import com.blanket.core.lower.Reachability;
import com.blanket.core.mapping.Desc;
import com.blanket.core.plan.Lowered;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.scan.Scan;
import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

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
 * {@link TargetChecks} that {@link Validator} runs on the whole binding set (C2, C3, C7). With the mod's classes, the
 * uses of members that are unsupported on a target (an {@code unsupported} rule, or a {@code requires} rule whose
 * feature the target lacks) are judged as LOWER judges them: {@code Blanket.supports} is folded with the target's
 * features, dead code is removed, and a use left in reachable code is {@link ErrorCode#UNSUPPORTED}.
 *
 * <p>The binding set is narrowed first: a type file keeps only the member rule lists the mod uses (plus the types that
 * appear in the descriptors of used members, which {@code TargetChecks} needs for their aliases), an events file is kept
 * only for a used event or an event a used event goes {@code via}, hooks only when a kept event's recipe names them, glue
 * files only for the members a kept event passes as payload. The rule list of an aliased enum constant the mod reads is
 * kept like a method's.
 */
public final class ModCheck {
    private ModCheck() {}

    /** The fixed API owner of {@code supports} (lowering-and-adapters.md section 4.7). */
    private static final String BLANKET = "com/blanket/api/Blanket";

    /** One used API member and the member key of its type file (null when the file has none), by the keys of {@code narrowed}. */
    private record Need(TypeBinding type, Scan.ApiMember member, String key) {
        String label() {
            return Rules.simple(type.api()) + "." + (key != null ? key : member.name());
        }

        /** The plan's member key: {@code owner.name+desc}, or {@code owner.name:desc} for a field. */
        String memberKey() {
            return member.owner() + "." + member.name() + (member.isField() ? ":" : "") + member.desc();
        }
    }

    /**
     * As {@link #run(BindingSet, TargetData, ApiClasses, Scan, Map, List)} without the classes: the uses of unsupported
     * members are not judged. RESOLVE checks this way and leaves them to LOWER, which judges them per class.
     */
    public static Report run(BindingSet bindings, TargetData data, ApiClasses classes, Scan scan, List<TargetSpec> targets) throws IOException {
        return run(bindings, data, classes, scan, null, targets);
    }

    /**
     * Checks everything {@code scan} says the mod uses on every target. Every target is checked and all failures come
     * back together.
     *
     * @param classes where API classes are read from when the mod's own use does not give them (glue classes)
     * @param code    the scanned classes (internal name to class file, API names), or null not to judge the uses of
     *                unsupported members
     * @return the errors sorted by target and member, and the warnings
     * @throws IOException when game data cannot be obtained
     */
    public static Report run(BindingSet bindings, TargetData data, ApiClasses classes, Scan scan, Map<String, byte[]> code,
                             List<TargetSpec> targets) throws IOException {
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
            Map<String, Lowered> unsupported = coverage(narrowed, needs, scan, t, errors);
            new TargetChecks(narrowed, data, api, t, errors, warnings).run();
            if (code != null) reachability(code, bindings.features(), unsupported, t, errors);
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
                    // an aliased enum constant has a rule list under its name; any other field is reported by coverage
                    String key = tb.members().containsKey(m.name()) ? m.name() : null;
                    needs.add(new Need(tb, m, key));
                    if (key != null) keys.put(key, key);
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

        // a used event's file, and the file of every event one of its recipes goes via (binding-format.md section 7)
        Set<String> wanted = new TreeSet<>(scan.events());
        for (EventBinding eb : all.events().values()) {
            if (!scan.events().contains(eb.event())) continue;
            for (EventBinding.Recipe r : eb.recipes()) if (r.via() != null) wanted.add(r.via());
        }
        Map<String, EventBinding> events = new LinkedHashMap<>();
        for (Map.Entry<String, EventBinding> e : all.events().entrySet()) {
            if (wanted.contains(e.getValue().event())) events.put(e.getKey(), e.getValue());
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

    /**
     * C1 for the used set on {@code t}. Returns the used members that are unsupported there, by plan member key: an
     * {@code unsupported} rule, or a {@code requires} rule whose feature {@code t} lacks (binding-format.md section 4.3).
     */
    private static Map<String, Lowered> coverage(BindingSet narrowed, List<Need> needs, Scan scan, TargetSpec t, List<Diagnostic> errors) {
        Map<String, Lowered> unsupported = new HashMap<>();
        Features features = narrowed.features();
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
            if (m.isField() && n.key() == null) {
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
            TypeBinding.MemberRule rule = rules.get(i);
            String feature = rule.requires();
            if (feature != null && features.names().contains(feature) && !features.available(feature, t)) {
                unsupported.put(n.memberKey(), new Lowered.Unsupported(n.label() + " requires the feature " + feature + ", which "
                        + t.id() + " does not have", feature));
            } else if (rule.lower().kind() == Lowering.Kind.UNSUPPORTED) {
                unsupported.put(n.memberKey(), new Lowered.Unsupported(rule.lower().ref(), null));
            }
        }
        for (String event : scan.events()) {
            EventRecipes.Wired w = EventRecipes.wire(narrowed.events(), event, t, errors);
            if (w == null) continue;
            String label = "event " + event;
            for (EventBinding.Injection in : w.recipe().injections()) {
                String hook = in.hook();
                if (hook == null) continue;
                List<Hooks.Rule> rules = narrowed.hooks() == null ? null : narrowed.hooks().hooks().get(hook);
                if (rules == null) {
                    errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                            "C1 coverage: " + label + " uses hook '" + hook + "' on " + t.id() + ", which hooks.json does not define",
                            "add \"" + hook + "\": [ { \"when\": ..., \"method\": ..., \"at\": ... } ] under \"hooks\" in hooks.json"));
                } else if (Rules.first(rules, Hooks.Rule::when, t) < 0) {
                    errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                            "C1 coverage: hook '" + hook + "' (used by " + label + " in " + w.file() + ") has no rule for " + t.id(),
                            "add a rule whose \"when\" matches " + t.id() + " to hook \"" + hook + "\" in hooks.json, with its method written with "
                                    + Rules.refAdvice(t) + "; " + Rules.lookupCommand("<Minecraft class>#<method>")));
                }
            }
        }
        return unsupported;
    }

    // ---------------------------------------------------------------------------------------- reachability of unsupported uses

    /**
     * FOLD's passes on a copy of every class, in API names: {@code supports} folded with {@code t}'s features, dead code
     * removed, then each use of an {@code unsupported} member left in reachable code is reported (example 4 of
     * binding-format.md). Folding and dead-code removal run only on classes that call {@code supports}; nothing else can
     * become dead.
     */
    private static void reachability(Map<String, byte[]> code, Features features, Map<String, Lowered> unsupported, TargetSpec t,
                                     List<Diagnostic> errors) {
        Map<String, Boolean> available = new LinkedHashMap<>();
        for (String f : features.names()) available.put(f, features.available(f, t));
        for (byte[] bytes : code.values()) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            if (callsSupports(node)) {
                Folding.fold(node, available, t.id(), errors);
                DeadCode.remove(node);
            }
            if (!unsupported.isEmpty()) Reachability.check(node, unsupported, t.id(), features::range, errors);
        }
    }

    private static boolean callsSupports(ClassNode node) {
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof MethodInsnNode c && c.owner.equals(BLANKET) && c.name.equals("supports")) return true;
            }
        }
        return false;
    }
}
