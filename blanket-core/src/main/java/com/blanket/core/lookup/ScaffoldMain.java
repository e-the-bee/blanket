// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lookup;

import com.blanket.core.bindings.BindingFiles;
import com.blanket.core.bindings.Targets;
import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.tool.Args;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * {@code ./gradlew scaffold --kind member|type|event|feature|target ...}: writes the skeleton of a contribution
 * (contributing.md section 3). Options: {@code --bindings <dir>} (default the working directory, which holds
 * targets.json), {@code --api-src <dir>} (default {@code ../blanket-api/src/main/java/com/blanket/api}),
 * {@code --cache <dir>}, {@code --offline}; per kind {@code --type --sig}, {@code --name --alias|--owned},
 * {@code --name}, {@code --name}, {@code --mc [--java]}.
 */
public final class ScaffoldMain {
    private ScaffoldMain() {}

    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err));
    }

    /** Returns the exit code: 0 when files were written, 2 for a bad command line or data. */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        try {
            Args args = Args.parse(argv, Set.of("owned", "offline"));
            String kind = args.require("kind");
            if (kind.equals("packet")) {
                err.println("scaffold: --kind packet is not supported yet: TypeBinding has no \"packet\" block (binding-format.md 4.6)");
                return 2;
            }
            Path bindings = Path.of(args.get("bindings", "."));
            Path apiSrc = Path.of(args.get("api-src", "../blanket-api/src/main/java/com/blanket/api"));
            Targets targets = BindingFiles.readTargets(bindings.resolve("targets.json"));
            MappingDownloads downloads = args.downloads();
            Scaffold scaffold = new Scaffold(bindings, apiSrc, targets, new Lookup(targets, MappingSources.files(downloads, targets)));

            List<Path> files;
            String next;
            switch (kind) {
                case "member":
                    files = scaffold.member(args.require("type"), args.require("sig"));
                    next = "fill the TODO `unsupported` rules in the type file (`lookup --ref ...` shows the names per target),"
                            + " then add a row to docs/api-surface.md (contributing.md 3.1 step 3)";
                    break;
                case "type":
                    files = scaffold.type(args.require("name"), args.get("alias"), args.has("owned"));
                    next = "fill the TODO aliases or owned representation in the type file, describe the type in its javadoc,"
                            + " then add members with --kind member (contributing.md 3.2)";
                    break;
                case "event":
                    files = scaffold.event(args.require("name"));
                    next = "name the injection point per era in the TODO hook of hooks.json and add the fire method in"
                            + " blanket-runtime (contributing.md 3.3)";
                    break;
                case "feature":
                    files = scaffold.feature(args.require("name"));
                    next = "list the eras where the feature is available in features.json (binding-format.md 6)";
                    break;
                case "target": {
                    String mc = args.require("mc");
                    int java = args.has("java") ? parseJava(args.get("java")) : downloads.javaVersion(mc);
                    files = scaffold.target(mc, java);
                    next = "run updateTargets for the supported list, then validateBindings (contributing.md 3.6)";
                    break;
                }
                default:
                    throw new IllegalArgumentException("pass --kind as member, type, event, feature or target, found '" + kind + "'");
            }
            for (Path f : files) {
                out.println((scaffold.created().contains(f) ? "created " : "changed ") + f);
            }
            out.println("next: " + next);
            return 0;
        } catch (RuntimeException e) {
            err.println("scaffold: " + (e instanceof IllegalArgumentException ? e.getMessage() : Lookup.describe(e)));
            return 2;
        }
    }

    private static int parseJava(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("pass --java as a number such as 21, found '" + text + "'");
        }
    }
}
