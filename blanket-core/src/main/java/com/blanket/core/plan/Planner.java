// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.lower.Hierarchy;
import com.blanket.core.lookup.Matrix;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.scan.Scan;
import com.blanket.core.validate.ApiClasses;
import com.blanket.core.validate.BindingSet;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.DownloadedTargetData;
import com.blanket.core.validate.ErrorCode;
import com.blanket.core.validate.ModCheck;
import com.blanket.core.validate.Report;
import com.blanket.core.validate.TargetData;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * RESOLVE (architecture.md section 3.2): SCANs a mod jar and turns the first matching rule of everything it uses into a
 * {@link Plan} per supported target, in runtime names with ready opcodes. The used set of a target is the mod's classes
 * plus the embedded classes {@link Reach} says the target's jar can run. Before planning a target, that used set is
 * checked like {@code blanket check} ({@link ModCheck}: C1, C2, C3, C7); any failure there or in planning throws an
 * {@link IllegalStateException} whose message is the {@link Diagnostic} lines.
 */
public final class Planner {
    private Planner() {}

    /**
     * Plans with game data from the Blanket cache, downloaded on first use.
     *
     * @param embed jars or class folders of the classes every generated jar embeds (blanket-api and blanket-runtime)
     */
    public static PlanSource plan(Path modJar, Path bindingsDir, List<Path> embed) throws IOException {
        BindingSet bindings = BindingSet.load(bindingsDir);
        MappingDownloads downloads = new MappingDownloads(new ArtifactFetcher(ArtifactFetcher.defaultCacheRoot(), false));
        return plan(modJar, embed, bindings, new DownloadedTargetData(downloads, bindings.targets(), bindingsDir), Planner.class.getClassLoader());
    }

    /** Plans with the given game data (tests, offline fixtures). */
    public static PlanSource plan(Path modJar, Path bindingsDir, List<Path> embed, TargetData data) throws IOException {
        return plan(modJar, embed, BindingSet.load(bindingsDir), data, Planner.class.getClassLoader());
    }

    /** As above, reading the API classes (glue stubs, {@code Dispatch}, enum-ness of API types) through {@code apiLoader}. */
    static PlanSource plan(Path modJar, Path bindingsDir, List<Path> embed, TargetData data, ClassLoader apiLoader) throws IOException {
        return plan(modJar, embed, BindingSet.load(bindingsDir), data, apiLoader);
    }

    private static PlanSource plan(Path modJar, List<Path> embedPaths, BindingSet bindings, TargetData data, ClassLoader apiLoader) throws IOException {
        Map<String, byte[]> mod = Reach.read(modJar);
        Map<String, byte[]> embed = new TreeMap<>();
        for (Path p : embedPaths) embed.putAll(Reach.read(p));
        ApiClasses api = ApiClasses.of(apiLoader);
        List<TargetSpec> targets = List.copyOf(Matrix.supported(bindings.targets()));
        Map<String, Plan> plans = new HashMap<>();
        return new PlanSource() {
            @Override
            public List<TargetSpec> targets() {
                return targets;
            }

            @Override
            public Plan plan(TargetSpec target) {
                if (!targets.contains(target)) throw new IllegalArgumentException(target.id() + " is not a supported target");
                return plans.computeIfAbsent(target.id(), id -> {
                    try {
                        return planOne(bindings, data, api, apiLoader, mod, embed, target);
                    } catch (IOException e) {
                        throw new UncheckedIOException("cannot obtain the game data of " + target.id() + ": " + e.getMessage(), e);
                    }
                });
            }

            @Override
            public Hierarchy hierarchy(TargetSpec target) {
                return Hierarchy.empty();
            }
        };
    }

    private static Plan planOne(BindingSet bindings, TargetData data, ApiClasses api, ClassLoader apiLoader, Map<String, byte[]> mod,
                                Map<String, byte[]> embed, TargetSpec t) throws IOException {
        List<String> roots = new ArrayList<>(Reach.ROOTS);
        roots.add(TargetPlan.glImpl(t));
        Scan scan = Reach.scan(mod, embed, roots, stubClasses(bindings));
        Report check = ModCheck.run(bindings, data, api, scan, List.of(t));
        List<Diagnostic> errors = new ArrayList<>();
        Predicate<String> enums = owner -> isEnum(apiLoader, owner);
        Set<String> unmet = TargetPlan.unmetLabels(bindings, t);
        for (Diagnostic d : check.errors()) {
            boolean ofUnmetMember = unmet.contains(d.member()) && (d.code() == ErrorCode.UNRESOLVED || d.code() == ErrorCode.SHAPE);
            if (!ofUnmetMember && !decidedHere(d, bindings, scan, enums)) errors.add(d);
        }
        if (!errors.isEmpty()) throw fail(errors);
        try (Names names = new Names(t, bindings.targets().gameClasses())) {
            TargetPlan builder = new TargetPlan(bindings, scan, api, t, names, enums);
            builder.build();          // collects every reference
            names.resolve(data);
            return builder.build();   // the plan in resolved names
        }
    }

    /**
     * The API classes no jar ships: those with a type file whose representation is not {@code owned} (aliased types and
     * owned facades are compile-time stubs). Owned classes and API classes without a type file are embedded.
     */
    private static Set<String> stubClasses(BindingSet bindings) {
        Set<String> out = new TreeSet<>();
        for (TypeBinding tb : bindings.types().values()) {
            if (tb.representation().stream().noneMatch(r -> r.owned() != null)) out.add(tb.api().replace('.', '/'));
        }
        return out;
    }

    /**
     * The coverage findings of {@link ModCheck} that are no failure of the plan, which carries the answer in its members:
     * an explicit {@code unsupported} rule (the plan holds {@link Lowered.Unsupported}, and FOLD fails only the uses it
     * cannot prove dead), a constant of an aliased enum (a field of an API type, bound by the rule under the constant's
     * name), a use of an owned facade (a type file with member rules and no representation) and a pass-through member
     * ({@link TargetPlan#passThrough}). {@code TargetPlan} still fails a used member that has no rule and is none of these.
     */
    private static boolean decidedHere(Diagnostic d, BindingSet bindings, Scan scan, Predicate<String> enums) {
        if (d.code() == ErrorCode.UNSUPPORTED) {
            return d.detail().startsWith("C1 coverage:") && d.detail().contains(" marks this member unsupported on ");
        }
        if (d.code() != ErrorCode.UNBOUND) return false;
        int dot = d.member().indexOf('.');
        if (dot > 0 && d.detail().startsWith("C1 coverage: used member ") && d.detail().contains(" has no rule list in ")) {
            TypeBinding tb = typeFile(bindings, d.member().substring(0, dot));
            if (tb == null) return false;
            String name = d.member().substring(dot + 1);
            boolean any = false;
            for (Scan.ApiMember m : scan.members()) {
                if (!m.owner().equals(tb.api().replace('.', '/')) || !m.name().equals(name)) continue;
                if (!TargetPlan.passThrough(m, enums)) return false;
                any = true;
            }
            return any;
        }
        String suffix = " (representation)";
        if (d.member().endsWith(suffix)) {
            TypeBinding tb = typeFile(bindings, d.member().substring(0, d.member().length() - suffix.length()));
            return tb != null && TargetPlan.isFacade(tb);
        }
        if (dot > 0 && d.detail().startsWith("C1 coverage: the mod reads the field ")) {
            TypeBinding tb = typeFile(bindings, d.member().substring(0, dot));
            return tb != null && tb.members().containsKey(d.member().substring(dot + 1));
        }
        return false;
    }

    private static TypeBinding typeFile(BindingSet bindings, String simpleName) {
        for (TypeBinding tb : bindings.types().values()) {
            if (tb.api().substring(tb.api().lastIndexOf('.') + 1).equals(simpleName)) return tb;
        }
        return null;
    }

    /** Whether the API class is an enum: its class file has {@code ACC_ENUM}. A class {@code apiLoader} cannot find is not. */
    private static boolean isEnum(ClassLoader apiLoader, String owner) {
        try (InputStream in = apiLoader.getResourceAsStream(owner + ".class")) {
            if (in == null) return false;
            int[] access = new int[1];
            new ClassReader(in.readAllBytes()).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int acc, String name, String signature, String superName, String[] interfaces) {
                    access[0] = acc;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return (access[0] & Opcodes.ACC_ENUM) != 0;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read API class " + owner + ": " + e.getMessage(), e);
        }
    }

    static IllegalStateException fail(Diagnostic d) {
        return fail(List.of(d));
    }

    static IllegalStateException fail(List<Diagnostic> ds) {
        StringBuilder b = new StringBuilder();
        for (Diagnostic d : ds) b.append(b.length() == 0 ? "" : "\n").append(d.format());
        return new IllegalStateException(b.toString());
    }
}
