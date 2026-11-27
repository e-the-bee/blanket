// SPDX-License-Identifier: GPL-3.0-only
plugins {
    `java-library`
}

// Mods compile against this module, so it is Java 8 bytecode (architecture.md section 2).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}

// C10 (testing-strategy.md): the API must stay source- and binary-compatible with the committed baseline, the T1 jar of
// the 0.2 API surface. japicmp runs as a plain JavaExec so no Gradle plugin is needed.
val japicmpCli = configurations.create("japicmpCli") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    japicmpCli(libs.japicmp)
}

val apiBaseline = layout.projectDirectory.file("baseline/blanket-api-0.2.0.jar")

val apiCompat = tasks.register<JavaExec>("apiCompat") {
    group = "verification"
    description = "Fails when the built API jar is not source- and binary-compatible with baseline/blanket-api-0.2.0.jar (japicmp)."
    val builtJar = tasks.jar
    dependsOn(builtJar)
    inputs.file(apiBaseline)
    classpath = japicmpCli
    mainClass.set("japicmp.JApiCmp")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--old", apiBaseline.asFile.absolutePath,
            "--new", builtJar.get().archiveFile.get().asFile.absolutePath,
            "--only-incompatible",
            "--error-on-binary-incompatibility",
            "--error-on-source-incompatibility",
        )
    })
}

tasks.named("check") { dependsOn(apiCompat) }
