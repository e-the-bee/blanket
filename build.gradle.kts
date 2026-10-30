// SPDX-License-Identifier: GPL-3.0-only
// Shared build rules. Module-specific Java levels are set in each module (api and runtime: --release 8).

subprojects {
    group = "io.github.e-the-bee"
    version = "0.0.0"

    repositories {
        mavenCentral()
    }

    plugins.withId("java-library") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
        dependencies {
            "testImplementation"(platform(libs.junit.bom))
            "testImplementation"(libs.junit.jupiter)
            "testRuntimeOnly"(libs.junit.platform.launcher)
        }
    }
}
