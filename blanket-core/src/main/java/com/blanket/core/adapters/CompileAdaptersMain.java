// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.tool.Args;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * {@code ./gradlew compileAdapters}: compiles {@code adapters/common/src} (the compile-only {@code @Stubs} annotation)
 * into {@code <out>/common}, then every adapter set's {@code adapters/<set>/src} into {@code <out>/<set>} with
 * {@link CompileAdapters#compileSet}. Options: {@code --bindings <dir>} (default the working directory),
 * {@code --stubs <dir>} (the output of {@code stubJars}, default {@code build/stubs}), {@code --extra <dir>} (stub jars
 * of {@code @Stubs} versions, default {@code build/stubs-extra}), {@code --out <dir>} (default
 * {@code build/adapter-classes}), {@code --work <dir>} (default {@code build/tmp/compileAdapters}), {@code --set <name>}
 * (repeatable; default every set), {@code --cache <dir>}, {@code --offline} (for {@code @Stubs} stub jars).
 */
public final class CompileAdaptersMain {
    private CompileAdaptersMain() {}

    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err));
    }

    /** Returns 0 when every requested set compiled, 1 when javac failed, 2 for a bad command line or missing files. */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        try {
            Args args = Args.parse(argv, Set.of("offline"));
            Path dir = Path.of(args.get("bindings", "."));
            Path stubs = Path.of(args.get("stubs", "build/stubs"));
            Path extra = Path.of(args.get("extra", "build/stubs-extra"));
            Path outDir = Path.of(args.get("out", "build/adapter-classes"));
            Path work = Path.of(args.get("work", "build/tmp/compileAdapters"));
            List<StubJars.AdapterSet> sets = selected(dir, args.all("set"));
            MappingDownloads downloads = args.downloads();

            Path common = outDir.resolve("common");
            int n = CompileAdapters.compileSet(dir.resolve("adapters/common/src"), List.of(), v -> new CompileAdapters.EraFiles(null, List.of()), work, common);
            out.println("compileAdapters: common: " + n + " sources -> " + common);
            for (StubJars.AdapterSet set : sets) {
                Path setOut = outDir.resolve(set.name());
                List<Path> shared = List.of(common, stubs.resolve(set.name() + "-api.jar"));
                try {
                    n = CompileAdapters.compileSet(dir.resolve("adapters").resolve(set.name()).resolve("src"), shared,
                            v -> CompileAdapters.eraFiles(set, v == null ? set.stubs() : v, stubs, extra, downloads, out), work, setOut);
                } catch (CompileAdapters.JavacException e) {
                    err.println("compileAdapters: " + set.name() + ": " + e.getMessage());
                    return 1;
                }
                out.println("compileAdapters: " + set.name() + ": " + n + " sources -> " + setOut);
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("compileAdapters: " + e.getMessage());
            return 2;
        }
    }

    /** The sets of {@code <bindings>/adapters} named in {@code wanted}, or all of them; an unknown name is an error. */
    static List<StubJars.AdapterSet> selected(Path dir, List<String> wanted) throws IOException {
        List<StubJars.AdapterSet> sets = StubJars.readSets(dir);
        for (String name : wanted) {
            if (sets.stream().noneMatch(s -> s.name().equals(name))) {
                throw new IllegalArgumentException("no adapter set '" + name + "' under " + dir.resolve("adapters"));
            }
        }
        return wanted.isEmpty() ? sets : sets.stream().filter(s -> wanted.contains(s.name())).toList();
    }
}
