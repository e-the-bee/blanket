# Architecture

Blanket is a build-time generator (settled decision 1). An author compiles a client mod once against
`blanket-api`; `blanket generate` turns that one jar into one native mod jar per requested target.
Nothing from Blanket runs in the game except the small runtime embedded, relocated, inside each jar.

## 1. Overview

```
 author's Java source ──javac --release N──►  mod.jar  (references blanket-api only)
                                                │
                                   blanket generate --targets t1,t2,...
                                                │
            ┌───────────────────────────── once ─┴──────────────────────────────┐
            │ 1 SCAN    mod.jar → used set (API members, types, events,          │
            │           library members, resources, guards)                      │
            └────────────────────────────────┬──────────────────────────────────┘
                     ┌───────────────────────┴───── per target (parallel) ────────┐
                     │ 2 RESOLVE  used set × bindings × target data → resolution   │
                     │ 3 LOWER    mod + embedded classes → runtime-named classes   │
                     │ 4 WIRE     entry class, mixins, native listeners           │
                     │ 5 SHRINK   class-level reachability                         │
                     │ 6 EMIT     metadata, assets, licenses, jar, report         │
                     └───────────────────────┬────────────────────────────────────┘
                                             ▼
   mymod-1.0+1.8.9-legacyfabric.jar  mymod-1.0+1.8.9-forge.jar  mymod-1.0+1.21.1-fabric.jar
   mymod-1.0+1.21.1-neoforge.jar     mymod-1.0+26.1.2-fabric.jar           + build/blanket/report.*
```

Inputs per target, all downloaded on the user's machine and pinned in `blanket.lock`
([libraries.md](libraries.md) §5): the Mojang version JSON and client jar, the mapping files for that
version ([mappings-and-versions.md](mappings-and-versions.md) §1; MCPConfig `joined.tsrg` for SRG eras),
the loader artifact, library jars, and the author's overlay jar if one is declared.

Pseudocode for every stage, with the problems found while writing it, is in
[pseudocode.md](pseudocode.md).

## 2. Modules

| Module | Contents | Java (bytecode) | Depends on | Published |
|---|---|---|---|---|
| `blanket-api` | public API: alias stub types, owned API classes (`Screen`, `Target`, `Key`, events, rendering types), `@Requires` | 8 | `blanket-runtime` (implementation detail of owned classes; not needed on the mod's compile classpath) | yes (mods compile against it) |
| `blanket-runtime` | event dispatch, GL backend (`Gl` with LWJGL 2 and 3 implementations), command list, glue stubs (`com.blanket.runtime.glue`), default font | 8 | LWJGL 2.9 and 3.x API jars, log4j-api 2.0-beta9 (compile only; all provided by the game) | yes (inside `blanket-core`'s distribution) |
| `blanket-bindings` | `targets.json`, `features.json`, `types/`, `glue/`, `hooks.json`, `events/`, adapter sources; maintainer tasks that build era stub jars and compile and remap adapters | data + 8 | `blanket-api`, `blanket-runtime`, stub jars (local only) | yes, as `blanket-bindings.jar` (data + compiled adapters in shipping namespaces) |
| `blanket-core` | the pipeline: scan, resolve, lower, wire, shrink, emit; downloads and cache; lockfile; report | 21 | ASM 9.x (BSD-3), mapping-io (Apache-2.0), Gson (Apache-2.0), `blanket-bindings`, `blanket-api`, `blanket-runtime` (as resources to embed) | yes |
| `blanket-emit` | the WIRE and EMIT stages: emitters per loader-era, Mixin and listener generation, metadata, jar writer | 21 | `blanket-core` | private until 1.0 (D-035); 0.x ships a placeholder that fails `generate` |
| `blanket-cli` | `generate`, `check`, `explain`, `lock`, `run` | 21 | `blanket-core`, `blanket-emit` | yes |
| `blanket-gradle` | Gradle plugin: `blanket { }` DSL, tasks per target | 21 | `blanket-core` (run in a forked JVM, §5) | yes (Gradle Plugin Portal) |
| `blanket-testkit` | headless GL golden-image harness; in-game smoke harness (instance setup, launch, screenshot check) | 21 | `blanket-core`, LWJGL 3 | internal (CI) |

Dependency rule: nothing in `blanket-api` or `blanket-runtime` depends on `blanket-core`; nothing in
`blanket-core` depends on a Minecraft or loader jar at compile time (it reads them as data). This keeps
the embedded code Java 8 and the generator free of game classes on its classpath.

## 3. Stages

### 3.1 Scan (once per build)

Reads every class in the mod jar with ASM and produces the **used set**:
- API members referenced (owner, name, descriptor) and API types mentioned anywhere (descriptors,
  casts, signatures, annotations, `ldc`);
- events subscribed: `GETSTATIC com/blanket/api/Events.<NAME>`;
- `Blanket.supports(Feature.X)` and `Blanket.hasLibrary("name")` call sites, which must have literal
  arguments;
- library classes and members referenced (anything present in a declared library's `compile` jar);
- resource paths passed as string constants to `Texture.resource` and `Font.resource` (they must be
  constants, so the files can be baked at build time; a non-constant path is a build error);
- target-independent rule violations ([lowering-and-adapters.md](lowering-and-adapters.md) §3).

It also bakes resources once: PNGs to raw RGBA, fonts to SDF atlases
([rendering-api.md](rendering-api.md) §2.3). Output is cached by the mod jar's hash.

### 3.2 Resolve (per target)

Inputs: used set, `blanket-bindings`, the target's downloads. For each used API member and type, pick
the first matching rule ([binding-format.md](binding-format.md) §2.2), resolve every reference to a
runtime name ([binding-format.md](binding-format.md) §2.4) by streaming the mapping files
([mappings-and-versions.md](mappings-and-versions.md) §4), verify presence in the client jar via the
class index, run the shape check, compute the adapter closure and add its references (iterated to a
fixed point), choose event recipes and verify hook targets, resolve libraries
([libraries.md](libraries.md) §3). The embedded runtime's own glue calls are part of the used set when
the runtime classes that make them are reachable (for example the GL backend's glue only when the mod
draws). That reachability is computed here, first, from the mod and overlay classes over the owned
API and runtime classes, and reused by SHRINK ([pseudocode.md](pseudocode.md) P-03).

Output: the **resolution** (a JSON file per target, cached) or a list of errors. All targets resolve
before any fails the build, so one run reports every problem.

### 3.3 Lower (per target)

Rewrites the mod's classes, the owned API classes and the reachable runtime classes from API/glue
references to runtime names, folds features, removes dead code, remaps types, recomputes frames and
checks class versions ([lowering-and-adapters.md](lowering-and-adapters.md) §2). Adapter classes,
already compiled, get only remapping and relocation.

### 3.4 Wire (per target)

Generates the classes that connect the mod to the loader:
- the entry class for the target's emitter ([loader-emitters.md](loader-emitters.md) §3);
- one Mixin class per (event, hook) the resolution selected, plus the Mixin config
  ([events-and-hooks.md](events-and-hooks.md) §3);
- native listener classes ([events-and-hooks.md](events-and-hooks.md) §5);
- the key-binding registration recipe ([events-and-hooks.md](events-and-hooks.md) §4.7).

All generated classes are version 52 and written with ASM directly in runtime names.

### 3.5 Shrink (per target)

Class-level reachability from the entry class, mixins, listeners and all mod classes over the
embedded runtime, owned API classes, adapters and shaded libraries
([lowering-and-adapters.md](lowering-and-adapters.md) §8, [libraries.md](libraries.md) §4.1).

### 3.6 Emit (per target)

Writes metadata, the language file for key bindings, icon, baked assets, licenses, and the jar:
- entries sorted by name, timestamps fixed to 1980-02-01, no build-machine data in the manifest, so the
  same inputs give a byte-identical jar (reproducible builds);
- the overlay jar for this target, if any, merged in ([build-and-gradle.md](build-and-gradle.md) §2.1);
- Blanket's license and attribution line (D-038, [libraries.md](libraries.md) §7);
- a final check that no `com/blanket/` reference remains and that every class is within the target's
  class-file limit;
- the **support report** (`build/blanket/report.txt` and `report.json`): per target, the events and the
  recipe kind used, features folded to false, `@Requires` methods stubbed, libraries with mode,
  version and license, warnings.

File name: `<modid>-<version>+<mc>-<loader>.jar` (loader written without dashes:
`legacyfabric`, `neoforge`).

## 4. Data flow and caching

| Artifact | Produced by | Key | Where |
|---|---|---|---|
| Downloads | resolve | URL, verified hash | `~/.blanket/cache/downloads` |
| Used set + baked assets | scan | mod jar SHA-256 | `build/blanket/scan/` |
| Resolution | resolve | used set hash, `blanket-bindings` version, lock entries for the target | `build/blanket/<target>/resolution.json` |
| Output jar | emit | all of the above | `build/blanket/out/` |

Resolve for a target is skipped when its key is unchanged; lower, wire, shrink and emit always rerun
(an estimated well under a second for a typical mod; measured in M5). Targets run in parallel threads.

## 5. Java baselines and what runs where

| Code | Runs | Java |
|---|---|---|
| Mod classes | in the game | compiled with `--release` = lowest Java baseline of the requested targets (8 when any target is ≤1.16.5); the plugin sets it ([build-and-gradle.md](build-and-gradle.md) §2) |
| `blanket-api` owned classes, `blanket-runtime`, adapters, generated classes | in the game, relocated into each jar | 8 (class version 52) |
| `blanket-core`, `blanket-cli` | on the developer's machine / CI | Java 21 bytecode; the generate step itself needs a JDK ≥ the highest requested target's Java baseline (25 for 26.x), because JDK references are checked against that JDK's `ct.sym` ([lowering-and-adapters.md](lowering-and-adapters.md) §6) |
| `blanket-gradle` | inside Gradle | Java 21 bytecode; runs the generator in a forked JVM from a Gradle Java toolchain of the required version, so Gradle's own JVM does not matter |
| `blanket-testkit` | CI | 21, plus game JVMs 8/17/21/25 for in-game tests |

## 6. Boundaries the design keeps

- **No runtime layer.** The only Blanket code in a player's game is inside the mod's own jar under
  `<base>.blanket.*`.
- **No whole-game model.** The generator reads only the symbols the mod uses
  ([mappings-and-versions.md](mappings-and-versions.md) §4). It is not a Loom: no deobfuscated game jar,
  no decompilation, no dev-time remapping of Minecraft.
- **All Minecraft knowledge is data plus adapters** in `blanket-bindings`, including the render
  backend's (glue bindings). `blanket-core` contains no Minecraft names.
- **Errors at build time.** Every unsupported combination the generator can see is a build error naming
  target, symbol and calling method; the only runtime failure modes are hooks broken by other mods
  (Mixin errors at game start) and the Vulkan backend case ([rendering-api.md](rendering-api.md) §3.6).
