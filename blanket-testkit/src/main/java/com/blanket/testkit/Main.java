// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code blanket-testkit run <target> [--jar <jar>] [--probes <file>] --out <dir> [--timeout <seconds>]
 * [--home <dir>]}. Exit code 0 when the verdict passes, 1 when it fails, 2 on a usage or provisioning error. On
 * Linux start it under {@code xvfb-run -a -s "-screen 0 854x480x24"}.
 */
public final class Main {
    static final String USAGE = """
            usage: blanket-testkit run <target> [--jar <jar>] [--probes <file>] --out <dir> [--timeout <seconds>] [--home <dir>]
              target     legacy-fabric:1.8.9 | forge:1.8.9 | fabric:1.21.1 | neoforge:1.21.1 | forge:1.20.1 | fabric:26.1.2
              --jar      the mod jar to install (a --testkit build); without it the game starts with no mod
              --probes   testkit-probes.json; passed to the game with the result path (both or neither)
              --out      folder for game.log, server.log, command.txt, verdict.txt, shot.png and testkit-result.json
              --timeout  seconds from launch until the result file must exist (default 360)
              --home     downloads and instances (default $BLANKET_TESTKIT_HOME, else ~/.blanket-testkit);
                         layout <home>/.cache and <home>/run, as the M0 spike's folder
            JDKs: BLANKET_JDK<major> (a JDK home), else /opt/blanket-jdk/jdk* (Windows: Corretto, Adoptium folders).""";

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.getenv()));
    }

    static int run(String[] args, PrintStream out, Map<String, String> env) {
        Harness.Options o;
        try {
            o = parse(args, env);
        } catch (HarnessException e) {
            out.println(e.getMessage());
            out.println(USAGE);
            return 2;
        }
        try {
            return new Harness(out, Jdks.system(), Platform.current()).run(o);
        } catch (HarnessException e) {
            out.println("error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            out.println("error: " + e);
            e.printStackTrace(out);
            return 2;
        }
    }

    static Harness.Options parse(String[] args, Map<String, String> env) throws HarnessException {
        if (args.length < 2 || !args[0].equals("run")) {
            throw new HarnessException("expected: run <target> ...");
        }
        Target target = Target.parse(args[1]);
        Path jar = null;
        Path probes = null;
        Path out = null;
        int timeout = 360;
        String homeEnv = env.get("BLANKET_TESTKIT_HOME");
        Path home = homeEnv != null && !homeEnv.isEmpty() ? Path.of(homeEnv)
                : Path.of(System.getProperty("user.home"), ".blanket-testkit");
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (i + 1 >= args.length) {
                throw new HarnessException("missing value after " + a);
            }
            String v = args[++i];
            switch (a) {
                case "--jar" -> jar = existing(v, "--jar");
                case "--probes" -> probes = existing(v, "--probes");
                case "--out" -> out = Path.of(v);
                case "--home" -> home = Path.of(v);
                case "--timeout" -> {
                    try {
                        timeout = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        throw new HarnessException("--timeout needs a number of seconds, got " + v);
                    }
                    if (timeout <= 0) {
                        throw new HarnessException("--timeout must be positive, got " + v);
                    }
                }
                default -> throw new HarnessException("unknown option " + a);
            }
        }
        if (out == null) {
            throw new HarnessException("--out is required");
        }
        return new Harness.Options(target, jar, probes, out, timeout, home);
    }

    private static Path existing(String v, String option) throws HarnessException {
        Path p = Path.of(v);
        if (!Files.isRegularFile(p)) {
            throw new HarnessException(option + " " + v + ": no such file");
        }
        return p;
    }
}
