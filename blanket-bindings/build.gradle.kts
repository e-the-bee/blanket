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
    abstract val name: Property<String>

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
        option("--type", type); option("--sig", sig); option("--name", name); option("--alias", alias)
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

// validateBindings checks C1, C2, C3, C7 and C8 on every supported target. It is NOT a dependency of `check` yet: the
// real bindings are still being completed (see docs/progress/M1.md), and it downloads about 60 MB per target on first use.
// Wire `check` to it once it is green on the real bindings.
tasks.register<ValidateTask>("validateBindings") {
    configureTool("com.blanket.core.validate.ValidateMain", "Checks the bindings against every supported target's game data (C1, C2, C3, C7, C8).")
}
