// SPDX-License-Identifier: GPL-3.0-only
plugins {
    `java-library`
}

dependencies {
    api(project(":blanket-bindings"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    implementation(libs.asm.analysis)
    implementation(libs.mapping.io)
    implementation(libs.gson)
    testImplementation(libs.asm.util)
}
