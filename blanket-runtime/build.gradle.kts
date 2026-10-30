// SPDX-License-Identifier: GPL-3.0-only
plugins {
    `java-library`
}

// Embedded in every generated jar, so Java 8 bytecode (architecture.md section 2).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

dependencies {
    implementation(project(":blanket-api"))
}
