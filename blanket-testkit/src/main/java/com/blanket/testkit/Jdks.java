// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the JDK for a Java major version (the version JSON's {@code javaVersion.majorVersion}): the environment
 * variable {@code BLANKET_JDK<major>} (a JDK home), else the newest folder named {@code jdk<major>*},
 * {@code jdk-<major>*} or {@code jdk1.<major>*} under the roots.
 */
public final class Jdks {
    private final Map<String, String> env;
    private final List<Path> roots;
    private final Platform platform;

    public Jdks(Map<String, String> env, List<Path> roots, Platform platform) {
        this.env = env;
        this.roots = roots;
        this.platform = platform;
    }

    /** The roots run.py searched: Corretto and Adoptium on Windows, {@code /opt/blanket-jdk} elsewhere. */
    public static List<Path> defaultRoots(Platform platform) {
        return platform.windows()
                ? List.of(Path.of("C:\\Program Files\\Amazon Corretto"), Path.of("C:\\Program Files\\Eclipse Adoptium"))
                : List.of(Path.of("/opt/blanket-jdk"));
    }

    public static Jdks system() {
        Platform p = Platform.current();
        return new Jdks(System.getenv(), defaultRoots(p), p);
    }

    /** The java launcher for {@code major}; throws with the folders searched when none is found. */
    public Path javaFor(int major) throws HarnessException {
        String var = "BLANKET_JDK" + major;
        String bin = platform.javaBinary();
        String home = env.get(var);
        if (home != null && !home.isEmpty()) {
            Path java = Path.of(home, "bin", bin);
            if (!Files.isRegularFile(java)) {
                throw new HarnessException(var + "=" + home + " has no bin/" + bin);
            }
            return java;
        }
        Pattern name = Pattern.compile("^jdk-?(1\\.)?" + major + "(?!\\d)", Pattern.CASE_INSENSITIVE);
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> dirs;
            try (Stream<Path> s = Files.list(root)) {
                dirs = s.sorted(Comparator.comparing((Path d) -> d.getFileName().toString()).reversed()).toList();
            } catch (IOException e) {
                continue;
            }
            for (Path dir : dirs) {
                Path java = dir.resolve("bin").resolve(bin);
                if (name.matcher(dir.getFileName().toString()).find() && Files.isRegularFile(java)) {
                    return java;
                }
            }
        }
        throw new HarnessException("no JDK " + major + " found: searched " + roots + " for folders jdk" + major + "*, jdk-"
                + major + "*, jdk1." + major + "*; set " + var + " to a JDK home to override");
    }
}
