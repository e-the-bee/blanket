// SPDX-License-Identifier: GPL-3.0-only
plugins {
    `java-library`
}

// Mods compile against this module, so it is Java 8 bytecode (architecture.md section 2).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}
