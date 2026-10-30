// SPDX-License-Identifier: GPL-3.0-only
plugins {
    `java-library`
}

// Embedded in every generated jar, so Java 8 bytecode (architecture.md section 2).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}

// One GL backend per LWJGL major. Each is compiled against its own LWJGL API jars, which the game provides.
val lwjgl2 by sourceSets.creating { compileClasspath += sourceSets.main.get().output }
val lwjgl3 by sourceSets.creating { compileClasspath += sourceSets.main.get().output }

// LWJGL 2 is not on Maven Central; Mojang's library host serves the same jar the 1.8.9 game downloads.
repositories {
    maven {
        name = "minecraftLibraries"
        url = uri("https://libraries.minecraft.net/")
        content { includeGroup("org.lwjgl.lwjgl") }
    }
}

dependencies {
    implementation(project(":blanket-api"))

    "lwjgl2CompileOnly"("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
    "lwjgl3CompileOnly"(libs.lwjgl.core)
    "lwjgl3CompileOnly"(libs.lwjgl.opengl)

    "testImplementation"(project(":blanket-api"))
}

tasks.jar {
    from(lwjgl2.output, lwjgl3.output)
}
