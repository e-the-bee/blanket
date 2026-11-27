// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.cli;

import com.blanket.core.bindings.BindingException;
import com.blanket.core.lookup.Matrix;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.scan.ModScanner;
import com.blanket.core.scan.Scan;
import com.blanket.core.tool.Args;
import com.blanket.core.validate.ApiClasses;
import com.blanket.core.validate.BindingSet;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.DownloadedTargetData;
import com.blanket.core.validate.ModCheck;
import com.blanket.core.validate.Report;
import com.blanket.core.validate.TargetData;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code blanket} command line (build-and-gradle.md section 1). M1 has two commands:
 * <pre>
 * blanket check --targets &lt;loader:mc,...&gt; [--bindings &lt;dir&gt;] [--offline] &lt;mod.jar&gt;
 * blanket explain &lt;ApiType.member&gt; --target &lt;loader:mc&gt; [--bindings &lt;dir&gt;] [--offline]
 * </pre>
 * Exit code 0: nothing wrong. 1: one or more diagnostics. 2: bad usage or unreadable input.
 */
public final class Main {
    private Main() {}

    /** Gives the game data (mapping files, client jars) of the bindings' targets; tests substitute fixtures. */
    interface DataFactory {
        TargetData open(BindingSet bindings, boolean offline) throws IOException;
    }

    static final String USAGE = String.join("\n",
            "usage:",
            "  blanket check --targets <loader:mc,...> [--bindings <dir>] [--offline] <mod.jar>",
            "  blanket explain <ApiType.member> --target <loader:mc> [--bindings <dir>] [--offline]",
            "targets look like fabric:1.21.1 or legacy-fabric:1.8.9. --bindings defaults to $BLANKET_BINDINGS, else ./blanket-bindings.");

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, Main::downloads));
    }

    /** Game data from the download cache ({@code BLANKET_CACHE}, else {@code ~/.blanket/cache}); {@code offline} fails on anything missing. */
    private static TargetData downloads(BindingSet bindings, boolean offline) throws IOException {
        Args args = Args.parse(offline ? new String[] {"--offline"} : new String[0], Set.of("offline"));
        return new DownloadedTargetData(args.downloads(), bindings.targets(), bindings.dir());
    }

    static int run(String[] args, PrintStream out, PrintStream err, DataFactory data) {
        try {
            if (args.length == 0) {
                err.println(USAGE);
                return 2;
            }
            return switch (args[0]) {
                case "check" -> check(List.of(args).subList(1, args.length), out, data);
                case "explain" -> explain(List.of(args).subList(1, args.length), out, data);
                default -> throw new CliException("unknown command '" + args[0] + "'");
            };
        } catch (CliException e) {
            err.println("blanket: " + e.getMessage());
            err.println(USAGE);
            return 2;
        } catch (BindingException | IllegalStateException | IllegalArgumentException | IOException | UncheckedIOException e) {
            err.println("blanket: " + e.getMessage());
            return 2;
        }
    }

    /** Options shared by the commands: {@code --flag value} pairs, a switch, and positional words. */
    private record Options(String targets, String target, String bindings, boolean offline, List<String> positional) {
        static Options parse(List<String> args) {
            String targets = null;
            String target = null;
            String bindings = null;
            boolean offline = false;
            List<String> positional = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i);
                switch (a) {
                    case "--offline" -> offline = true;
                    case "--targets", "--target", "--bindings", "--mod" -> {
                        if (i + 1 >= args.size()) throw new CliException(a + " needs a value");
                        String v = args.get(++i);
                        switch (a) {
                            case "--targets" -> targets = v;
                            case "--target" -> target = v;
                            case "--bindings" -> bindings = v;
                            default -> positional.add(v);
                        }
                    }
                    default -> {
                        if (a.startsWith("--")) throw new CliException("unknown option " + a);
                        positional.add(a);
                    }
                }
            }
            return new Options(targets, target, bindings, offline, positional);
        }

        Path bindingsDir() {
            if (bindings != null) return Path.of(bindings);
            String env = System.getenv("BLANKET_BINDINGS");
            return Path.of(env != null && !env.isEmpty() ? env : "blanket-bindings");
        }
    }

    private static BindingSet load(Path dir) throws IOException {
        if (!Files.isRegularFile(dir.resolve("targets.json"))) {
            throw new CliException("no targets.json in bindings directory " + dir.toAbsolutePath() + " (use --bindings <dir>)");
        }
        return BindingSet.load(dir);
    }

    /** Parses {@code loader:mc} against the bindings' targets.json (era and Java level). */
    private static TargetSpec target(BindingSet bindings, String id) {
        try {
            return Matrix.spec(bindings.targets(), id);
        } catch (IllegalArgumentException e) {
            throw new CliException("target '" + id + "': " + e.getMessage());
        }
    }

    private static int check(List<String> args, PrintStream out, DataFactory data) throws IOException {
        Options o = Options.parse(args);
        if (o.targets() == null) throw new CliException("check needs --targets <loader:mc,...>");
        if (o.positional().size() != 1) throw new CliException("check needs exactly one mod jar");
        Path jar = Path.of(o.positional().get(0));
        if (!Files.isRegularFile(jar)) throw new CliException("mod jar not found: " + jar);

        BindingSet bindings = load(o.bindingsDir());
        List<TargetSpec> targets = new ArrayList<>();
        for (String id : o.targets().split(",")) {
            if (!id.isBlank()) targets.add(target(bindings, id.trim()));
        }
        if (targets.isEmpty()) throw new CliException("--targets lists no target");

        Map<String, byte[]> classes = ModScanner.classes(jar);
        Scan scan = ModScanner.scan(classes);
        Report report = ModCheck.run(bindings, data.open(bindings, o.offline()), ApiClasses.of(Main.class.getClassLoader()), scan, classes, targets);
        for (Diagnostic d : report.errors()) out.println(d.format());
        for (String w : report.warnings()) out.println("warning: " + w);
        String scanned = scan.members().size() + " API members, " + scan.types().size() + " API types, "
                + scan.events().size() + " events in " + scan.classCount() + " classes";
        if (report.ok()) {
            out.println("check passed: " + scanned + " resolve on " + targets.size() + " target(s)");
            return 0;
        }
        out.println("check failed: " + report.errors().size() + " error(s) on " + targets.size() + " target(s); " + scanned);
        return 1;
    }

    private static int explain(List<String> args, PrintStream out, DataFactory data) throws IOException {
        Options o = Options.parse(args);
        if (o.target() == null) throw new CliException("explain needs --target <loader:mc>");
        if (o.positional().size() != 1) throw new CliException("explain needs exactly one <ApiType.member>");
        BindingSet bindings = load(o.bindingsDir());
        TargetSpec target = target(bindings, o.target());
        Explain.Result r = new Explain(bindings, data.open(bindings, o.offline()).mappingFiles()).explain(o.positional().get(0), target);
        for (String line : r.lines()) out.println(line);
        for (Diagnostic d : r.diagnostics()) out.println(d.format());
        return r.diagnostics().isEmpty() ? 0 : 1;
    }
}
