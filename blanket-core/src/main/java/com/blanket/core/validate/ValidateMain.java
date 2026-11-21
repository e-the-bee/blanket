// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.validate;

import com.blanket.core.tool.Args;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * {@code ./gradlew validateBindings}: runs C1, C2, C3, C7 and C8 on every supported target. Options:
 * {@code --bindings <dir>} (default the working directory), {@code --cache <dir>}, {@code --offline}. The API and glue
 * classes are read from this program's classpath.
 */
public final class ValidateMain {
    private ValidateMain() {}

    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err));
    }

    /** Returns the exit code: 0 when there are no errors, 1 when there are, 2 for a bad command line, bad files or missing data. */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        try {
            Args args = Args.parse(argv, Set.of("offline"));
            Path dir = Path.of(args.get("bindings", "."));
            BindingSet bindings = BindingSet.load(dir);
            TargetData data = new DownloadedTargetData(args.downloads(), bindings.targets(), dir);
            Report report = new Validator(bindings, data, ApiClasses.of(ValidateMain.class.getClassLoader())).run();
            out.print(report.format());
            return report.ok() ? 0 : 1;
        } catch (IOException | RuntimeException e) {
            err.println("validateBindings: " + e.getMessage());
            return 2;
        }
    }
}
