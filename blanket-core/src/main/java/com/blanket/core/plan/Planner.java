// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.fetch.ArtifactFetcher;
import com.blanket.core.lower.Hierarchy;
import com.blanket.core.lookup.Matrix;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.scan.ModScanner;
import com.blanket.core.scan.Scan;
import com.blanket.core.validate.ApiClasses;
import com.blanket.core.validate.BindingSet;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.DownloadedTargetData;
import com.blanket.core.validate.ModCheck;
import com.blanket.core.validate.Report;
import com.blanket.core.validate.TargetData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RESOLVE (architecture.md section 3.2): SCANs a mod jar and turns the first matching rule of everything it uses into a
 * {@link Plan} per supported target, in runtime names with ready opcodes. Before planning a target, the mod's used set
 * is checked like {@code blanket check} ({@link ModCheck}: C1, C2, C3, C7); any failure there or in planning throws an
 * {@link IllegalStateException} whose message is the {@link Diagnostic} lines.
 */
public final class Planner {
    private Planner() {}

    /** Plans with game data from the Blanket cache, downloaded on first use. */
    public static PlanSource plan(Path modJar, Path bindingsDir) throws IOException {
        BindingSet bindings = BindingSet.load(bindingsDir);
        MappingDownloads downloads = new MappingDownloads(new ArtifactFetcher(ArtifactFetcher.defaultCacheRoot(), false));
        return plan(modJar, bindings, new DownloadedTargetData(downloads, bindings.targets(), bindingsDir));
    }

    /** Plans with the given game data (tests, offline fixtures). */
    public static PlanSource plan(Path modJar, Path bindingsDir, TargetData data) throws IOException {
        return plan(modJar, BindingSet.load(bindingsDir), data);
    }

    private static PlanSource plan(Path modJar, BindingSet bindings, TargetData data) throws IOException {
        Scan scan = ModScanner.scan(modJar);
        ApiClasses api = ApiClasses.of(Planner.class.getClassLoader());
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
                        return planOne(bindings, data, api, scan, target);
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

    private static Plan planOne(BindingSet bindings, TargetData data, ApiClasses api, Scan scan, TargetSpec t) throws IOException {
        Report check = ModCheck.run(bindings, data, api, scan, List.of(t));
        if (!check.ok()) throw fail(check.errors());
        try (Names names = new Names(t, bindings.targets().gameClasses())) {
            TargetPlan builder = new TargetPlan(bindings, scan, api, t, names);
            builder.build();          // collects every reference
            names.resolve(data);
            return builder.build();   // the plan in resolved names
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
