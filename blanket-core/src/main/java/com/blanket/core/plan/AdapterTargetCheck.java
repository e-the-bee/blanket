// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.bindings.Version;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.validate.BindingSet;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.TargetData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The adapter half of the bindings validator on one target (C4, C6; testing-strategy.md section 2), with the planner's
 * own {@link AdapterClosure}: the closure the target's selected adapter rules reach in the built set
 * ({@code <bindings>/build/adapters/<set>/}), which is what the target's jars would ship, is loaded; every game
 * reference it makes is resolved and looked up in the target's jars, every adapter rule's method is checked against
 * the API member's descriptor, and every {@code overrides} entry of a reached host is checked against the host's
 * supertypes. Classes no selected rule reaches are not checked here (a host meant for another version).
 */
public final class AdapterTargetCheck {
    private AdapterTargetCheck() {}

    /** The folder {@code remapAdapters} writes one set to. */
    public static Path setDir(Path bindingsDir, String set) {
        return bindingsDir.resolve("build").resolve("adapters").resolve(set);
    }

    /** Whether {@code remapAdapters} has written the set: its {@code adapter-refs.json} exists. */
    public static boolean built(Path bindingsDir, String set) {
        return Files.isRegularFile(setDir(bindingsDir, set).resolve(AdapterClosure.REFS_FILE));
    }

    /** What one target's check found: its diagnostics and the adapter classes its rules reach. */
    public record Result(List<Diagnostic> errors, Set<String> reached) {}

    /**
     * Checks the closure of {@code adapters} in the built set on {@code t}. Closure problems come back with details
     * starting {@code C4 adapters:}, override problems with {@code C6 overrides:}.
     *
     * @param adapters the adapter rules that apply on {@code t}: API member key ({@code owner.name+desc}) to its call
     */
    public static Result check(BindingSet bindings, TargetData data, TargetSpec t, Map<String, Lowered.Adapter> adapters) throws IOException {
        String anchor = bindings.targets().anchor();
        Map<String, Lowered> members = new LinkedHashMap<>(adapters);
        try (Names names = new Names(t, bindings.targets().gameClasses())) {
            AdapterClosure closure = AdapterClosure.gather(bindings.dir(), t, bindings.targets().gameClasses(), anchor, members, names);
            Map<String, String[]> aliases = new LinkedHashMap<>();   // API class -> {anchor, alias reference}
            for (TypeBinding tb : bindings.types().values()) {
                TypeBinding.Representation rep = firstRepresentation(tb, t);
                if (rep == null || rep.alias() == null) continue;
                String a = tb.anchor() != null ? tb.anchor() : anchor;
                try {
                    names.optional(a, rep.alias(), tb.api() + " (representation)");
                    aliases.put(tb.api().replace('.', '/'), new String[] {a, rep.alias()});
                } catch (IllegalStateException malformed) {
                    // a malformed alias is C2's to report; the shapes of members using it are then compared in API names
                }
            }
            names.resolve(data);
            Map<String, String> types = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> e : aliases.entrySet()) {
                Resolver.Resolved r = names.optional(e.getValue()[0], e.getValue()[1], e.getKey());
                if (r != null) types.put(e.getKey(), r.owner());
            }
            if (!closure.isEmpty()) {
                try (TargetHierarchy hierarchy = TargetHierarchy.open(t, data, names.runtimeToJar())) {
                    Map<String, byte[]> remapped = closure.remapAll(types, names, hierarchy);
                    closure.checkOverrides(names, hierarchy, remapped);
                }
            }
            List<Diagnostic> out = new ArrayList<>();
            for (Diagnostic d : closure.errors()) {
                out.add(d.detail().startsWith("C6 ") ? d : new Diagnostic(d.code(), d.target(), d.member(), d.reference(), d.namespaceStep(),
                        "C4 adapters: " + d.detail(), d.nextStep()));
            }
            return new Result(out, closure.reached());
        }
    }

    private static TypeBinding.Representation firstRepresentation(TypeBinding tb, TargetSpec t) {
        Version mc = Version.parse(t.mc());
        for (TypeBinding.Representation r : tb.representation()) {
            if (r.when().matches(t.loader(), mc)) return r;
        }
        return null;
    }

    /** Every class file of the built set, as {@code com/blanket/adapters/<set>/...} internal names. */
    public static List<String> builtClasses(Path bindingsDir, String set) throws IOException {
        Path root = setDir(bindingsDir, set);
        Path pkg = root.resolve(AdapterClosure.PACKAGE + set);
        if (!Files.isDirectory(pkg)) return List.of();
        try (Stream<Path> files = Files.walk(pkg)) {
            return files.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .map(s -> s.substring(0, s.length() - ".class".length()))
                    .sorted()
                    .toList();
        }
    }
}
