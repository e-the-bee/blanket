// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.EventBinding;
import com.blanket.core.bindings.Hooks;
import com.blanket.core.bindings.Selector;
import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.resolve.TargetSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * C1 coverage (testing-strategy.md section 2): every public API class without {@code @Owned} has a type file, every
 * public API or glue member has a rule list (owned facades included; an alias enum's constants by name), every rule list
 * has a rule for every supported target, every event has a recipe on every supported target and every Mixin recipe a
 * hook rule. Pass-through members need no rule list (M2 plan section 3): members of {@code Object} and, for an enum,
 * of {@code Enum} plus {@code values()} and {@code valueOf(String)}. Also reports dead rules (reached by no supported
 * target) as warnings (binding-format.md section 9).
 */
final class Coverage {
    private final BindingSet bindings;
    private final List<TargetSpec> specs;
    private final ApiClasses api;
    private final List<Diagnostic> errors;
    private final List<String> warnings;

    Coverage(BindingSet bindings, List<TargetSpec> specs, ApiClasses api, List<Diagnostic> errors, List<String> warnings) {
        this.bindings = bindings;
        this.specs = specs;
        this.api = api;
        this.errors = errors;
        this.warnings = warnings;
    }

    void run() {
        apiClasses();
        Map<String, Set<String>> glueKeys = new HashMap<>();
        for (Map.Entry<String, TypeBinding> e : bindings.typesAndGlue().entrySet()) {
            boolean glue = e.getKey().startsWith("glue/");
            type(e.getKey(), e.getValue(), glue);
            if (glue) glueKeys.computeIfAbsent(Rules.simple(e.getValue().api()), k -> new HashSet<>()).addAll(e.getValue().members().keySet());
        }
        events(glueKeys);
        if (bindings.hooks() != null) {
            for (Map.Entry<String, List<Hooks.Rule>> h : bindings.hooks().hooks().entrySet()) {
                list("hooks.json", "hook " + h.getKey(), h.getValue(), Hooks.Rule::when, t -> { });
            }
        }
    }

    // ---------------------------------------------------------------------------------------- types and glue

    /** Every public class of the blanket-api jar that is not {@code @Owned} is an alias stub and needs a type file. */
    private void apiClasses() {
        Set<String> bound = new HashSet<>();
        for (TypeBinding tb : bindings.types().values()) bound.add(Rules.internal(tb.api()));
        for (String cls : api.apiJarClasses()) {
            Optional<ApiClasses.ClassInfo> info = api.classInfo(cls);
            if (info.isEmpty() || !info.get().isPublic() || info.get().owned() || bound.contains(cls)) continue;
            String simple = cls.substring(cls.lastIndexOf('/') + 1);
            errors.add(new Diagnostic(ErrorCode.UNBOUND, "*", simple, null, null,
                    "C1 coverage: the public API class " + cls.replace('/', '.') + " is not @Owned and has no type file",
                    "add types/" + simple + ".json with a representation rule for every supported target ("
                            + Rules.lookupCommand("<Minecraft class>") + "), or mark the class @Owned if Blanket ships its code"));
        }
    }

    private void type(String file, TypeBinding tb, boolean glue) {
        String simple = Rules.simple(tb.api());
        if (!glue && !tb.representation().isEmpty()) {
            list(file, "representation", tb.representation(), TypeBinding.Representation::when, t -> errors.add(new Diagnostic(
                    ErrorCode.UNBOUND, t.id(), simple + " (representation)", null, null,
                    "C1 coverage: " + file + " has no representation rule for " + t.id(),
                    "add a representation rule whose \"when\" matches " + t.id() + " to " + file + ": an `alias` written with "
                            + Rules.refAdvice(t) + " (see " + Rules.lookupCommand("<Minecraft class>") + ") or `owned`")));
        }
        Optional<List<ApiClasses.Method>> methods = api.methods(Rules.internal(tb.api()));
        if (methods.isEmpty()) {
            errors.add(new Diagnostic(ErrorCode.UNBOUND, "*", simple, null, null,
                    "C1 coverage: " + file + " binds " + tb.api() + ", which is not on the classpath",
                    "fix \"api\" in " + file + ", or build blanket-api and blanket-runtime first"));
            return;
        }
        // Members are bound on every target where the type is an alias or a facade; an owned representation needs none.
        List<TargetSpec> bound = new ArrayList<>();
        for (TargetSpec t : specs) {
            int ri = Rules.first(tb.representation(), TypeBinding.Representation::when, t);
            if (glue || tb.representation().isEmpty() || ri >= 0 && tb.representation().get(ri).owned() == null) bound.add(t);
        }
        ApiClasses.ClassInfo info = api.classInfo(Rules.internal(tb.api())).orElse(null);
        boolean isEnum = info != null && info.isEnum();
        Map<String, Integer> overloads = new HashMap<>();
        for (ApiClasses.Method m : methods.get()) overloads.merge(m.name(), 1, Integer::sum);
        Set<String> keys = new HashSet<>();
        for (ApiClasses.Method m : methods.get()) {
            boolean overloaded = overloads.get(m.name()) > 1;
            String key = overloaded ? m.name() + m.desc() : m.name();
            keys.add(key);
            List<TypeBinding.MemberRule> rules = tb.members().get(key);
            if (rules == null && passThrough(m, isEnum)) continue;
            String hint = overloaded ? " (the key carries the descriptor because " + m.name() + " is overloaded, binding-format.md 4.2)" : "";
            member(file, simple, key, rules, bound, hint);
        }
        // An alias enum's constants are read as fields; each has a rule list under its name (lowering-and-adapters.md 4.6).
        boolean alias = tb.representation().stream().anyMatch(r -> r.alias() != null);
        if (isEnum && alias) {
            for (String constant : info.enumConstants()) {
                keys.add(constant);
                member(file, simple, constant, tb.members().get(constant), bound, " (an alias enum constant: a `get` of the game enum's field)");
            }
        }
        for (String key : tb.members().keySet()) {
            if (!keys.contains(key)) {
                warnings.add(file + " member \"" + key + "\" matches no public member of " + tb.api() + " (stale rule list)");
            }
        }
    }

    /** One member's rule list: present when some target binds the type, with a rule for each such target. */
    private void member(String file, String simple, String key, List<TypeBinding.MemberRule> rules, List<TargetSpec> bound, String hint) {
        String label = simple + "." + key;
        if (rules == null) {
            if (bound.isEmpty()) return;
            errors.add(new Diagnostic(ErrorCode.UNBOUND, "*", label, null, null,
                    "C1 coverage: public member " + label + " has no rule list in " + file,
                    "add \"" + key + "\": [ { \"when\": {}, \"lower\": ... } ] under \"members\" in " + file + hint
                            + "; " + Rules.lookupCommand("<Minecraft class>#<member>") + " shows the names on every target"));
            return;
        }
        list(file, "member " + key, rules, TypeBinding.MemberRule::when, t -> {
            if (!bound.contains(t)) return;
            errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                    "C1 coverage: " + label + " has no rule for " + t.id() + " in " + file,
                    "add a rule whose \"when\" matches " + t.id() + " to member \"" + key + "\" in " + file + ", written with "
                            + Rules.refAdvice(t) + "; " + Rules.lookupCommand("<Minecraft class>#<member>")));
        });
    }

    /** Members of {@code java.lang.Object} and of {@code java.lang.Enum}, as {@code name+descriptor}. */
    private static final Set<String> OBJECT_MEMBERS = declared(Object.class), ENUM_MEMBERS = declared(Enum.class);

    private static Set<String> declared(Class<?> c) {
        Set<String> out = new HashSet<>();
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPrivate(m.getModifiers()) && !m.isSynthetic()) out.add(m.getName() + org.objectweb.asm.Type.getMethodDescriptor(m));
        }
        return Set.copyOf(out);
    }

    /** A pass-through member (M2 plan section 3, lowering-and-adapters.md 4.6): it stays a call on the real class. */
    static boolean passThrough(ApiClasses.Method m, boolean isEnum) {
        if (OBJECT_MEMBERS.contains(m.name() + m.desc())) return true;
        if (!isEnum) return false;
        return ENUM_MEMBERS.contains(m.name() + m.desc())
                || m.isStatic() && m.name().equals("values") && m.desc().startsWith("()[")
                || m.isStatic() && m.name().equals("valueOf") && m.desc().startsWith("(Ljava/lang/String;)");
    }

    // ---------------------------------------------------------------------------------------- events and hooks

    private void events(Map<String, Set<String>> glueKeys) {
        for (Map.Entry<String, EventBinding> e : bindings.events().entrySet()) {
            String file = e.getKey();
            EventBinding eb = e.getValue();
            String label = "event " + eb.event();
            boolean[] reached = new boolean[eb.recipes().size()];
            for (TargetSpec t : specs) {
                int i = Rules.first(eb.recipes(), EventBinding.Recipe::when, t);
                if (i < 0) {
                    errors.add(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                            "C1 coverage: " + label + " has no recipe for " + t.id() + " in " + file,
                            "add a recipe whose \"when\" matches " + t.id() + " to " + file + ": a `mixin` on a hook of hooks.json, or a `native` loader event (contributing.md 3.3)"));
                    continue;
                }
                reached[i] = true;
                for (EventBinding.Injection in : eb.recipes().get(i).injections()) {
                    if (in.hook() != null) hookRule(t, label, file, in.hook());
                }
            }
            for (int i = 0; i < reached.length; i++) {
                if (!reached[i]) warnings.add(file + " recipe " + i + " " + eb.recipes().get(i).when() + " is reached by no supported target (dead rule)");
            }
            Set<String> seen = new HashSet<>();
            for (EventBinding.Recipe r : eb.recipes()) {
                for (EventBinding.Injection in : r.injections()) {
                    for (EventBinding.Source s : in.payload().values()) glueSource(file, label, s, glueKeys, seen);
                    for (EventBinding.Source s : in.before()) glueSource(file, label, s, glueKeys, seen);
                }
            }
        }
    }

    private void hookRule(TargetSpec t, String label, String file, String hook) {
        List<Hooks.Rule> rules = bindings.hooks() == null ? null : bindings.hooks().hooks().get(hook);
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

    /** A glue payload source names a glue member that a glue file must bind (D-054). */
    private void glueSource(String file, String label, EventBinding.Source s, Map<String, Set<String>> glueKeys, Set<String> seen) {
        if (!s.kind().equals("glue")) return;
        String ref = s.value().getAsString();
        if (seen.add(ref)) {
            int hash = ref.indexOf('#');
            String type = ref.substring(0, hash);
            String member = ref.substring(hash + 1);
            Set<String> keys = glueKeys.getOrDefault(type, Set.of());
            boolean found = keys.contains(member)
                    || (member.indexOf('(') < 0 && keys.stream().anyMatch(k -> k.startsWith(member + "(")))
                    || (member.indexOf('(') >= 0 && keys.contains(member.substring(0, member.indexOf('('))));
            if (!found) {
                errors.add(new Diagnostic(ErrorCode.UNBOUND, "*", label, ref, null,
                        "C1 coverage: " + label + " in " + file + " calls glue member " + ref + ", which no glue file binds",
                        "add \"" + member + "\" to the glue file of " + type + " (glue/" + type + ".json) with a rule for every supported target"));
            }
        }
        for (EventBinding.Source arg : s.args()) glueSource(file, label, arg, glueKeys, seen);
    }

    // ---------------------------------------------------------------------------------------- lists

    /** Calls {@code uncovered} for each supported target no rule matches, and warns about rules no target reaches. */
    private <R> void list(String file, String what, List<R> rules, Function<R, Selector> when, Consumer<TargetSpec> uncovered) {
        boolean[] reached = new boolean[rules.size()];
        for (TargetSpec t : specs) {
            int i = Rules.first(rules, when, t);
            if (i < 0) uncovered.accept(t);
            else reached[i] = true;
        }
        for (int i = 0; i < reached.length; i++) {
            if (!reached[i]) warnings.add(file + " " + what + " rule " + i + " " + when.apply(rules.get(i)) + " is reached by no supported target (dead rule)");
        }
    }
}
