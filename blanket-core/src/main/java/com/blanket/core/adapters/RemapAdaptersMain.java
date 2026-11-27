// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.tool.Args;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * {@code ./gradlew remapAdapters}: for every adapter set, the classes {@code compileAdapters} wrote to
 * {@code <classes>/<set>} (authoring names) are remapped with {@link RemapAdapters#remapSet} into {@code <out>/<set>}:
 * {@code com/blanket/adapters/<set>/*.class} in shipping names and {@code adapter-refs.json}. Options:
 * {@code --bindings <dir>} (default the working directory), {@code --classes <dir>} (default
 * {@code build/adapter-classes}), {@code --stubs <dir>} (default {@code build/stubs}), {@code --extra <dir>} (default
 * {@code build/stubs-extra}), {@code --out <dir>} (default {@code build/adapters}), {@code --set <name>} (repeatable;
 * default every set), {@code --cache <dir>}, {@code --offline}.
 */
public final class RemapAdaptersMain {
    private RemapAdaptersMain() {}

    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err));
    }

    /** Returns 0 when every requested set was remapped, 2 for a bad command line, missing files or missing data. */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        try {
            Args args = Args.parse(argv, Set.of("offline"));
            Path dir = Path.of(args.get("bindings", "."));
            Path compiled = Path.of(args.get("classes", "build/adapter-classes"));
            Path stubs = Path.of(args.get("stubs", "build/stubs"));
            Path extra = Path.of(args.get("extra", "build/stubs-extra"));
            Path outDir = Path.of(args.get("out", "build/adapters"));
            List<StubJars.AdapterSet> sets = CompileAdaptersMain.selected(dir, args.all("set"));
            MappingDownloads downloads = args.downloads();
            for (StubJars.AdapterSet set : sets) {
                Map<String, byte[]> classes = readClasses(compiled.resolve(set.name()));
                Path api = stubs.resolve(set.name() + "-api.jar");
                RemapAdapters.EraSource eras = version -> {
                    CompileAdapters.EraFiles files = CompileAdapters.eraFiles(set, version, stubs, extra, null, out);
                    List<Path> libraries = new ArrayList<>();
                    libraries.add(api);
                    libraries.addAll(files.libraries());
                    return new RemapAdapters.Era(files.stubJar(), libraries, RemapAdapters.stages(set, version, downloads));
                };
                Path setOut = outDir.resolve(set.name());
                AdapterRefs refs = RemapAdapters.remapSet(set.name(), set.shipping(), set.stubs(), classes, eras, setOut);
                out.println("remapAdapters: " + set.name() + ": " + classes.size() + " classes -> " + setOut + " (" + set.shipping() + ", "
                        + refs.declaring().size() + " declaring, " + refs.overrides().size() + " overrides)");
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("remapAdapters: " + e.getMessage());
            return 2;
        }
    }

    /** Every class file under {@code dir} by internal name; an absent folder is an error (compileAdapters writes one per set). */
    static Map<String, byte[]> readClasses(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) throw new IOException("missing " + dir + "; run compileAdapters first");
        Map<String, byte[]> classes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                classes.put(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(p));
            }
        }
        return classes;
    }
}
