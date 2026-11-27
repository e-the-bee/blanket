// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.bindings.Lowering;
import com.blanket.core.bindings.Targets;
import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.plan.AdapterTargetCheck;
import com.blanket.core.plan.Lowered;
import com.blanket.core.resolve.TargetSpec;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * C4 adapters and C6 overrides (testing-strategy.md section 2) on one target. The adapter rules that apply on the
 * target must name {@code <set>:com.blanket.adapters.<Class>#<method>} of the target's set (targets.json
 * {@code "adapters"}); the closure those rules reach in the built set ({@code build/adapters/<set>/}, written by
 * {@code remapAdapters}), which is what the target would ship, is then checked with the planner's adapter closure
 * ({@link AdapterTargetCheck}): every class and member it uses resolves and is in the target's jars, every rule's method
 * has the API member's descriptor (receiver first, alias types as their game classes), and every overriding host method
 * overrides a method of a supertype. A class meant for another version of the set (a {@code @Stubs} host) is thus
 * judged only where a rule reaches it. After every target, {@link #unused} warns about built classes no target reaches.
 * A set that is not built is a warning when adapter rules apply on the target (C4 and C6 did not run on it), and
 * nothing otherwise.
 */
final class AdapterChecks {
    static final String C4 = "C4 adapters";
    private static final String PACKAGE = "com.blanket.adapters.";

    private final BindingSet bindings;
    private final TargetData data;
    private final ApiClasses api;
    private final TargetSpec t;
    private final List<Diagnostic> errors;
    private final List<String> warnings;
    private final Map<String, Set<String>> reached;

    /** @param reached set to the adapter classes some target's rules reach; this target's closure is added */
    AdapterChecks(BindingSet bindings, TargetData data, ApiClasses api, TargetSpec target, List<Diagnostic> errors, List<String> warnings,
                  Map<String, Set<String>> reached) {
        this.bindings = bindings;
        this.data = data;
        this.api = api;
        this.t = target;
        this.errors = errors;
        this.warnings = warnings;
        this.reached = reached;
    }

    void run() throws IOException {
        Targets.VersionRow row = bindings.targets().versions().get(t.mc());
        if (row == null || row.adapters() == null) return;   // C9 reports a target without a versions row
        String set = row.adapters();
        Set<String> mine = reached.computeIfAbsent(set, k -> new TreeSet<>());
        Map<String, Lowered.Adapter> adapters = rules(set);
        if (!AdapterTargetCheck.built(bindings.dir(), set)) {
            if (adapters.isEmpty()) return;   // nothing of the set is used on this target, and there is nothing to check
            warnings.add(t.id() + ": C4 adapters and C6 overrides not checked: the adapter set " + set + " is not built ("
                    + AdapterTargetCheck.setDir(bindings.dir(), set).resolve("adapter-refs.json") + " does not exist; "
                    + adapters.size() + " adapter rule(s) apply on this target); run `./gradlew :blanket-bindings:remapAdapters`");
            return;
        }
        if (adapters.isEmpty()) return;
        AdapterTargetCheck.Result r = AdapterTargetCheck.check(bindings, data, t, adapters);
        errors.addAll(r.errors());
        mine.addAll(r.reached());
    }

    /**
     * One warning per built class of a set some supported target uses that no target's selected rules reach: C4 and C6
     * did not check it, and no jar ships it.
     *
     * @param reached from every target's {@link #run}: set to the classes reached
     */
    static void unused(BindingSet bindings, Map<String, Set<String>> reached, List<String> warnings) throws IOException {
        for (Map.Entry<String, Set<String>> e : reached.entrySet()) {
            String set = e.getKey();
            if (!AdapterTargetCheck.built(bindings.dir(), set)) continue;
            for (String cls : AdapterTargetCheck.builtClasses(bindings.dir(), set)) {
                if (e.getValue().contains(cls)) continue;
                warnings.add("adapter " + set + ":" + cls.substring(cls.lastIndexOf('/') + 1) + " is reached by no adapter rule on any supported target "
                        + "(unused adapter): C4 and C6 did not check it");
            }
        }
    }

    /** The adapter calls of the rules that apply on the target, by API member key; malformed or wrong-set rules are errors here. */
    private Map<String, Lowered.Adapter> rules(String set) {
        Map<String, Lowered.Adapter> out = new LinkedHashMap<>();
        for (Map.Entry<String, TypeBinding> e : bindings.types().entrySet()) {
            String file = e.getKey();
            TypeBinding tb = e.getValue();
            String owner = Rules.internal(tb.api());
            Map<String, String> descs = apiDescs(owner);
            for (Map.Entry<String, List<TypeBinding.MemberRule>> m : tb.members().entrySet()) {
                int i = Rules.first(m.getValue(), TypeBinding.MemberRule::when, t);
                if (i < 0 || unmet(m.getValue().get(i))) continue;
                Lowering l = m.getValue().get(i).lower();
                String desc = descs.get(m.getKey());
                if (desc == null) continue;   // no API member: C1 warns about the stale rule list
                String name = m.getKey().indexOf('(') < 0 ? m.getKey() : m.getKey().substring(0, m.getKey().indexOf('('));
                String key = owner + "." + name + desc.substring(desc.indexOf('|') + 1);
                String callDesc = desc.substring(0, desc.indexOf('|'));
                String label = Rules.simple(tb.api()) + "." + m.getKey();
                if (l.kind() == Lowering.Kind.ADAPTER) {
                    adapter(l.ref(), set, file, label, callDesc, key, out);
                } else if (l.kind() == Lowering.Kind.CHAIN) {
                    for (int s = 0; s < l.steps().size(); s++) {
                        Lowering step = l.steps().get(s);
                        if (step.kind() == Lowering.Kind.ADAPTER) adapter(step.ref(), set, file, label, callDesc, key + " step " + s, out);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Member key of the type file to {@code <adapter call descriptor>|<API descriptor>}: the call takes the receiver
     * first; an alias enum constant is read with {@code ()L<owner>;} and keyed {@code :L<owner>;}.
     */
    private Map<String, String> apiDescs(String owner) {
        Map<String, String> out = new HashMap<>();
        Optional<List<ApiClasses.Method>> methods = api.methods(owner);
        if (methods.isEmpty()) return out;
        Map<String, Integer> overloads = new HashMap<>();
        for (ApiClasses.Method m : methods.get()) overloads.merge(m.name(), 1, Integer::sum);
        for (ApiClasses.Method m : methods.get()) {
            String key = overloads.get(m.name()) > 1 ? m.name() + m.desc() : m.name();
            String call = m.isStatic() ? m.desc() : "(L" + owner + ";" + m.desc().substring(1);
            out.put(key, call + "|" + m.desc());
        }
        api.classInfo(owner).ifPresent(info -> {
            for (String c : info.enumConstants()) out.putIfAbsent(c, "()L" + owner + ";|:L" + owner + ";");
        });
        return out;
    }

    private void adapter(String ref, String set, String file, String label, String desc, String key, Map<String, Lowered.Adapter> out) {
        int colon = ref.indexOf(':'), hash = ref.indexOf('#');
        String cls = colon > 0 && hash > colon ? ref.substring(colon + 1, hash) : "";
        String method = hash > 0 ? ref.substring(hash + 1) : "";
        if (!cls.startsWith(PACKAGE) || cls.indexOf('.', PACKAGE.length()) >= 0 || cls.length() == PACKAGE.length()
                || method.isEmpty() || method.indexOf('(') >= 0) {
            errors.add(new Diagnostic(ErrorCode.SHAPE, t.id(), label, ref, null,
                    C4 + ": an adapter reference is <set>:com.blanket.adapters.<Class>#<method> (binding-format.md section 5), found " + ref,
                    "write the adapter rule of " + label + " in " + file + " in that form"));
            return;
        }
        String named = ref.substring(0, colon);
        if (!named.equals(set)) {
            errors.add(new Diagnostic(ErrorCode.UNRESOLVED, t.id(), label, ref, null,
                    C4 + ": the adapter set " + named + " is not the set of " + t.mc() + ", " + set + " (targets.json)",
                    "name an adapter of the set " + set + " in the rule of " + label + " for " + t.id() + " in " + file));
            return;
        }
        out.put(key, new Lowered.Adapter("com/blanket/adapters/" + set + "/" + cls.substring(PACKAGE.length()), method, desc));
    }

    /** Whether the rule needs a feature this target lacks (its references need not exist here; TargetChecks skips them too). */
    private boolean unmet(TypeBinding.MemberRule rule) {
        String feature = rule.requires();
        return feature != null && bindings.features().names().contains(feature) && !bindings.features().available(feature, t);
    }
}
