# Reuse from v1

v1 lives at `C:\Users\donov\Documents\lotus\.other\blanket` (read-only). It is ~20,400 lines of Java
(all `*.java`, tests included) built around a runtime capability registry, runtime version detection
and a shared dependency mod. v2 is a build-time generator ([architecture.md](architecture.md)), so most
v1 code has no place to go. This file gives a verdict per file or package after reading the code, not
the docs.

Verdicts:
- **keep**: port almost as is (package rename, small fixes listed).
- **rewrite**: the idea or algorithm carries over; the code does not.
- **drop**: no equivalent in v2.

Porting rule: nothing is copied without a test in the new module that exercises it against real
data ([testing-strategy.md](testing-strategy.md)). v1's own tests ran on synthetic fixtures only.

## Mapping and game-data code (`buildtools/.../build/mappings`, `mapping-verify`)

| v1 file | Verdict | Reason (from reading the code) |
|---|---|---|
| `mappings/ProguardParser.java` | **drop** (replaced by mapping-io) | Correct for the anchor direction, but stores class names dotted and emits descriptors only in deobfuscated names; v2 needs obf-side descriptors too. FabricMC's `mapping-io` reads ProGuard and Tiny v1/v2 with a streaming visitor ([research-log.md](research-log.md) R-PA-08); see D-011 in [decisions.md](decisions.md). |
| `mappings/TinyV2Parser.java` | **drop** (replaced by mapping-io) | Header-driven namespace choice and the descriptor remap (`remapDescriptor`) are right and are the behaviour to test mapping-io usage against. Two passes over a String-split file. |
| `mappings/TypeDescriptors.java` | **drop** | Only needed by the ProGuard parser. |
| `mappings/MappingSet.java`, `MappingEntry.java`, `SymbolKind.java`, `Provenance.java` | **drop** | A whole-version symbol table. Settled decision 3 forbids modelling all of Minecraft; v2 resolves only the used set ([mappings-and-versions.md](mappings-and-versions.md) §4). |
| `mappings/RealNameTableParser.java` + `verify/RealNameTableExtractor.java` | **drop** | Extracts every 26.x symbol into a text table. v2 checks the needed symbols directly in the 26.x jar through the class index. |
| `mappings/MappingIngestor.java` | **drop** | Provenance stamping for the dropped table model. The useful fact it encodes (pre-1.14 intermediary is a community backport) is in [mappings-and-versions.md](mappings-and-versions.md). |
| `verify/ArtifactFetcher.java` | **keep** (with fixes) | Solid: manifest → version JSON → client jar and `client_mappings`, Fabric and Legacy Fabric intermediary jars, manual redirect handling (Legacy Fabric's maven redirects), SHA-1 check against Mojang's advertised hash, three retries, atomic `.part` writes, a pin record. Fixes: move to `java.net.http.HttpClient` (v2 core runs on Java 17+), add SHA-256 for the lockfile, add NeoForge/Forge maven artifacts, make the intermediary URL selection data-driven (Fabric 26.x intermediary is an empty file, R-MAP-07). |
| `verify/JarIndex.java` | **rewrite** | Right idea (ASM, `SKIP_CODE`, no classloading) but indexes every class in the jar. v2's class index opens the jar once and reads classes lazily by entry name, plus their supertypes for hierarchy queries ([lowering-and-adapters.md](lowering-and-adapters.md) §6). |
| `verify/ObfResolver.java` | **drop** | A hand-rolled second Tiny reader; mapping-io covers it. |
| `verify/Tiers.java`, `Tier.java` | **drop** | Hard-coded tier list. v2 reads targets from `targets.json` ([binding-format.md](binding-format.md) §3). |
| `mapping-verify` tests (`IngestionInvariantsTest`, `DiffingInvariantsTest`, `ProviderGauntletTest`) | **rewrite** | The "real-data test, self-skips when offline" pattern is good and reused for resolver tests. The assertions target the dropped table model. |
| `diff/*` (`StructuralDiffer`, `ContinuityChain`, `FlagQueue`, `TierPairs`, ...) | **drop** | Whole-version diffing and a review queue. v2 follows one symbol through intermediary ids only when a binding asks for it. The classification idea (same descriptor = rename, different = shape change) survives as resolver error messages. |
| `buildtools/fixtures/ingestion/*` | **drop** | Synthetic data; v2 tests run against real downloaded mapping files ([testing-strategy.md](testing-strategy.md)). |

## Bytecode code

| v1 file | Verdict | Reason |
|---|---|---|
| `agent/AsmClassPatcher.java` | **rewrite** (knowledge only) | No runtime patching in v2. Two lessons carry into the legacy Forge coremod fallback and the lowering pass: (1) skip abstract/native methods so a hook target without a body counts as unmatched; (2) a stack-neutral insert lets you copy frames and avoid `getCommonSuperClass` classloading. v2's lowering is not stack-neutral, so it recomputes frames from its own class index instead ([lowering-and-adapters.md](lowering-and-adapters.md) §6). |
| `agent/BlanketAgent.java`, `BlanketClassFileTransformer.java` | **drop** | Java agent: excluded by settled decision 1. |
| `loaders/*/shim/staged/AsmEntryPointSynthesizer.java` | **rewrite** | Synthesizes a loader entry class with ASM, the same job as v2's emitters. It was built for runtime `defineClass`; v2 writes the class into the jar. |
| `core/patch/*` (`PatchPlan`, `PatchTarget`, `InjectionReport`, `StandaloneHookInstaller`, ...) | **drop** | In-memory patch intents for the agent. |
| `core/mixin/InjectionSpec.java`, `RequireOneEnforcer.java` | **rewrite** (rule only) | The invariant "every injection is `require = 1`, raw-offset targeting needs a written reason" becomes a validation rule on hook data ([events-and-hooks.md](events-and-hooks.md) §3). |
| `buildtools/providers/BranchJoinVerifier.java`, `defineclass/*` | **drop** | They guard runtime `defineClass` and branch-join class loading in reflective providers. Neither exists in v2. |

## Runtime core (`core/`)

| v1 package | Verdict | Reason |
|---|---|---|
| `capability/Version.java` | **keep** (with fix) | Numeric left-aligned comparison handles `26.1 > 1.21.11` with no special case. Fix: it rejects snapshot ids (`26.4-snapshot-6`); v2 rejects snapshots as targets anyway ([mappings-and-versions.md](mappings-and-versions.md) §7), so keep the strictness and make the error message say that. |
| `capability/VersionRange.java` | **rewrite** | Its syntax (`a..b`, `a..b+`, `a+`) is close to v2's `when` selector but not the same ([binding-format.md](binding-format.md) §2), and v2 also needs regex selectors for libraries. Port the overlap check. |
| `capability/*` (registry, specs, providers, policies, provenance, `HookResult`) | **drop** | Runtime resolution; replaced by build-time resolution and the support report. |
| `bootstrap/*`, `detection/*` (agreement engine, fingerprints, loader detectors, rendering-mod catalog) | **drop** | The target is known at build time. `FingerprintTable` was only ever a guess table ("BOUNDARY_DOCUMENTED"); v2 confirms a jar's version from the Mojang manifest hash instead. |
| `event/*`, `hook/*` | **rewrite** | `EventSource`/`Subscription` ergonomics are fine for authors. The implementation (vendored Orbit bus, priority bands, lazy method refs) is replaced by a ~100-line event bus in `blanket-runtime` ([events-and-hooks.md](events-and-hooks.md) §5). |
| `vendor/orbit/*` | **drop** | Not needed for the v2 event set; drops a vendored third-party license. |
| `render/*` (`BoundRenderMatrix`, `MatrixStackToPoseStackAdapter`, `OpBindings`) | **drop** | Mirrors Minecraft's matrix types. v2 owns its own transforms ([rendering-api.md](rendering-api.md)). The fact it encodes (1.17 `MatrixStack.push/pop` → `PoseStack.pushPose/popPose`, same argument types) appears as a binding example ([binding-format.md](binding-format.md) §8, example 2). |
| `runtime/*` (`ReflectiveMember`, `ProviderTable`) | **drop** | Reflection-backed providers; v2 emits direct calls. |
| `shim/*` (loader shim, event-bus bridges, packet bridges, platform escape hatch) | **drop** | Running one artifact on a foreign loader; v2 emits one jar per loader. |
| `standalone/*`, `probe/*`, `degradation/*`, `diagnose/*` | **drop** | Loaderless mode, runtime probes, runtime degradation reports. The build-time support report replaces all of it. |
| `sdk/*` (`IMinecraft`, `IPlayer`, `IWorld`, `IRenderMatrix`, `AttackCooldownFn`) | **drop** | v2's API is in [api-surface.md](api-surface.md). Names and shapes differ on purpose (no `I` prefix, alias types instead of interfaces). |

## Build tooling (`buildtools/` others)

| v1 file/package | Verdict | Reason |
|---|---|---|
| `json/Json.java` | **drop** | A home-made JSON reader/writer. v2 core runs on Java 17+ and uses Gson (Apache-2.0), which Minecraft itself ships, so authors know its behaviour. |
| `parse/MiniToml.java`, `MiniYaml.java` | **drop** | v2 data is JSON. `mods.toml` output is written with a tiny TOML writer in the emitter (writing TOML is much easier than parsing it). |
| `catalog/*`, `generator/*`, `capctl/*`, `matrix/*`, `onboarding/*`, `api/ApiCheckMain.java`, `isolation/*`, `Pipeline.java` | **drop** | Spec catalog → Java source generation for runtime providers, compatibility matrix, API check for the dropped surface. |
| `providers/CodegenPipeline.java`, `DirectProviderGenerator.java`, `ProviderModel.java` | **drop** | Source generation of providers; v2 rewrites bytecode instead. |

## Loader scaffolding (`loaders/`, `loaders-compilecheck/`)

| v1 file | Verdict | Reason |
|---|---|---|
| `loaders/*/build.gradle`, `STATUS.md` | **drop**, but read | Never run (`STATUS.md`: "Not run in the migration environment"). Their pinned versions are stale. The list of expected friction (Loom needs forked JDKs, legacy Forge toolchains are brittle) shaped [build-and-gradle.md](build-and-gradle.md) §6: v2 never needs Loom or ForgeGradle to build a mod. |
| `loaders/*/src/.../*Entrypoint.java`, `*HookInstaller.java` | **drop** | Runtime bootstrap glue. The facts they encode (Forge uses an FML mod bus for lifecycle; NeoForge has per-container `IEventBus`) are re-verified in [loader-emitters.md](loader-emitters.md). |
| `loaders/fabric/src/main/resources/blanket.mixins.json` | **drop** | A two-entry Mixin config; v2 emits its own. Its `"injectors": {"defaultRequire": 1}` setting is the right default and is kept. |

## Documents

| v1 document | Verdict | Use in v2 |
|---|---|---|
| `docs/design/capability-seed-survey.md` | **keep** as an input, unmodified | Demand evidence for every API member ([api-surface.md](api-surface.md) cites its section and row). |
| `docs/design/seed-derivation.md` | **keep** the rules, drop the capability IDs | The inclusion rule (HIGH/MEDIUM demand → v1, LOW → backlog) and the "exclusions despite demand" list (`client.math`) are reused. |
| `docs/project/decisions.md` row "Legal Gate: Mojang mapping usage terms" (2026-07-06) | **keep** as a settled input | Conditions restated in [mappings-and-versions.md](mappings-and-versions.md) §8. |
| Everything else (`architecture/`, `guide/`, `archive/`, `project/` trackers and release plans, 61 rules) | **drop** | They describe the runtime design. |

## Totals

Kept: `ArtifactFetcher` (with fixes) and `Version`. Rewritten from ideas: class index, version ranges,
entry-class synthesis, event-bus ergonomics, the `require = 1` rule. Everything else is dropped.
That is under 500 lines of v1 code carried forward, which matches the v1 lesson in `PLAN.md`: build
the thin vertical slice first ([milestones.md](milestones.md) M0).
