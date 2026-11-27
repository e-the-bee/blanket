// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.bindings.Targets;
import com.blanket.core.tool.Args;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * {@code ./gradlew lookup --ref "Entity#getX()D" [--target forge:1.20.1]...}: prints the runtime name of a reference on
 * every supported target, or the step where it fails with candidates. A {@code loader:} reference is looked up in the
 * loader artifact that {@code loader-artifacts.json} of the bindings folder lists for the target. Options:
 * {@code --bindings <dir>} (default the working directory, which holds targets.json), {@code --cache <dir>}, {@code --offline}.
 */
public final class LookupMain {
    private LookupMain() {}

    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err));
    }

    /**
     * Returns the exit code: 0 when the table is the answer, 1 when a target named with {@code --target} failed or no
     * target resolved at all, 2 for a bad command line or data. A reference that is valid on only part of the matrix
     * exits 0 (the table shows where it fails).
     */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        try {
            Args args = Args.parse(argv, Set.of("offline"));
            Path bindings = Path.of(args.get("bindings", "."));
            Targets targets = BindingFiles.readTargets(bindings.resolve("targets.json"));
            MappingDownloads downloads = args.downloads();
            Lookup lookup = new Lookup(targets, MappingSources.files(downloads, targets),
                    LoaderJars.fromFile(downloads, bindings.resolve("loader-artifacts.json")));
            try {
                List<String> named = args.all("target");
                List<Lookup.Result> results = lookup.lookup(args.require("ref"), named);
                return report(results, !named.isEmpty(), out, err);
            } finally {
                lookup.close();
            }
        } catch (RuntimeException e) {
            err.println("lookup: " + Lookup.describe(e));
            return 2;
        }
    }

    /** Prints the table; when the exit code is 1, also one line on {@code err} saying why the first row failed. */
    static int report(List<Lookup.Result> results, boolean targetsNamed, PrintStream out, PrintStream err) {
        out.print(Lookup.table(results));
        int code = exitCode(results, targetsNamed);
        if (code == 1) {
            results.stream().flatMap(r -> r.rows().stream()).filter(row -> !row.ok()).findFirst()
                    .ifPresent(row -> err.println("lookup: " + row.message()));
        }
        return code;
    }

    /** 1 when any row fails and the user named the targets, or when no row resolved; else 0. */
    static int exitCode(List<Lookup.Result> results, boolean targetsNamed) {
        boolean anyOk = false;
        for (Lookup.Result r : results) {
            for (Lookup.Row row : r.rows()) {
                if (row.ok()) anyOk = true;
                else if (targetsNamed) return 1;
            }
        }
        return anyOk ? 0 : 1;
    }
}
