// SPDX-License-Identifier: GPL-3.0-only
// The L4 harness (blanket-testkit): provisions a game instance per target, runs a --testkit jar in a flat world
// on a local server and turns testkit-result.json plus the game log into a verdict. Java 21 and JUnit come from
// the root build's java-library rules.
plugins {
    `java-library`
    application
}

dependencies {
    implementation(libs.gson)
}

application {
    mainClass.set("com.blanket.testkit.Main")
}

// Tests tagged "online" download real game files; `build` never runs them, `onlineTest` does.
tasks.test {
    useJUnitPlatform {
        excludeTags("online")
    }
}

tasks.register<Test>("onlineTest") {
    description = "Runs the testkit tests that download game files (tag: online)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("online")
    }
}
