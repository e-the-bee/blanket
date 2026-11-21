// SPDX-License-Identifier: GPL-3.0-only
// The M0 sample mod (milestones.md M0): compiled against blanket-api only, as a real mod would be.
plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    compileOnly(project(":blanket-api"))
}
