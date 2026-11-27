// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.adapters;

import com.blanket.core.mapping.MappingDownloads;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.tool.Args;
import com.blanket.core.validate.BindingSet;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ./gradlew stubJars}: for every adapter set builds, in {@code --out} (default {@code build/stubs}), the era stub
 * jar {@code <stubs version>-<authoring>.jar}, the API view jar {@code <set>-api.jar} and the list of library jars
 * {@code <set>-libs.txt} (one path per line, jars copied to {@code libs/}) that javac needs beside them. Options: {@code --bindings <dir>} (default the
 * working directory), {@code --classes <jar or folder>} (required, repeatable: the compiled blanket-api and
 * blanket-runtime), {@code --set <name>} (repeatable; default every set), {@code --cache <dir>}, {@code --offline}.
 */
public final class StubJarsMain {
    private StubJarsMain() {}

    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err));
    }

    /** Returns 0 when every requested set was built, 2 for a bad command line, bad files or missing data. */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        try {
            Args args = Args.parse(argv, Set.of("offline"));
            Path dir = Path.of(args.get("bindings", "."));
            Path outDir = Path.of(args.get("out", "build/stubs"));
            List<Path> classes = new ArrayList<>();
            for (String c : args.all("classes")) classes.add(Path.of(c));
            if (classes.isEmpty()) throw new IllegalArgumentException("--classes is required: the compiled blanket-api and blanket-runtime (jar or class folder)");
            List<String> wanted = args.all("set");

            List<StubJars.AdapterSet> sets = StubJars.readSets(dir);
            for (String name : wanted) {
                if (sets.stream().noneMatch(s -> s.name().equals(name))) {
                    throw new IllegalArgumentException("no adapter set '" + name + "' under " + dir.resolve("adapters"));
                }
            }
            BindingSet bindings = BindingSet.load(dir);
            MappingDownloads downloads = args.downloads();
            for (StubJars.AdapterSet set : sets) {
                if (!wanted.isEmpty() && !wanted.contains(set.name())) continue;
                build(set, bindings, downloads, classes, outDir, out);
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("stubJars: " + e.getMessage());
            return 2;
        }
    }

    static void build(StubJars.AdapterSet set, BindingSet bindings, MappingDownloads downloads, List<Path> classes,
                              Path outDir, PrintStream out) throws IOException {
        List<StubJars.Table> stages = StubJars.stages(set, downloads);
        Path stubs = outDir.resolve(set.stubs() + "-" + set.authoring() + ".jar");
        int stubClasses = StubJars.write(downloads.clientJar(set.stubs()), stages, stubs);
        out.println("stubJars: " + set.name() + ": " + stubs + " (" + stubClasses + " classes)");

        TargetSpec target = ApiView.target(bindings.targets(), set);
        Map<String, String> aliases = ApiView.aliases(bindings, target, set, stages);
        Path view = outDir.resolve(set.name() + "-api.jar");
        int viewClasses = ApiView.write(view, classes, aliases);
        out.println("stubJars: " + set.name() + ": " + view + " (" + viewClasses + " classes, " + aliases.size() + " alias types)");

        Path libs = outDir.resolve(set.name() + "-libs.txt");
        StringBuilder text = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Path> lib : downloads.libraryJars(set.stubs()).entrySet()) {
            // the cache names a file by its checksum; javac wants a .jar, so each library is copied to <out>/libs/<g>-<a>-<v>.jar
            Path jar = outDir.resolve("libs").resolve(lib.getKey().replace(':', '-') + ".jar");
            if (!Files.isRegularFile(jar) || Files.size(jar) != Files.size(lib.getValue())) {
                Files.createDirectories(jar.getParent());
                Files.copy(lib.getValue(), jar, StandardCopyOption.REPLACE_EXISTING);
            }
            text.append(jar.toAbsolutePath()).append('\n');
            count++;
        }
        Files.writeString(libs, text.toString(), StandardCharsets.UTF_8);
        out.println("stubJars: " + set.name() + ": " + libs + " (" + count + " libraries)");
    }
}
