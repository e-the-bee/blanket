// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.BindingException;
import com.blanket.core.bindings.EventBinding;
import com.blanket.core.bindings.Hooks;
import com.blanket.core.bindings.Lowering;
import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.bindings.Version;
import com.blanket.core.resolve.ClassIndex;
import com.blanket.core.resolve.GameClasses;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Optional;

/**
 * The checks that need one target's data: C2 resolution (every reference of every rule that applies to the target
 * resolves, and is in the client jar), C3 shape, and C7 hooks (hook methods and call sites, native event classes, Mixin
 * recipes only where Mixin exists).
 */
final class TargetChecks {
    private static final String C2 = "C2 resolution";
    private static final String C7 = "C7 hooks";

    /** One reference of one applicable rule. {@code site} says where to fix it. */
    private record Use(String member, String site, String check, Ref ref, String anchor) {}

    private final BindingSet bindings;
    private final TargetData data;
    private final ApiClasses api;
    private final TargetSpec t;
    private final List<Diagnostic> errors;
    private final List<String> warnings;
    private final List<Use> uses = new ArrayList<>();
    private final GameClasses game;

    TargetChecks(BindingSet bindings, TargetData data, ApiClasses api, TargetSpec target, List<Diagnostic> errors, List<String> warnings) {
        this.bindings = bindings;
        this.data = data;
        this.api = api;
        this.t = target;
        this.errors = errors;
        this.warnings = warnings;
        this.game = bindings.targets().gameClasses();
    }

    void run() throws IOException {
        Map<String, Hooks.Rule> hookRules = new LinkedHashMap<>();
        collectTypes();
        collectHooks(hookRules);
        collectEvents();

        Resolver.MappingFiles files = data.mappingFiles();
        Resolution named = Resolution.of(t, group(uses, null), files, game);
        boolean sameNames = t.runtime().equals("obf") || t.runtime().equals("official");
        Resolution jarNames = sameNames ? named : Resolution.of(Resolution.jarNames(t), group(uses, named), files, game);

        List<Path> jars = new ArrayList<>(data.classpath(t));
        jars.addAll(data.libraryJars(t, libraryOwners(jarNames)));
        try (ClassIndex index = ClassIndex.open(jars, true)) {
            references(named, jarNames, index);
            shapes(jarNames, index);
            callSites(hookRules, jarNames, jars);
        }
    }

    // ---------------------------------------------------------------------------------------- what applies

    private void collectTypes() {
        for (Map.Entry<String, TypeBinding> e : bindings.typesAndGlue().entrySet()) {
            String file = e.getKey();
            TypeBinding tb = e.getValue();
            String simple = Rules.simple(tb.api());
            String anchor = anchorOf(tb);
            int ri = Rules.first(tb.representation(), TypeBinding.Representation::when, t);
            if (ri >= 0 && tb.representation().get(ri).alias() != null) {
                use(simple + " (representation)", file + " representation", C2, tb.representation().get(ri).alias(), anchor);
            }
            for (Map.Entry<String, List<TypeBinding.MemberRule>> m : tb.members().entrySet()) {
                int i = Rules.first(m.getValue(), TypeBinding.MemberRule::when, t);
                if (i < 0) continue;
                List<String> refs = new ArrayList<>();
                loweringRefs(m.getValue().get(i).lower(), refs);
                for (String r : refs) use(simple + "." + m.getKey(), file + " member \"" + m.getKey() + "\"", C2, r, anchor);
            }
        }
    }

    private void collectHooks(Map<String, Hooks.Rule> selected) {
        if (bindings.hooks() == null) return;
        for (Map.Entry<String, List<Hooks.Rule>> h : bindings.hooks().hooks().entrySet()) {
            int i = Rules.first(h.getValue(), Hooks.Rule::when, t);
            if (i < 0) continue;
            Hooks.Rule rule = h.getValue().get(i);
            selected.put(h.getKey(), rule);
            String site = "hooks.json hook \"" + h.getKey() + "\"";
            use("hook " + h.getKey(), site, C7, rule.method(), bindings.targets().anchor());
            Hooks.At.Kind kind = rule.at().kind();
            if (kind == Hooks.At.Kind.INVOKE || kind == Hooks.At.Kind.INVOKE_STRING) {
                use("hook " + h.getKey(), site, C7, rule.at().method(), bindings.targets().anchor());
            }
        }
    }

    private void collectEvents() {
        for (Map.Entry<String, EventBinding> e : bindings.events().entrySet()) {
            EventBinding eb = e.getValue();
            int i = Rules.first(eb.recipes(), EventBinding.Recipe::when, t);
            if (i < 0) continue;
            EventBinding.Recipe r = eb.recipes().get(i);
            String label = "event " + eb.event();
            if (r.isMixin() && !hasMixin(t)) {
                errors.add(new Diagnostic(ErrorCode.UNSUPPORTED, t.id(), label, null, null,
                        C7 + ": " + label + " uses a Mixin recipe (hook '" + r.injections().get(0).hook() + "') on " + t.id() + ", an era without Mixin",
                        "give " + t.id() + " a `native` recipe in " + e.getKey() + " ahead of the Mixin recipe: legacy Forge and Forge before 1.15.2 have no Mixin"));
            }
            String anchor = bindings.targets().anchor();
            for (EventBinding.Injection in : r.injections()) {
                if (in.nativeEvent() != null) use(label, e.getKey(), C7, in.nativeEvent(), anchor);
                for (Map.Entry<String, String> f : in.filter().entrySet()) {
                    use(label, e.getKey(), C2, f.getKey(), anchor);
                    use(label, e.getKey(), C2, f.getValue(), anchor);
                }
                for (EventBinding.Source s : in.payload().values()) payloadRefs(label, e.getKey(), s);
                for (EventBinding.Source s : in.before()) payloadRefs(label, e.getKey(), s);
            }
        }
    }

    private void payloadRefs(String label, String file, EventBinding.Source s) {
        String anchor = bindings.targets().anchor();
        if (s.kind().equals("this")) {
            use(label, file, C2, s.value().getAsString(), anchor);
        } else if (s.kind().equals("getter") && s.value().getAsString().indexOf('#') >= 0) {
            use(label, file, C2, s.value().getAsString(), anchor);
        }
        for (EventBinding.Source arg : s.args()) payloadRefs(label, file, arg);
    }

    /** Mixin needs Mixin on the era: not legacy Forge, not Forge before 1.15.2 (testing-strategy.md C7). */
    static boolean hasMixin(TargetSpec t) {
        if (t.emitter().equals("forge-legacy")) return false;
        return !(t.loader().equals("forge") && Version.parse(t.mc()).compareTo(Version.parse("1.15.2")) < 0);
    }

    private String anchorOf(TypeBinding tb) {
        return tb.anchor() != null ? tb.anchor() : bindings.targets().anchor();
    }

    private static void loweringRefs(Lowering l, List<String> out) {
        switch (l.kind()) {
            case INVOKE:
            case GET:
            case PUT:
            case NEW:
            case CAST:
                out.add(l.ref());
                break;
            case CHAIN:
                for (Lowering s : l.steps()) loweringRefs(s, out);
                break;
            default:
                break;
        }
    }

    /** Parses a reference into a use; a malformed one is an error right away. */
    private void use(String member, String site, String check, String text, String anchor) {
        try {
            uses.add(new Use(member, site, check, Ref.parse(text, site, "$"), anchor));
        } catch (BindingException e) {
            errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), member, text, "parse", check + ": " + e.getMessage(),
                    "write the reference as [ns:]class#member with a descriptor (binding-format.md 2.3) in " + site));
        }
    }

    /** Anchor to the distinct references of the uses; with {@code only}, just those that resolved there. */
    private static Map<String, List<Ref>> group(List<Use> uses, Resolution only) {
        Map<String, Map<String, Ref>> by = new LinkedHashMap<>();
        for (Use u : uses) {
            if (only != null && only.resolved(u.anchor(), u.ref().text()) == null) continue;
            by.computeIfAbsent(u.anchor(), k -> new LinkedHashMap<>()).putIfAbsent(u.ref().text(), u.ref());
        }
        Map<String, List<Ref>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Ref>> e : by.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        return out;
    }

    // ---------------------------------------------------------------------------------------- C2 and C7 references

    private void references(Resolution named, Resolution jarNames, ClassIndex index) {
        for (Use u : uses) {
            String text = u.ref().text();
            Resolver.Problem p = named.problem(u.anchor(), text);
            if (p != null) {
                boolean route = p.step().equals("route") || p.step().equals("mapping file");
                errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), u.member(), text, p.step(), u.check() + ": " + p.message(),
                        route ? "write " + u.site() + " for " + t.id() + " with " + Rules.refAdvice(t) + " (binding-format.md 2.4); " + Rules.lookupCommand(text)
                                + " shows what each target needs"
                                : "run " + Rules.lookupCommand(text) + " for the failing step and candidates, then fix " + u.site()));
                continue;
            }
            boolean loader = u.ref().ns().equals("loader");
            // A loader jar is compiled against the runtime names of its era, not the client jar's names
            Resolution used = loader ? named : jarNames;
            Resolver.Resolved r = used.resolved(u.anchor(), text);
            if (r == null) {
                // named had no problem for this use, so the other resolution must have one; if neither does, say so
                Resolver.Problem unresolved = used.problem(u.anchor(), text);
                errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), u.member(), text, unresolved != null ? unresolved.step() : "resolve",
                        u.check() + ": " + (unresolved != null ? unresolved.message() : "the reference did not resolve and no step reported why"),
                        "run " + Rules.lookupCommand(text) + " for the failing step and candidates, then fix " + u.site()));
                continue;
            }
            String missing = missingFromJar(index, r);
            if (missing == null) continue;
            if (!loader && isLibraryClass(r.owner()) && !data.coversLibraries(t)) continue;   // JOML, LWJGL and friends ship beside the client jar, not in it
            if (loader && !data.hasLoaderJar(t)) {
                errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), u.member(), text, "loader-jar", u.check() + ": " + missing
                        + "; " + t.id() + " has no loader jar, so the loader reference cannot be checked",
                        "add the loader of " + t.id() + " to loader-artifacts.json (binding-format.md 7), or write " + u.site() + " without a loader reference for " + t.id()));
                continue;
            }
            errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), u.member(), text, "jar", u.check() + ": " + missing,
                    "the mapping files name it but the " + t.mc() + " jar does not have it: reference members on their declaring class (binding-format.md 2.3), "
                            + "or fix " + u.site() + "; " + Rules.lookupCommand(text) + " shows the resolved names"));
        }
    }

    /** The library classes the applicable references name, in jar names: the classes whose library jar the index needs. */
    private Set<String> libraryOwners(Resolution jarNames) {
        Set<String> owners = new TreeSet<>();
        for (Use u : uses) {
            if (u.ref().ns().equals("loader")) continue;
            Resolver.Resolved r = jarNames.resolved(u.anchor(), u.ref().text());
            if (r != null && isLibraryClass(r.owner())) owners.add(r.owner());
        }
        return owners;
    }

    /** What is missing from the jar for a resolved (jar-named) reference, or null when it is there. */
    private String missingFromJar(ClassIndex index, Resolver.Resolved r) {
        String where = isLibraryClass(r.owner()) ? "the " + t.mc() + " jar or its libraries" : "the " + t.mc() + " jar";
        Optional<ClassIndex.Info> info = index.get(r.owner());
        if (info.isEmpty()) return "class " + r.owner() + " is not in " + where;
        if (r.name() == null) return null;
        boolean present = r.field() ? info.get().fields().containsKey(r.name() + ":" + r.desc())
                : info.get().methods().containsKey(r.name() + r.desc());
        if (present) return null;
        return (r.field() ? "field " : "method ") + r.owner() + "." + r.name() + (r.field() ? ":" : "") + r.desc() + " is not in " + where;
    }

    /**
     * A class that is neither a game class nor a JDK class: a library the game ships beside its client jar
     * (JOML, LWJGL), whose classes the client jar cannot vouch for.
     */
    private boolean isLibraryClass(String owner) {
        return !(game.isGame(owner)
                || owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/"));
    }

    // ---------------------------------------------------------------------------------------- C3

    private void shapes(Resolution jarNames, ClassIndex index) {
        Map<String, String> alias = new HashMap<>();
        for (TypeBinding tb : bindings.types().values()) {
            int ri = Rules.first(tb.representation(), TypeBinding.Representation::when, t);
            if (ri < 0 || tb.representation().get(ri).alias() == null) continue;
            Resolver.Resolved r = jarNames.resolved(anchorOf(tb), tb.representation().get(ri).alias());
            if (r != null) alias.put(Rules.internal(tb.api()), r.owner());
        }
        Shapes shapes = new Shapes(index, alias);
        for (Map.Entry<String, TypeBinding> e : bindings.typesAndGlue().entrySet()) {
            TypeBinding tb = e.getValue();
            Optional<List<ApiClasses.Method>> methods = api.methods(Rules.internal(tb.api()));
            if (methods.isEmpty()) continue;
            Map<String, Integer> overloads = new HashMap<>();
            for (ApiClasses.Method m : methods.get()) overloads.merge(m.name(), 1, Integer::sum);
            String anchor = anchorOf(tb);
            for (ApiClasses.Method m : methods.get()) {
                String key = overloads.get(m.name()) > 1 ? m.name() + m.desc() : m.name();
                List<TypeBinding.MemberRule> rules = tb.members().get(key);
                if (rules == null) continue;
                int i = Rules.first(rules, TypeBinding.MemberRule::when, t);
                if (i < 0) continue;
                Lowering l = rules.get(i).lower();
                String label = Rules.simple(tb.api()) + "." + key;
                String problem = shapes.check(m, Rules.internal(tb.api()), l, text -> jarNames.resolved(anchor, text), reason -> {
                    boolean c2Failed = errors.stream().anyMatch(d -> d.target().equals(t.id()) && label.equals(d.member()));
                    if (!c2Failed) warnings.add(t.id() + " " + label + ": SHAPE not checked: " + reason);
                });
                if (problem == null) continue;
                errors.add(new Diagnostic(ErrorCode.SHAPE, t.id(), label, l.kind() == Lowering.Kind.CHAIN ? null : l.ref(), null,
                        "C3 shape: " + problem,
                        "make the lowering's stack effect (receiver, arguments, result) equal the API member's in " + e.getKey() + " member \"" + key
                                + "\", or bind it with an `adapter` (binding-format.md 4.3)"));
            }
        }
    }

    // ---------------------------------------------------------------------------------------- C7 call sites

    private void callSites(Map<String, Hooks.Rule> hookRules, Resolution jarNames, List<Path> jars) throws IOException {
        String anchor = bindings.targets().anchor();
        for (Map.Entry<String, Hooks.Rule> e : hookRules.entrySet()) {
            Hooks.At at = e.getValue().at();
            boolean string = at.kind() == Hooks.At.Kind.INVOKE_STRING;
            if (at.kind() != Hooks.At.Kind.INVOKE && !string) continue;
            Resolver.Resolved hook = jarNames.resolved(anchor, e.getValue().method());
            Resolver.Resolved call = jarNames.resolved(anchor, at.method());
            if (hook == null || call == null || hook.name() == null || call.name() == null) continue;
            int n = CallSites.count(jars, hook.owner(), hook.name(), hook.desc(), call.owner(), call.name(), call.desc(), string ? at.ldc() : null);
            if (n < 0) continue;   // the hook method itself is missing: reported as a C7 reference failure
            boolean ok = string || at.ordinal() == null ? n == 1 : n > at.ordinal();
            if (ok) continue;
            String wanted = string || at.ordinal() == null ? "exactly 1 (every hook is a Mixin injector with expect = 1)"
                    : "at least " + (at.ordinal() + 1) + " for ordinal " + at.ordinal();
            errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), "hook " + e.getKey(), at.method(), "call sites",
                    C7 + ": " + e.getValue().method() + " contains " + n + " call sites of " + at.method()
                            + (string ? " with the string \"" + at.ldc() + "\"" : "") + ", the injection point needs " + wanted,
                    "change the \"at\" of hook \"" + e.getKey() + "\" in hooks.json for " + t.id() + ": " + (n == 0
                            ? "pick a call the method makes, or another injection point (HEAD, TAIL, RETURN)"
                            : "add an \"ordinal\" to choose one of the " + n + " call sites")));
        }
    }
}
