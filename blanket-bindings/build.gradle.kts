// SPDX-License-Identifier: GPL-3.0-only
plugins {
    `java-library`
}

dependencies {
    api(project(":blanket-api"))
    api(project(":blanket-runtime"))
}

// ---------------------------------------------------------------------------------------------------------------
// Contributor tools (contributing.md section 2). They run blanket-core's classes against this folder (the bindings),
// so the classpath is blanket-core with everything it needs, which includes blanket-api and blanket-runtime: the
// classes whose members validateBindings checks for coverage.
// ---------------------------------------------------------------------------------------------------------------

val tools by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    tools(project(":blanket-core"))
}

/** A contributor tool: a JavaExec of one blanket-core main class, started in this folder. */
abstract class BlanketTool : JavaExec() {
    init {
        group = "blanket"
        argumentProviders.add(CommandLineArgumentProvider { toolArgs() + offlineArgs() })
    }

    /** The command line of the tool, from the task's options. */
    protected abstract fun toolArgs(): List<String>

    /** Gradle's own --offline reaches the tool as its --offline flag. */
    private fun offlineArgs(): List<String> = if (project.gradle.startParameter.isOffline) listOf("--offline") else emptyList()
}

abstract class LookupTask : BlanketTool() {
    @get:Input
    @get:Optional
    @get:Option(option = "ref", description = "Reference to resolve: [ns:]Class[#member], for example \"Entity#getX()D\".")
    abstract val ref: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "target", description = "A target id (loader:mc) to resolve on; repeat for several. Default: every supported target.")
    abstract val target: ListProperty<String>

    override fun toolArgs(): List<String> {
        val args = mutableListOf("--ref", ref.orNull ?: throw GradleException("lookup needs --ref \"Class#member\""))
        target.getOrElse(emptyList()).forEach { args += listOf("--target", it) }
        return args
    }
}

abstract class ScaffoldTask : BlanketTool() {
    @get:Input @get:Optional @get:Option(option = "kind", description = "member, type, event, feature or target.")
    abstract val kind: Property<String>

    @get:Input @get:Optional @get:Option(option = "type", description = "API type that gets the member (--kind member).")
    abstract val type: Property<String>

    @get:Input @get:Optional @get:Option(option = "sig", description = "Java signature of the member, for example \"float armor()\".")
    abstract val sig: Property<String>

    @get:Input @get:Optional @get:Option(option = "name", description = "Name of the new type, event or feature.")
    abstract val newName: Property<String>   // not `name`: that would clash with Task.getName()

    @get:Input @get:Optional @get:Option(option = "alias", description = "Mojang class a new type aliases.")
    abstract val alias: Property<String>

    @get:Input @get:Optional @get:Option(option = "owned", description = "The new type is owned (no alias).")
    abstract val owned: Property<Boolean>

    @get:Input @get:Optional @get:Option(option = "mc", description = "Release id for --kind target.")
    abstract val mc: Property<String>

    @get:Input @get:Optional @get:Option(option = "java", description = "Java level for --kind target; default: from Mojang's version JSON.")
    abstract val javaLevel: Property<String>

    override fun toolArgs(): List<String> {
        val args = mutableListOf("--kind", kind.orNull ?: throw GradleException("scaffold needs --kind member|type|event|feature|target"))
        fun option(flag: String, value: Property<String>) { if (value.isPresent) args += listOf(flag, value.get()) }
        option("--type", type); option("--sig", sig); option("--name", newName); option("--alias", alias)
        option("--mc", mc); option("--java", javaLevel)
        if (owned.getOrElse(false)) args += "--owned"
        return args
    }
}

abstract class ValidateTask : BlanketTool() {
    override fun toolArgs(): List<String> = emptyList()
}

fun BlanketTool.configureTool(mainClassName: String, text: String) {
    description = text
    classpath = tools
    mainClass.set(mainClassName)
    workingDir = projectDir
}

tasks.register<LookupTask>("lookup") {
    configureTool("com.blanket.core.lookup.LookupMain", "Resolves a reference on every supported target and prints the runtime names or the failing step.")
}

tasks.register<ScaffoldTask>("scaffold") {
    configureTool("com.blanket.core.lookup.ScaffoldMain", "Adds API stubs and binding rule lists for a member, type, event, feature or target.")
}

// validateBindings checks C1, C2, C3, C7 and C8 on every supported target. It downloads about 60 MB per target on
// first use. `check` depends on it, so the real bindings must stay green. It runs after remapAdapters, whose output
// (build/adapters) the plans of the checked bindings read.
tasks.register<ValidateTask>("validateBindings") {
    configureTool("com.blanket.core.validate.ValidateMain", "Checks the bindings against every supported target's game data (C1, C2, C3, C7, C8).")
    dependsOn("remapAdapters")
}

// stubJars (build-and-gradle.md section 6, lowering-and-adapters.md section 5.1): per adapter set the era stub jar
// build/stubs/<version>-<authoring>.jar, the API view jar build/stubs/<set>-api.jar and the library list
// build/stubs/<set>-libs.txt. It downloads the stub versions' client jars, mappings and libraries on first use.
val apiClasses by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    apiClasses(project(":blanket-api"))
    apiClasses(project(":blanket-runtime"))
}

abstract class StubJarsTask : BlanketTool() {
    @get:Input @get:Optional @get:Option(option = "set", description = "An adapter set to build (legacy, modern, unobf); repeat for several. Default: every set.")
    abstract val set: ListProperty<String>

    /** The compiled blanket-api and blanket-runtime, which the API view jar is made from. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiJars: ConfigurableFileCollection

    override fun toolArgs(): List<String> {
        val args = mutableListOf<String>()
        apiJars.files.forEach { args += listOf("--classes", it.absolutePath) }
        set.getOrElse(emptyList()).forEach { args += listOf("--set", it) }
        return args
    }
}

tasks.register<StubJarsTask>("stubJars") {
    configureTool("com.blanket.core.adapters.StubJarsMain", "Builds the era stub jars, the API view jars and the library lists of every adapter set into build/stubs.")
    apiJars.from(apiClasses)
    // the jars depend on the set files, the alias rules of the type files and targets.json; downloads are versioned
    inputs.files(fileTree("adapters") { include("*/adapter-set.json") }, fileTree("types"), file("targets.json"))
        .withPropertyName("bindings").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.buildDirectory.dir("stubs"))
}

// compileAdapters and remapAdapters (build-and-gradle.md section 6, lowering-and-adapters.md section 5.2).
// compileAdapters: javac --release 8 per set, adapters/<set>/src against build/stubs (stub jar, API view jar,
// libraries) into build/adapter-classes/<set>; adapters/common/src (the compile-only @Stubs annotation) into
// build/adapter-classes/common. A class annotated @Stubs("<version>") gets its own javac run against that version's
// stub jar, built on first use into build/stubs-extra.
// remapAdapters: authoring names to the set's shipping names, package com/blanket/adapters/ to
// com/blanket/adapters/<set>/, into build/adapters/<set>/ with adapter-refs.json beside the classes. The generator
// and validateBindings read build/adapters (they are not packed into this project's jar, M2 plan section 3).
abstract class AdapterTask : BlanketTool() {
    @get:Input @get:Optional @get:Option(option = "set", description = "An adapter set (legacy, modern, unobf); repeat for several. Default: every set.")
    abstract val set: ListProperty<String>

    override fun toolArgs(): List<String> = set.getOrElse(emptyList()).flatMap { listOf("--set", it) }
}

tasks.register<AdapterTask>("compileAdapters") {
    configureTool("com.blanket.core.adapters.CompileAdaptersMain", "Compiles every adapter set against its era stub jar into build/adapter-classes.")
    dependsOn("stubJars")
    inputs.files(fileTree("adapters")).withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(layout.buildDirectory.dir("stubs")).withPropertyName("stubs").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.buildDirectory.dir("adapter-classes"))
    outputs.dir(layout.buildDirectory.dir("stubs-extra"))
}

tasks.register<AdapterTask>("remapAdapters") {
    configureTool("com.blanket.core.adapters.RemapAdaptersMain", "Remaps the compiled adapters to their shipping names into build/adapters, with adapter-refs.json.")
    dependsOn("compileAdapters")
    inputs.files(fileTree("adapters") { include("*/adapter-set.json") }).withPropertyName("sets").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(layout.buildDirectory.dir("adapter-classes"), layout.buildDirectory.dir("stubs"), layout.buildDirectory.dir("stubs-extra"))
        .withPropertyName("classes").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.buildDirectory.dir("adapters"))
}

tasks.named("check") { dependsOn("validateBindings") }
