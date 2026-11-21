// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.bindings.Targets;
import com.blanket.core.tool.Args;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * {@code ./gradlew lookup --ref "Entity#getX()D" [--target forge:1.20.1]...}: prints the runtime name of a reference on
 * every supported target, or the step where it fails with candidates. Options: {@code --bindings <dir>} (default the
 * working directory, which holds targets.json), {@code --cache <dir>}, {@code --offline}.
 */
public final class LookupMain {
    private LookupMain() {}

    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err));
    }

    /** Returns the exit code: 0 when every target resolved, 1 when any failed, 2 for a bad command line or data. */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        try {
            Args args = Args.parse(argv, Set.of("offline"));
            Targets targets = BindingFiles.readTargets(Path.of(args.get("bindings", ".")).resolve("targets.json"));
            Lookup lookup = new Lookup(targets, MappingSources.files(args.downloads(), targets));
            java.util.List<Lookup.Result> results = lookup.lookup(args.require("ref"), args.all("target"));
            out.print(Lookup.table(results));
            for (Lookup.Result r : results) {
                for (Lookup.Row row : r.rows()) {
                    if (!row.ok()) return 1;
                }
            }
            return 0;
        } catch (RuntimeException e) {
            err.println("lookup: " + Lookup.describe(e));
            return 2;
        }
    }
}
