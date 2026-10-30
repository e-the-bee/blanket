# Build, CLI and Gradle

How an author builds, runs and debugs a Blanket mod, and how Blanket itself is built.

## 1. CLI (`blanket-cli`)

The CLI is the generator's front door; the Gradle plugin calls the same `blanket-core` entry points.

| Command | What it does |
|---|---|
| `blanket generate --mod <mod.jar> --config <blanket.json> --targets <t,...> [--libs blanket-libs.json] [--lock blanket.lock] [--out <dir>]` | all six stages ([architecture.md](architecture.md) §3); writes one jar per target and the support report; exit code 1 on any error |
| `blanket check …same options…` | scan + resolve only, prints the support report; for CI and editors |
| `blanket explain <ApiType.member> --target <t>` | prints the rule chosen, every resolution step (namespace by namespace), the runtime name, and, on failure, members of the expected owner with the same descriptor |
| `blanket explain --symbol <ref> --target <t>` | the same for a raw symbol reference (binding authors) |
| `blanket lock [--target <t>]` | resolves every library and input for the targets and rewrites `blanket.lock` |
| `blanket run --target <t> [--instance <dir>] [--debug <port>]` | provisions a clean game instance for the target, installs the generated jar, launches the game (§4) |
| `blanket targets` | lists the supported matrix and every valid loader-era |

`blanket.json` (written by the Gradle plugin, or by hand for CLI use):

```json
{
  "schema": 1,
  "mod": {
    "id": "mymod", "name": "My Mod", "version": "1.0.0",
    "description": "…", "authors": ["…"], "license": "MIT",
    "icon": "icon.png", "entrypoint": "com.example.mymod.MyMod", "basePackage": "com.example.mymod"
  },
  "lang": {
    "key.mymod.toggle": "Toggle overlay",
    "key.categories.mymod.main": "My Mod"
  },
  "fonts": { "glyphs": "latin-1" }
}
```

Validation: `id` matches `^[a-z][a-z0-9_]{1,63}$` ([loader-emitters.md](loader-emitters.md) §2.1);
`version` is any string without spaces; `entrypoint` names a class in the mod jar that implements
`ClientMod`; `basePackage` is a prefix of the entrypoint's package and of no Minecraft or loader
package.

## 2. Gradle plugin (`blanket-gradle`)

```kotlin
plugins {
    java
    id("com.blanket") version "1.0.0"
}

blanket {
    mod {
        id = "mymod"
        name = "My Mod"
        version = project.version.toString()
        description = "Shows things"
        authors = listOf("me")
        license = "GPL-3.0-or-later"           // must be GPL-3.0-compatible (D-038)
        icon = file("src/main/resources/assets/mymod/icon.png")
        entrypoint = "com.example.mymod.MyMod"
        basePackage = "com.example.mymod"      // default: entrypoint's package
    }
    targets {
        legacyFabric("1.8.9")
        forge("1.8.9")
        fabric("1.21.1")
        neoforge("1.21.1")
        fabric("26.1.2")
        forge("1.20.1")
        // or: allFabric(), allForge(), neoforgeSupported()
    }
    overlays {                                 // optional, D-036
        target("fabric:1.21.1") {
            jar = file("overlays/fabric-1.21.1/build/libs/overlay.jar")   // built with Loom, in runtime names
            provide("com.example.mymod.ConfigBridge", "com.example.mymod.fabric.ModMenuBridge")
            fabricEntrypoint("modmenu", "com.example.mymod.fabric.ModMenuEntry")
        }
    }
    libraries = file("blanket-libs.json")      // optional
    lang {
        key("toggle", "Toggle overlay")         // → key.mymod.toggle
        category("main", "My Mod")              // → key.categories.mymod.main
    }
    fonts { glyphs = "latin-1" }
}
```

What applying the plugin does:
- adds `com.blanket:blanket-api` to `compileOnly`, and every library's `compile` version from
  `blanket-libs.json` to `compileOnly`;
- sets `JavaCompile.options.release` to the lowest Java baseline of the declared targets (8 for any
  target ≤1.16.5, 17, 21 or 25 otherwise), and fails configuration if the author set a higher one;
- writes `build/blanket/blanket.json` from the DSL;
- registers the tasks below. `assemble` depends on `blanketGenerate`.

| Task | Inputs | Outputs |
|---|---|---|
| `blanketGenerate` | the `jar` task's output, `blanket.json`, `blanket-libs.json`, `blanket.lock`, the `blanket-bindings` version | `build/blanket/out/*.jar`, `build/blanket/report.{txt,json}` |
| `blanketGenerate<Loader><Version>` (e.g. `blanketGenerateFabric1_21_1`) | same, one target | one jar |
| `blanketCheck` | same | report only |
| `blanketLock` | same | `blanket.lock` |
| `blanketRun<Loader><Version>` | the target's jar | runs the game (§4) |
| `blanketExplain` | `--member`, `--target` options | console |

Tasks declare their inputs and outputs, so Gradle's up-to-date checks and build cache work, and they
are configuration-cache compatible (no project access at execution time). The generator runs through
the Gradle Worker API in process isolation with a Java toolchain whose version is at least the highest
target's Java baseline ([architecture.md](architecture.md) §5).

### 2.1 Overlays (per-target escape hatch, D-036)

An overlay is a jar the author builds with the target loader's own toolchain (Loom, ForgeGradle,
ModDevGradle), so it already uses the target's runtime names and may reference Minecraft and the loader
directly. Blanket does not compile it. Its classes go through SCAN and LOWER like the mod's own: an
overlay compiled against the mod jar and `blanket-api` may contain API types and members, and those
are lowered for its target; its Minecraft and loader references are already in runtime names and are
left alone ([pseudocode.md](pseudocode.md) P-20). The overlay's compile classpath is the mod's normal
jar, `blanket-api`, and whatever its toolchain provides.

Rules, checked by `blanketGenerate`:
1. The overlay contains no metadata file and no loader entry class of its own (`fabric.mod.json`,
   `mods.toml`, `@Mod`); Blanket writes the only one. Fabric entry points it needs (for example
   `modmenu`) are declared with `fabricEntrypoint` and merged into `fabric.mod.json`.
   The overlay may contain its own Mixin classes and one Mixin config, declared with
   `mixins("overlay.mixins.json")`; Blanket lists it next to its own config in the target's metadata
   (`mixins` in `fabric.mod.json`, `[[mixins]]` in NeoForge's toml, the `MixinConfigs` manifest entry
   on Forge 1.15.2+). Declaring Mixins for a target without Mixin (Forge before 1.15.2) is a build
   error. This is how an author cancels a packet or changes any vanilla behaviour the API does not
   cover (D-043); the Mixin is the author's, written against that target's real classes.
2. The mod reaches the overlay only through `Blanket.overlay(Interface.class)`. The interface lives in
   the mod's source and uses only JDK types and owned Blanket types. `provide(interface, impl)` binds
   it per target; on targets without a binding the call folds to `null`, so the mod writes
   `ConfigBridge b = Blanket.overlay(ConfigBridge.class); if (b != null) b.open();`.
3. Overlay classes may reference the mod's classes freely; after lowering both sides use the same
   runtime descriptors. `provide(interface, impl)` is checked against the class index: `impl` must
   implement the interface and have a public no-argument constructor.
4. A path present in both the generated jar and the overlay is a build error.
5. The overlay is merged after SHRINK (it is a root, never shrunk) and before EMIT writes the jar, and
   it is listed in the support report.

## 3. Language files and other generated assets

Key-binding names and categories are translation keys; the plugin's `lang` block provides English
display names. The emitter writes them as `assets/<modid>/lang/en_us.json` (1.13+) or
`assets/<modid>/lang/en_US.lang` (≤1.12.2) (**ASSUMED** formats, spike S-04/S-05). Authors may add more
languages as resource files in either format; the emitter converts each to the target's format.

## 4. Running and debugging in a real game

`blanketRun<Target>` (CLI: `blanket run`) gives the author the exact jar players get, in a clean
instance:

1. **Provision** `run/<target>/` once: the client jar, libraries and natives from the Mojang version
   JSON; asset index and objects; the loader:
   - Fabric / Legacy Fabric: Fabric Loader and its libraries from the loader meta profile; launch
     main class `net.fabricmc.loader.impl.launch.knot.KnotClient` (**ASSUMED**, S-14);
   - NeoForge: the NeoForge installer run in `--install-client` mode into the instance
     (**ASSUMED**, S-14);
   - Forge 1.8.9: Forge universal jar with LaunchWrapper, main class
     `net.minecraft.launchwrapper.Launch` and `--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker`
     (**ASSUMED**, S-14).
   Downloads go to the shared cache and are pinned in `blanket.lock` like other inputs.
2. **Install** the freshly generated jar into `run/<target>/mods/` (removing older Blanket outputs of
   the same mod id).
3. **Launch** with the target's Java (Gradle toolchain), offline mode and a fixed username for local
   single-player testing. Online play uses the author's own launcher with the jar; Blanket never
   handles account credentials.
4. **Debug** with `--debug <port>` / `blanketRun… --debug-jvm`: the game JVM starts with
   `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=<port>`. IDE breakpoints in mod
   source work because mod class names, line numbers and local variable names are unchanged by
   lowering ([lowering-and-adapters.md](lowering-and-adapters.md) §4.10). Method-body changes can be
   hot-swapped through the debugger as long as they do not add API references; anything else needs
   regenerate and restart.

There is no Loom-style dev environment with a deobfuscated game: the generator never needs Minecraft
on the mod's compile classpath, and running production jars avoids a second code path.

**IDE run configurations (D-041).** `blanketIdeRuns` writes one IntelliJ run configuration
(`.idea/runConfigurations/Blanket_<Target>.xml`) and one VS Code launch entry
(`.vscode/launch.json`, merged by name) per target. Each runs `blanketRun<Target> --debug-jvm` and
attaches the debugger to the game JVM's port.

## 5. A mod project's layout

```
mymod/
  settings.gradle.kts
  build.gradle.kts            the blanket { } block
  blanket-libs.json           optional
  blanket.lock                committed
  src/main/java/com/example/mymod/MyMod.java      implements ClientMod
  src/main/resources/assets/mymod/icon.png
  src/main/resources/assets/mymod/textures/…      for Texture.resource(...)
  src/main/resources/assets/mymod/fonts/…         for Font.resource(...)
  run/                        provisioned game instances (git-ignored)
  build/blanket/out/          generated jars
```

## 6. Blanket's own repository

Public repository `github.com/e-the-bee/blanket`, private repository `github.com/e-the-bee/blanket-emit`;
both are created before M1 starts (M0 needs only the private one, D-048).

```
blanket/
  settings.gradle.kts
  blanket-api/        blanket-runtime/     blanket-bindings/
  blanket-core/       blanket-cli/         blanket-gradle/      blanket-testkit/
  blanket-emit/       (git submodule of the private repository `e-the-bee/blanket-emit` until 1.0, D-035)
  samples/            hud-box/ and the later samples (one source set each; `m0-spike` lives in the private repository, D-048)
  docs/               these documents
```

Maintainer-only tasks in `blanket-bindings` (they download game data and never publish it):

| Task | Does |
|---|---|
| `stubJars` | builds era stub jars for each adapter set's `stubs` versions into `build/stubs/` ([lowering-and-adapters.md](lowering-and-adapters.md) §5.1) |
| `compileAdapters` | javac `--release 8` per set (and per `@Stubs` class) |
| `remapAdapters` | authoring → shipping namespace |
| `validateBindings` | checks C1–C9 against every supported target ([testing-strategy.md](testing-strategy.md) §2) |
| `bindingsJar` | data + remapped adapters → `blanket-bindings.jar` |
| `updateTargets` | regenerates the matrix in `targets.json` from loader metadata ([binding-format.md](binding-format.md) §3.1) |

Gradle 9.x, Kotlin DSL, version catalog. External build dependencies: ASM, mapping-io, Gson (generator),
LWJGL 2.9.4 and 3.3.x API jars plus log4j-api 2.0-beta9 (`compileOnly` for the runtime), JUnit 5
(tests). Nothing from Loom, ModDevGradle or ForgeGradle is used anywhere (v1's loader scaffolding
never ran because those toolchains need forked JDKs and network access in odd ways; v2 does not need
them, [reuse-from-v1.md](reuse-from-v1.md)).
