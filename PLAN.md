# Blanket v2 — Plan (index)

Status: planning complete; M0 done 2026-10-29 (all six core targets pass in real games, `blanket-emit/m0-spike/NOTES.md`). Next: M1.
v1 (read-only reference): `C:\Users\donov\Documents\lotus\.other\blanket`.

## The idea

You write a Minecraft client mod once, in plain Java, against the Blanket API, and compile it once.
At build time you pick targets (loader + Minecraft version, e.g. `fabric:1.21.1`, `neoforge:1.21.1`,
`legacy-fabric:1.8.9`, `forge:1.20.1`, `fabric:26.1.2`). Release 1.0 supports every Fabric and Forge
release from 1.8.9 to the newest 26.x, and NeoForge 1.20.1, 1.21.1, 1.21.11 and the newest 26.x. Blanket generates a native mod jar per target in
that loader's own format. Players install nothing extra; there is no Blanket at runtime beyond the small
relocated runtime inside each jar.

## Settled decisions (inputs, not reopened)

1. Blanket is a build-time generator. No runtime layer, no compatibility mod, no Java agent.
2. Mod source never contains a Minecraft type, mapping name, loader API, or version check.
3. Mappings are made only for symbols the mod actually uses, per target, at build time.
4. Blanket has its own 2D and 3D rendering API.
5. Third-party libraries are mapped per loader and version by a project-owned JSON file
   (`$version` templating, regex matching).
6. Client-side only for v1; 1.8.9 to the 26.x releases is the ambition.
7. Built mods are frozen: a new Minecraft version needs a rebuild.
8. Mojang mappings are used at build time only; no mapping file ships; no MCP names. *Amended
   (D-033):* MCPConfig's obf→SRG table is read at build time for SRG-era Forge/NeoForge targets.

From the answered questions (D-032–D-042): public tool grown on the author's own mods; GPL-3.0-only
with an attribution term and a Minecraft linking permission, group `io.github.e-the-bee`; 0.1–0.9
withhold the emitters, 1.0 publishes them; per-target overlay jars built with the loader's own
toolchain; widgets, world-space panels, input routing and packets in 1.0; Java only. Adding features
is a data-and-adapter change any contributor can make (D-045, docs/contributing.md).

## Architecture

```
 author's Java ──javac --release N──► mod.jar (references blanket-api only)
                                         │  blanket generate --targets ...
   ┌──────────────────────────── once ───┴─────────────────────────────┐
   │ 1 SCAN     used set: API members/types, events, libraries, assets │
   └───────────────────────────────┬───────────────────────────────────┘
        ┌──────────────────────────┴────────── per target ─────────────┐
        │ 2 RESOLVE  bindings + mapping files (streamed, used set only) │
        │            + client jar check → runtime names                 │
        │ 3 LOWER    rewrite API calls/types → Minecraft members or     │
        │            adapters; fold features; remap; frames             │
        │ 4 WIRE     entry class, generated Mixins / native listeners   │
        │ 5 SHRINK   class-level reachability (runtime, adapters, libs) │
        │ 6 EMIT     metadata, assets, licenses, reproducible jar,      │
        │            support report                                     │
        └──────────────────────────┬────────────────────────────────────┘
                                   ▼
  mymod-1.0+1.8.9-legacyfabric.jar  mymod-1.0+1.8.9-forge.jar  mymod-1.0+1.21.1-fabric.jar
  mymod-1.0+1.21.1-neoforge.jar     mymod-1.0+26.1.2-fabric.jar
```

Runtime names per loader-era: Fabric intermediary (1.14–1.21.11), Legacy Fabric intermediary
(≤1.13.2), Mojang names (NeoForge 1.20.2+, Forge 1.20.6+), official names (26.x), notch names on Forge
1.8.9–1.12.2 (FML remaps them at load), SRG names on Forge 1.13.2–1.16.5, Mojang classes with SRG
members on Forge 1.17–1.20.4 and NeoForge 1.20.1.

## Documents

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | pipeline stages, data flow, modules, Java baselines, what runs where |
| [docs/api-surface.md](docs/api-surface.md) | the complete v1 API with demand citations, alias vs owned per type |
| [docs/binding-format.md](docs/binding-format.md) | binding data schema and twelve worked examples |
| [docs/lowering-and-adapters.md](docs/lowering-and-adapters.md) | bytecode rewriting, adapters, era stub jars, lambdas, generics, frames, shrink |
| [docs/events-and-hooks.md](docs/events-and-hooks.md) | v1 events, recipes per loader-era, failure reporting, threading |
| [docs/loader-emitters.md](docs/loader-emitters.md) | per loader-era: namespace, metadata, entry point, Mixin, Java, jar layout |
| [docs/mappings-and-versions.md](docs/mappings-and-versions.md) | mapping sources, symbol identity, the 1.13/1.14 boundary, target matrix, Mojang terms |
| [docs/rendering-api.md](docs/rendering-api.md) | `Canvas2D`/`Draw3D`/resources, the `gl` backend, glue, the `gpu` backend, tests |
| [docs/libraries.md](docs/libraries.md) | `blanket-libs.json`, resolution, modes, compatibility check, lockfile, relocation, licenses |
| [docs/build-and-gradle.md](docs/build-and-gradle.md) | CLI, Gradle DSL and tasks, running and debugging in a real game, layouts |
| [docs/testing-strategy.md](docs/testing-strategy.md) | test layers, checks C1–C10, in-game smoke tests, CI matrix and cost |
| [docs/milestones.md](docs/milestones.md) | M0–M10 with real-game gates; 0.1–0.9 per milestone, 1.0 at the end of M9 |
| [docs/contributing.md](docs/contributing.md) | versioning (v1 → v1.1 add only, breaking → v2), rules for pull requests, tooling, recipes for adding a member/type/event/packet/feature/target |
| [docs/pseudocode.md](docs/pseudocode.md) | pseudocode for every stage and the runtime, with the problems it found (P-nn) |
| [docs/risks.md](docs/risks.md) | risks, spikes, fallbacks, adversarial pass |
| [docs/decisions.md](docs/decisions.md) | decision log D-001… |
| [docs/research-log.md](docs/research-log.md) | every verified fact with source |
| [docs/reuse-from-v1.md](docs/reuse-from-v1.md) | file-by-file verdict on v1 code |
| [open_questions.md](open_questions.md) | your answers (all resolved; decisions D-032–D-042) |
| `blanket-emit/m0-spike/NOTES.md` | M0 result, surprises, how to rerun (private repository) |

## Changes from the previous revision of this plan

Recorded with evidence in [docs/decisions.md](docs/decisions.md): value types are aliased too (D-003);
`forge:1.8.9` joins the first release because notch names work there (D-009, D-012); SRG eras were
excluded (D-010) and are now targets (D-033); the five-target first release became the full matrix (D-034); mapping-io replaces v1's parsers (D-011); core-profile shaders are GLSL 1.50, not 330
(D-016); the second render backend is `gpu` on Mojang's GPU abstraction, needed because Minecraft is
moving to Vulkan (D-017); the generator runs on a JVM at least as new as the newest target's Java
(D-026).
