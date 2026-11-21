// SPDX-License-Identifier: GPL-3.0-only
plugins {
    application
    `java-library`
}

dependencies {
    implementation(project(":blanket-core"))
    testImplementation(libs.asm)
}

application {
    mainClass.set("com.blanket.cli.Main")
}

// `./gradlew :blanket-cli:run --args="check ..."` runs from the repository root, so the default ./blanket-bindings is found.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
