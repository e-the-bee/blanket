// SPDX-License-Identifier: GPL-3.0-only
plugins {
    application
}

dependencies {
    implementation(project(":blanket-core"))
}

application {
    mainClass.set("com.blanket.cli.Main")
}
