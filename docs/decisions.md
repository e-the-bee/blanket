# Decisions

Append-only. Each entry: decision, alternatives, evidence, date. **Provisional** entries may change
after M0; a change is a new entry that names the one it supersedes. Evidence ids point to
[research-log.md](research-log.md).

---

**D-001 — Settled inputs.** 2026-10-28.
The eight settled decisions in PLAN.md (build-time generator; no Minecraft/loader/version in mod source;
mappings only for used symbols; own 2D/3D API; project-owned libraries JSON; client-only; frozen builds;
Mojang mappings build-time only, no MCP/TSRG) are inputs, not reopened.

**D-002 — Rewrite compiled bytecode per target, not source.** 2026-10-28. Confirms PLAN rec. 3.
Alternatives: per-target source generation or preprocessing (Stonecutter, ReplayMod preprocessor).
Evidence: E1 shows lambdas, method references, anonymous classes and generics survive type aliasing and
member lowering (R-EXP-01); source preprocessors rewrite sources in place and see one version at a time
in the IDE (R-PA-02, R-PA-03); no prior art does build-time bytecode retargeting of an abstract API, but
Architectury's `@ExpectPlatform` body rewrite proves the per-call technique (R-PA-01, R-PA-12).

**D-003 — Alias handle *and* value types; own only where shapes differ.** 2026-10-28. Changes PLAN
§4.1 / rec. 6, which owned value types.
Alternatives: own all value types (allocation and conversion at every boundary).
Evidence: `BlockPos`, `Vec3`, `AABB`, `Direction` exist on every first-release target and their API
members map to members or adapters (R-MAP-09, R-MAP-14); enum `name()` is unobfuscated (constructor
string literal). Owned: `Target` (HitResult hierarchy vs `MovingObjectPosition`), `Key`, `Screen`,
rendering types. **Provisional** until S-01/S-02.

**D-004 — Java package `com.blanket`.** 2026-10-28. Carried from v1 (v1 decisions 2026-07-08). Maven
group may differ if namespace verification requires it. Naming itself is [open_questions.md](../open_questions.md) Q9.

**D-005 — Canonical binding names are Mojang names at anchor 1.21.1; cross-version identity via Fabric
intermediary; legacy via explicit LF ids; 26.x via 1.21.11 names.** 2026-10-28.
Alternatives: bind in intermediary ids (unreadable); per-version Mojang names everywhere (no rename
following); structural matching across the 1.13/1.14 gap (heuristic).
Evidence: intermediary follows the `isOnGround` → `onGround` rename (R-MAP-10) and `Entity`/`getX` ids
stay fixed 1.14.4–1.21.11 (R-MAP-09); LF and Fabric id spaces are unrelated (R-MAP-06); LF ids stable
1.8.9–1.13.2 for checked members (R-MAP-13); no intermediary after 1.21.11 (R-MAP-07, R-LDR-08).

**D-006 — Era stub jars process a whole version, but only in Blanket's own development build.**
2026-10-28.
Alternatives: stub only the classes adapters reference (chicken-and-egg: authors need the stubs to
write the reference); write adapters against unreadable ids.
Reasoning: settled decision 3 governs the generate pipeline for a mod; stub jars are never used by
`generate`, never published. Confirmation requested in [open_questions.md](../open_questions.md) Q10.

**D-007 — Own minimal resolver; do not drive Loom, ModDevGradle or ForgeGradle.** 2026-10-28. Confirms
PLAN rec. 7.
Evidence: only the used set is needed (decision 3); v1's loader builds never ran because those
toolchains need forked JDKs and network setup (v1 `loaders/*/STATUS.md`); Architectury Loom still lacks
26.1 support (R-PA-01).

**D-008 — No Fabric API dependency; events via generated Mixin (Fabric family) or native events
(Forge/NeoForge).** 2026-10-28. Confirms PLAN rec. 4.
Evidence: Fabric Loader bundles Mixin (R-LDR-10); Fabric API's mixins give verified injection points
per branch (R-EVT-10); Forge legacy wraps vanilla GUI code, so natives are required there (R-EVT-07).

**D-009 — First-release matrix: `legacy-fabric:1.8.9`, `forge:1.8.9`, `fabric:1.21.1`,
`neoforge:1.21.1`, `fabric:26.1.2`.** 2026-10-28. Changes PLAN's M0 set (adds `forge:1.8.9`; pins 26.x to
26.1.2).
Evidence: legacy Forge needs no MCP data (D-012); 26.1.2 ships OpenGL without Vulkan, 26.2+ ships Vulkan
and 26.3 replaces GLFW with SDL3 (R-MAP-04, R-GFX-05); the five cover all five runtime namespaces.
**Provisional** pending [open_questions.md](../open_questions.md) Q1/Q3.

**D-010 — SRG-named eras are not targets (Forge 1.13–1.20.5, NeoForge 1.20.1).** 2026-10-28.
Consequence of settled decision 8: SRG names exist only in MCP/MCPConfig data. Evidence of the eras:
R-LDR-05, R-LDR-07. Reversal needs the user ([open_questions.md](../open_questions.md) Q2).

**D-011 — Parse mappings with FabricMC mapping-io; remap with ASM's `ClassRemapper`; do not use
tiny-remapper or port v1's parsers.** 2026-10-28.
Alternatives: port v1 `ProguardParser`/`TinyV2Parser`; tiny-remapper.
Evidence: mapping-io (Apache-2.0) reads ProGuard and Tiny v1/v2 with a streaming visitor (R-PA-08), which
fits the used-set filter; tiny-remapper is LGPL-3.0, remaps whole jars and calls `System.exit` on some
paths (R-PA-08); v1 parsers lack obf-side descriptors ([reuse-from-v1.md](reuse-from-v1.md)).

**D-012 — Legacy Forge jars use notch names.** 2026-10-28.
Alternatives: SRG names (needs MCP data, excluded); bundled Mixin with refmaps.
Evidence: FML's `DeobfuscationTransformer` remaps every class, mod classes included, from notch to SRG
at load (R-LDR-01, R-LDR-02).

**D-013 — No bytecode downgrading in v1; mods compile at the lowest target Java.** 2026-10-28.
Alternatives: JvmDowngrader per target.
Evidence: JvmDowngrader is LGPL-2.1 and its API stubs must be shaded into each output (R-PA-11), a
license decision for every author. Revisit with R-14.

**D-014 — Non-native hooks on legacy Forge, when needed, use a generated coremod, not an embedded
Mixin.** 2026-10-28.
Alternatives: embed Mixin + `MixinTweaker` (version conflicts across mods: `MixinBootstrap` refuses a
second version, R-LDR-06; a `TweakClass` jar is not scanned for `@Mod`, R-LDR-03); depend on MixinBooter
(a runtime dependency mod, R-PA-09).
Not used by v1 events. Spike S-12.

**D-015 — `WORLD_JOIN`/`WORLD_LEAVE` are synthesized from `CLIENT_TICK_START`.** 2026-10-28.
Alternatives: per-loader join events (Forge 1.8.9 network events may run off-thread, R-EVT-03).
Trade-off: ≤ 1 tick latency for identical semantics on every target.

**D-016 — Core-profile shaders use GLSL 1.50, not 3.30.** 2026-10-28. Changes PLAN §4.7.
Evidence: 1.17–1.21.8 create a GL 3.2 core context; 3.3 is required only from 1.21.9 (R-GFX-02,
R-GFX-03).

**D-017 — The second render backend is `gpu` (Mojang's GPU abstraction), replacing PLAN's
`mc-native`.** 2026-10-28.
Evidence: Mojang announced OpenGL removal once Vulkan is stable and advises modders to use internal
rendering APIs; 26.4 snapshots default to Vulkan (R-GFX-05); `GpuDevice`/`RenderPipeline` exist from
1.21.5 (R-GFX-04).

**D-018 — `HUD_RENDER` does not fire while a screen is open.** 2026-10-28. **Provisional.**
Alternatives: mirror each version's vanilla HUD-under-screen behaviour.
Reason: one behaviour on every target; screens are drawn through `Screen.render`.

**D-019 — On 1.21.6+, 2D drawing records commands and replays them after `GuiRenderer` renders.**
2026-10-28. **Provisional** (S-08).
Alternatives: picture-in-picture renderers (correct layering, but Fabric's is a Fabric API feature and
the API changed twice, R-EVT-15).
Evidence: GUI drawing is deferred from 1.21.6 (R-EVT-12).

**D-020 — Fonts and textures are baked at build time; default font Inter (SIL OFL 1.1).** 2026-10-28.
Evidence: no stb in LWJGL 2; Minecraft's own text code moved stb → FreeType (1.20.5) and lost its draw
methods in 26.2 (R-GFX-07).

**D-021 — One `when` selector grammar and first-match rule semantics for bindings and libraries;
`provided` libraries may take their version from Mojang's version JSON; `Blanket.hasLibrary` folds like
`supports`.** 2026-10-28. Extends PLAN §4.9.

**D-022 — `blanket.lock` also pins game, mapping and loader inputs; `generate` never edits an existing
lockfile; a project without one gets it on first build.** 2026-10-28. Extends PLAN §4.9.

**D-023 — No transitive library resolution.** 2026-10-28. Reason: per-target differences must be
explicit and reviewable ([libraries.md](libraries.md) §3.1).

**D-024 — All Blanket-generated and embedded classes are class version 52.** 2026-10-28.
Evidence: legacy Forge's ASM 5 reads only up to 52 (R-LDR-04); every target accepts 52.

**D-025 — Development runs use the generated production jars in provisioned clean instances; no
deobfuscated dev environment.** 2026-10-28. **Provisional** pending [open_questions.md](../open_questions.md) Q8.

**D-026 — Generator bytecode Java 21; the generate JVM must be ≥ the highest target Java (25 for
26.x), launched through a Gradle toolchain.** 2026-10-28. Changes PLAN §5 ("17+").
Reason: JDK API references are checked against that JDK's `ct.sym`; 26.x needs Java 25 (R-MAP-02).

**D-027 — v1 API surface as in [api-surface.md](api-surface.md); off-hand is a separate member, not a
`Hand` argument.** 2026-10-28.
Reason: a feature guard applies to a member; a constant argument cannot be reliably detected.

**D-028 — Legacy Fabric client tick hooks `MinecraftClient#tick`, not `runGameLoop`.** 2026-10-28.
Evidence: Legacy Fabric API's tick events fire per frame (R-EVT-08); Blanket keeps 20-per-second
semantics on every target.

**D-029 — Shrinking is class-level only.** 2026-10-28. Reason: small runtime; method-level shrinking
adds risk with little gain.

**D-030 — Owned API classes live in `blanket-api`; internals in `blanket-runtime`; both are embedded
and relocated.** 2026-10-28.

**D-031 — All Blanket data files are JSON, read with Gson.** 2026-10-28. Alternatives: TOML/YAML with
v1's home-made parsers. Reason: one format for bindings and `blanket-libs.json` (settled decision 5 is
JSON); Gson ships with Minecraft, so behaviour is familiar.

---

Entries D-032 to D-042 record the answers to [open_questions.md](../open_questions.md) (2026-10-29).

**D-032 — Audience: public tool, grown on the author's own mods (Q1).** 2026-10-29. First real mod: the
info HUD (`samples/hud-box` grows into it). API growth after 1.0 is still demand-driven
([api-surface.md](api-surface.md) §6), with the author's mods as the first source of demand.

**D-033 — Settled decision 8 amended: obf→SRG tables are read for SRG-era targets (Q2, Q3).**
2026-10-29. Supersedes D-010. Blanket reads MCPConfig `joined.tsrg` for Forge 1.13.2–1.20.4 and
NeoForge 1.20.1 only, at build time, downloaded by the user's build like every other input. It never
reads MCP's `methods.csv`/`fields.csv` (human member names) and never ships a mapping file. Two runtime
namespaces are added: `srg` (Forge 1.13.2–1.16.5: class and member names from the table) and `srgmoj`
(Forge 1.17–1.20.4, NeoForge 1.20.1: Mojang class names, SRG `m_N_`/`f_N_` member names). Note: on
1.13.2–1.16.5 the table's *class* names are MCP's human class names; they are emitted because the game
runs with them, and they are read from the table, not from MCP. Evidence: R-LDR-05, R-LDR-07, R-MAP-16
(MCPConfig license allows use; notice must stay with the *data*, which Blanket never redistributes).

**D-034 — Release 1.0 covers every Fabric and Forge release, selected NeoForge releases (Q3).**
2026-10-29. Supersedes the five-target first release of D-009 (D-012, notch names on legacy Forge, still holds). Fabric: every release from 1.8.9
(Legacy Fabric to 1.13.2, Fabric from 1.14) to the newest 26.x. Forge: every release with a Forge build
from 1.8.9 to the newest 26.x (no 1.20.5 build exists, R-LDR-14). NeoForge: 1.20.1, 1.21.1, 1.21.11 and
the newest 26.x. The list is generated from the loaders' version metadata into `targets.json`
([mappings-and-versions.md](mappings-and-versions.md) §7). After 1.0: newest release within four weeks;
nothing is dropped.

**D-035 — Releases 0.1–0.9 withhold the emitters (Q2).** 2026-10-29. Each milestone M1–M9 ends with a
public 0.x release of everything except `blanket-emit` (the WIRE and EMIT stages,
[architecture.md](architecture.md) §2), which lives in a private repository until 1.0. Public 0.x
builds contain a placeholder that fails `generate` with "emitters are published with Blanket 1.0".
Public CI checks the private module out with a deploy key, so CI stays on the public repository's
runner minutes. 1.0 publishes the module. Because no 0.x binary contains the emitters, GPL obligations
(D-038) never require publishing them early.

**D-036 — Per-target escape hatch: merge author-built overlay jars (Q4).** 2026-10-29. Blanket 1.x does
not compile loader-specific code. An author who needs it builds an **overlay jar** for one target with
that loader's own toolchain (Loom, ForgeGradle, ModDevGradle), already in the target's runtime names,
and names it in the Gradle block. Blanket merges its classes and resources into that target's output
and lowers one API call, `Blanket.overlay(Class<T>)`, to `new <impl>()` or `null` per target
([build-and-gradle.md](build-and-gradle.md) §2.1). Blanket 2.x will compile overlay source sets itself.

**D-037 — Java only, source level = lowest target's Java (Q5).** 2026-10-29. Confirms D-013.

**D-038 — License: GPL-3.0-only with two additional terms (Q9).** 2026-10-29. Supersedes the MIT
assumption of D-004. Maven group `io.github.e-the-bee`; Java package stays `com.blanket`.
Additional terms (GPL-3.0 §7): (a) **attribution** (§7(b)): the notice "Built with Blanket by
e-the-bee" and the copyright line must be preserved in every copy and derived work, including the
`THIRD-PARTY.txt` entry in generated jars; (b) **linking permission** (§7, additional permission):
combining Blanket's embedded code with Minecraft and with any mod loader is permitted, because a jar
that links to proprietary Minecraft classes could otherwise not be distributed under plain GPL.
Consequence: every jar Blanket generates contains GPL-3.0 code, so the mod as distributed must be
GPL-3.0-compatible and its source available. The generator fails on a proprietary `license`
("ARR", "All Rights Reserved", missing) and warns on an SPDX id that is not GPL-3.0-compatible
([libraries.md](libraries.md) §7). Limit, stated plainly: a license makes closed-source and
name-stripping use unlawful; it cannot technically stop cheat clients, most of which are already open
source.

**D-039 — GUI widgets are in 1.0 (Q6).** 2026-10-29. Supersedes backlog item 8. Owned package
`com.blanket.api.ui`: `Button`, `Toggle`, `Slider`, `TextField`, `ScrollList`, laid out by the mod,
drawn with `Canvas2D` ([api-surface.md](api-surface.md) §3.14). Releases 0.1–0.5 have raw drawing only.

**D-040 — Rendering stays in-game; world-space drawing is first-class (Q7).** 2026-10-29. `Draw3D` gains
`WorldPanel` (a `Canvas2D` placed in the world, depth-tested, with a crosshair cursor for spatial GUIs)
and anchored text with a choice of billboard or fixed facing ([rendering-api.md](rendering-api.md) §2.2).
"Behind a block, player or entity" is ordinary depth testing, which needs `WORLD_RENDER` to run after
entities have written depth on every era ([events-and-hooks.md](events-and-hooks.md) §4.4).

**D-041 — IDE run configurations in M5 (Q8).** 2026-10-29. Generated IntelliJ and VS Code
configurations call `blanketRun<Target>` with the debugger attached.

**D-042 — Maintainer-only era stub jars confirmed (Q10).** 2026-10-29. Confirms D-006.

**D-043 — Input routing and cancelling are in 1.0; author Mixins go in overlays.** 2026-10-29.
Supersedes backlog item 11 and the "click also hits the world" limit of D-040. New event `INPUT`
fires for every in-world key, mouse button, scroll and typed character *before* the game handles it;
a listener can `consume()` it, `Input.capture(InputFilter)` keeps matching input away from the game
while active, and interactive `WorldPanel`s receive clicks aimed at them ([api-surface.md](api-surface.md)
§3.15). Recipes are cancellable `@Inject` Mixins where the loader has Mixin, cancelable native events
where they exist, and key-binding suppression at tick start where neither exists
([events-and-hooks.md](events-and-hooks.md) §4.10). Author-written Mixins (for example to cancel a
packet) are not part of the API; an overlay jar may carry its own Mixin config, which Blanket declares in
the target's metadata ([build-and-gradle.md](build-and-gradle.md) §2.1). Reason: the user asked for
Mixin-level input control and for options deciding which input reaches the game, the mod and panels.

**D-044 — Packets are in the v1 API.** 2026-10-29. Supersedes backlog item 1 and the "No packets"
rule. Package `com.blanket.api.net`: typed registration `Packets.onReceive/onSend(PacketType, listener)`
with cancel, a starting catalog (`BLOCK_UPDATE`, `CHAT`, `CUSTOM_PAYLOAD`, `ANY`), and
`Packets.sendCustomPayload` ([api-surface.md](api-surface.md) §3.16). A packet view is an ordinary API
type: alias when one packet class carries it on every era, owned with adapters otherwise, so adding a
packet type is the same work as adding any type. One recipe for every loader-era: a Netty handler
added to the connection's pipeline, so no Mixin is needed and Forge eras without Mixin are covered
([events-and-hooks.md](events-and-hooks.md) §4.11). Listeners run on the network thread
(P-27). Evidence: S§5 demand (`packet.clientListener` 5 mods, `customPayload`, `blockUpdate`); user
request 2026-10-29.

**D-045 — Adding a feature is a data-and-adapter change with tooling, open to anyone with repository
access.** 2026-10-29. Supersedes the demand-survey requirement of [api-surface.md](api-surface.md) §1
rule 1: an addition needs a stated use case in its pull request, not a survey row. The generator
(`blanket-core`, `blanket-emit`) is never touched to add a type, member, event of an existing recipe
kind, packet type, feature or target. Maintainer tasks `lookup`, `scaffold`, `validateBindings` (with
"next step" hints) and `memberSweep` make the path mechanical; the recipes and the definition of done
are in [contributing.md](contributing.md). New members start `@Experimental` and may change in a minor
release; everything else follows semantic versioning.

**D-046 — Versioning: MAJOR.MINOR.PATCH with a strict promise inside a major.** 2026-10-29.
Supersedes the `@Experimental` clause of D-045 (new members are frozen once released). `v1.x` releases
only add (MINOR) or fix to documented behaviour (PATCH); anything a mod could observe as a break,
including binding rewrites that change values or timing, waits for the next major on the `next`
branch. Enforced by C10 (japicmp on `blanket-api`), golden lowering output and C9. Previous major gets
new Minecraft versions and fixes for 6 months after the next major. Details:
[contributing.md](contributing.md) §1. Reason: user request ("v1 code should be compatible with v1.1
or v1.2 … a change pushes it to v2").

**D-047 — Status of entries left provisional by the 2026-10-29 answers.** 2026-10-29. D-009 is
superseded by D-034 (full matrix; core six for M0–M6). D-025 is confirmed by D-041 (Q8 answered with the
recommended default). D-003, D-019 stay provisional until their spikes (S-01/S-02, S-08) in M0–M1.
D-018 stays provisional until M4, when a mod screen first draws over the HUD on the core six.

**D-048 — Release-testing hardware and where M0 lives.** 2026-10-29. (a) The L5 manual run requires
only the maintainer's Windows PC; macOS and Linux + AMD runs happen when a machine (or a volunteer) is
available, and each release's notes name the OS and GPU it was checked on. Alternative: block releases
on three machines the project does not own. (b) The M0 spike generator writes loader jars, so it is an
emitter in the sense of D-035: it lives in the private `e-the-bee/blanket-emit` repository (directory
`m0-spike/`) and is never pushed to the public one. Repositories: public `e-the-bee/blanket`, private
`e-the-bee/blanket-emit`; the public one is created before M1.

**D-049 — On 26.x, Blanket draws into the main render target through its own FBO.** 2026-10-29. Glue
exposes the main target's colour and depth GL texture ids; the `gl` executor attaches them to a
Blanket-owned framebuffer, draws, and restores the previous binding. Alternatives: draw into whatever
is bound (wrong: framebuffer 0, overwritten by the blit, R-EXP-03); draw after `blitToScreen` into the
default framebuffer (works for the HUD only, loses depth for the world). Evidence: R-EXP-03, M0 gate.

**D-050 — `forge-modern` always writes `pack.mcmeta`; L4 asserts an in-world state.** 2026-10-29.
Without the file Forge 1.20.1 stops at a warning screen while the mod itself loads (R-EXP-04), so a
title-screen check passes a broken jar. The emitter writes `pack.mcmeta` with the target's pack format on
every Forge era (NeoForge does not need it, R-EXP-04; writing it there too is harmless). Every L4 run
must reach a world and fail on any blocking screen.

**D-051 — Hook `at` gains `invokeString`; payload values reach the runtime unconverted.** 2026-10-29.
M0 needed a Mixin `INVOKE_STRING` point (1.8.9 world hook) and per-target conversions of hook arguments
(`DeltaTracker`, `Matrix4f`, `GuiGraphics#flush`) that it wrote by hand in each hook (R-EXP-06). The
schema adds `{"invokeString", "ldc"}` and passes payload values as `Object` to `Dispatch`, with
conversions as glue members ([binding-format.md](binding-format.md) §7). Alternative: conversion steps
inside the event recipe (a second place for Minecraft names). **Provisional** until M1 binds the six M0
recipes with it.

**D-052 — Build dependencies for M1 and glue stubs as static members.** 2026-10-30. **Provisional** for the owner to confirm.
M1 T1 and T2 added: the Gradle 9.8.0 wrapper; ASM 9.7.1, mapping-io 0.9.2, Gson 2.15.0 and JUnit 5.14.4 from Maven
Central; LWJGL 3.3.3 (`org.lwjgl:lwjgl`, `lwjgl-opengl`) from Maven Central, compile-only. LWJGL 2.9.4 is not on Maven
Central, so `blanket-runtime` declares `libraries.minecraft.net` as a repository restricted to `org.lwjgl.lwjgl`, the same
jar the 1.8.9 game downloads (compile-only, the game provides it). The runtime's `Glue` abstract class is replaced by
static glue stubs in `com.blanket.runtime.glue` (`GlueFrame`, `GlueWorld`, `GlueGui`), each bound per target by
`glue/*.json` (D-051 shape). Log4j-api (listed in architecture.md section 2) is not added: no runtime code uses it.

**D-053 — `chain` gains a `cast` step.** 2026-11-02. A `chain` step may be `{"cast": "<class>"}`, which changes the static type of the value before the next step. Needed for M0's 26.x texture hooks (`GpuTexture` to `GlTexture`, then `glId`). The cast is a checked type change in the lowering, not a conversion. **Provisional** for the owner to confirm; becomes final when the six M0 bindings pass the gate.

**D-054 — Event payloads may take glue values.** 2026-11-02. A payload source may be `{"glue": "<glue member>", "args": [<source>...]}`, where each `args` entry is any existing payload source. The glue member is an `Object`-returning static in `com.blanket.runtime.glue`, so conversions and side effects (for example `GuiGraphics.flush()`, `Matrix4f` to `float[]`) live in glue, not in the hook body. Extends D-051. **Provisional** for the owner to confirm.

**D-055 — `Dispatch` stays typed for M1; note against D-051.** 2026-11-02. D-051 planned `Dispatch.<event>(Object...)`. For M1, `blanket-runtime` keeps the typed `Dispatch` methods M0 used, because the six M0 hooks already call them with the right types and no hook passes an unconverted value that would need `Object`. D-051's payload-unconverted rule still holds: conversions happen in glue before the call. Revisit with the `Object...` form only if a later milestone needs it. **Provisional** for the owner to confirm.

**D-056 — Chain arguments, arrays and effect-only glue for the M0 HUD and world hooks.** 2026-11-20. **Provisional (orchestrator, owner away)** for the owner to confirm. Closes the three T9 gaps in docs/progress/M1-BLOCKED.md and amends D-054. (A) `{"const": v}` is allowed as a later `chain` step; a later `invoke` takes exactly the `const`/`newarray` steps pushed right before it, in order and with matching types (replaces the "later invoke takes no parameters" rule of D-053; `put`, `adapter`, nested chains and top-level casts stay errors). Needed for `DeltaTracker#getGameTimeDeltaPartialTick(true)`. (B) `{"newarray": "<prim>", "length": n}` is a chain step that pushes a new primitive array, for `Matrix4f#get(new float[16])`. (C) A recipe may list `"before": [<glue source>...]`, each a `void` glue member written with its descriptor, run before the `Dispatch` call (`GuiGraphics#flush()`). (D) D-054 amended: a glue member returns the type of the `Dispatch` parameter it fills (as implemented, D-055), and its parameters may stay `Object`. Two additions the T9 agent needed to express M0 exactly, beyond the orchestrator's four points: `before` is also allowed on native recipes (M0 flushes in the forge 1.20.1 and neoforge 1.21.1 `RenderGuiEvent$Post` listeners), and a payload `{"const": null}` (M0 passes `null` as the 26.1.2 view matrix). Alternatives: an `args` list on chain steps; array conversion through adapters (M2); moving the HUD and world hooks to M2.

**D-057 — Event recipes name their `Dispatch` method; filters and getters are references.** 2026-11-20. **Provisional (orchestrator, owner away)** for the owner to confirm. (1) Every `mixin` injection and every `native` body has a required `dispatch` key naming the `com.blanket.runtime.Dispatch` method it calls (`tickEnd`, `hud`, `hudRecord`, `hudReplay`, `world`, `worldLegacy`); its parameter count equals the payload entry count. `mixin` accepts one object or an array of objects, each its own injector (26.1.2 HUD = `gui.record` → `hudRecord` + `gui.replay` → `hudReplay`). Before this, the generator had to guess the method from the event and era. (2) A native `filter` is `{"<getter or field ref>": "<static field ref>"}`, entries ANDed; the generated listener compares by identity and returns early when different. Replaces the name-only form (`{"phase": "END"}`), which carried Minecraft-loader names outside references. (3) A `getter` source accepts a field reference (`Owner#name:T`, read with GETFIELD), for Forge 1.8.9 public final fields such as `RenderGameOverlayEvent#partialTicks:F`. Amends D-056 for C3: an `Object` parameter of a glue stub matches any reference-type parameter of its lowering, receiver included, and the generator emits a `checkcast` per argument (so `GlueWorld.viewProjection(Object, Object)` lowers to `Camera#getViewRotationProjectionMatrix(Matrix4f)` directly). Spec: [binding-format.md](binding-format.md) §7, §4.5.

**D-058 — `blanket-bindings/loader-artifacts.json` names the loader jars the validator checks.** 2026-11-20. **Provisional (orchestrator, owner away)** for the owner to confirm. A flat object from Forge/NeoForge target id to artifact version (`forge:1.8.9` = `1.8.9-11.15.1.2318-1.8.9`, `forge:1.20.1` = `1.20.1-47.4.28`, `neoforge:1.21.1` = `21.1.265`, the versions M0's `tools/run.py` installed and played). The validator downloads the matching universal jar and checks native event classes and their references in it; a target without an entry gets a warning. Fabric and Legacy Fabric need no entry: their event recipes are Mixin recipes on the client jar. Spec: [binding-format.md](binding-format.md) §1, §7.

**D-059 — `targets.json` names the game's classes; a game class with no mapping row is UNRESOLVED.** 2026-11-21. **Provisional (orchestrator, owner away)** for the owner to confirm. `targets.json` gains two required top-level keys: `gamePackages`, a non-empty list of internal-name prefixes that end with `/` (`net/minecraft/`, `com/mojang/`), and `obfuscatedClass`, a regex that matches a whole obfuscated class name including an inner-class suffix (`[a-z][a-z0-9]{0,3}(\$.*)?`). One predicate, `com.blanket.core.resolve.GameClasses`, is built from them and used by `Resolver`, `Routes` and `TargetChecks`; no game package or name pattern is written in blanket-core code (rule 2). Before this, three copies disagreed: the resolver did not see `bew$a` as a game class and the validator did, and a mistyped `com/mojang/...` class with no row was kept as a library class. Now a class that `GameClasses` accepts and that has no mapping row fails the reference at the mapping step. JOML, LWJGL and the JDK stay library classes. A `targets.json` without the keys fails to load with its path (`$.gamePackages` or `$.obfuscatedClass`). D-058 is tightened: a loader reference on a supported target with no loader jar in `loader-artifacts.json` is an UNRESOLVED error at step `loader-jar` instead of a warning, so `validateBindings` cannot pass with it unchecked. No Fabric or Legacy Fabric target uses a loader reference in the real bindings, so `loader-artifacts.json` gains no Fabric entry. Spec: [binding-format.md](binding-format.md) §3, §7.

**D-060 — Owner confirms D-052 to D-059.** 2026-11-26. The owner confirmed D-052, D-053, D-054, D-055, D-056, D-057, D-058 and D-059 as written, after the M1 gate passed on the core six with them (testbox, R-EXP-11, docs/progress/M1-gate/); they are final. The owner also chose to keep blanket-emit as a local private repository with no remote for now (D-035, D-048 unchanged), and not to publish release 0.1 yet.

**D-061 — M2 build decisions (lowering and adapters).** 2026-11-26. **Provisional (orchestrator)** for the owner to confirm. (1) New build dependencies: japicmp for C10, ASM `asm-analysis` (blanket-core) and `asm-util` (tests); versions recorded in docs/progress/M2.md. (2) The legacy adapter set's authoring names are Legacy Yarn 1.8.9 (`net.legacyfabric:yarn:1.8.9+build.604`, CC0, R-MAP-14), downloaded like LF intermediary; `adapter-set.json` gains `authoringMappings`. (3) Remapped adapters go to `blanket-bindings/build/adapters/<set>/` in package `com.blanket.adapters.<set>` with an `adapter-refs.json` side table (declaring classes of adapter member references, override declarations); not packaged into `blanket-bindings.jar` in M2. (4) New error codes `VERSION`, `COLLISION`, `FORBIDDEN`. (5) `features.json` is read directly into each target's plan, no generated `types/Blanket.json`. (6) Key code tables are Java in each set's `KeyCodes` adapter, no `types/Key.json`. (7) `KeyBinding.register` is bound in M2 (an adapter builds the key and queues it in the runtime); the per-loader registration recipe is M3 (S-07). (8) API stub bodies throw `AssertionError("blanket stub")` and owned API types carry `@com.blanket.api.Owned`. (9) The C10 baseline is the T1 `blanket-api` jar, committed under `blanket-api/baseline/`, since no release exists (D-060). Why: each closes a gap or contradiction the M2 plan found in the docs (lowering §5.2/§4.5/§6, binding-format §6, api-surface §1/§3.9, events-and-hooks §4.7); all are cheap to revise before 0.2.

**D-062 — M2 build decisions, second batch.** 2026-11-26. **Provisional (orchestrator)** for the owner to confirm. (1) Adapters compile through a core tool on `javax.tools`, not one Gradle JavaCompile per set, so the `@Stubs` split is testable; stub jars on javac's classpath are capped at major 65 and a `java.lang.Record` shim is added for `--release 8` (R-EXP-18). (2) `@Stubs` stub jars go to `blanket-bindings/build/stubs-extra/`; forge:1.20.1 screens use the `@Stubs("1.20.1")` host (R-EXP-18). (3) The Resolver keeps `<init>` and maps owner and descriptor only (R-EXP-15). (4) `Planner.plan(modJar, bindingsDir, embed)` takes the embedded classes, and the class-level shrink ships only `Reach`'s closure (the M2 part of SHRINK, lowering §8). (5) Legacy Yarn is the `v2` classifier with a sha1 check (R-EXP-13), and `stubJars` copies library jars to `.jar` names because the fetch cache stores files without extension. (6) The M2 gate scene joins a live local server on all six targets (R-EXP-12). Why: each came from a measured fact in M2; cheap to revise before 0.2.

**D-063 — Empty-stack display name and alias-enum toString.** 2026-11-27. **Provisional (orchestrator)** for the owner to confirm. (1) On targets without `ItemStack.EMPTY` (≤1.10.2), `displayName()` of an empty stack returns the fixed text `Air`, because the game has no name for it: the M2 api-tour gate on both 1.8.9 targets got `tile.air.name`, a key missing from 1.8.9 `en_US.lang`, where modern targets return `Air`. (2) `toString()` of an alias enum is Minecraft's own and may differ between versions (lowercase `up` on 1.8.9 and on fabric:26.1.2 in the gate); the API promises `name()` only, so the api-tour sample logs `side().name()` and expects `UP`. Why: the gate failures `FAIL slot8` and `FAIL target` (M2-gate-results/legacy-fabric-1.8.9, fabric:26.1.2).

**D-064 — M3 event wiring.** 2026-11-27. **Provisional (worker)** for the owner to confirm. (1) Recipe kind `via`: `{"when": {...}, "via": "<EVENT>"}` says the runtime builds the event from another event's hook. RESOLVE wires the named event's first matching recipe, and each recipe once, so a mod using `CLIENT_TICK_START`, `WORLD_JOIN` and `WORLD_LEAVE` gets one tick-start hook. C1 fails when the named event has no events file or no recipe for the target, when it is itself `via` (a chain or a cycle), or when an event names itself. `WORLD_JOIN` and `WORLD_LEAVE` use it (D-015). (2) `native` may be one body or an array of bodies, like `mixin`; each body becomes its own listener method, for an event that needs two loader events on one target. (3) An event counts as used only where the mod, or an embedded class other than `com/blanket/runtime/Dispatch`, reads `Events.X`: `Dispatch` reads every event to fire it, which made every jar wire every hook, against events-and-hooks §1 principle 5. A class reached from a root that subscribes (the testkit) still counts. (4) `lookup` resolves `loader:` references in the loader jar of `loader-artifacts.json` with the validator's class index (step `jar`); a target without an entry fails at step `loader-jar`. This closes M1-verify finding 5, deferred in D-061. Why: binding-format §7 had no way to express a synthesized event or a second loader event, and the M2 scan rule did not scale to the M3 events.

**D-065 — M3 generated hooks and binding schema.** 2026-11-27. **Provisional (worker)** for the owner to confirm. (1) A generated Mixin class is named `<Event>$<HookId>` with both parts cut to Java identifier characters (`tick.end` becomes `tick_end`), a repeated pair gets `_2`, and every `@Inject` carries `require = 1` besides the config's `defaultRequire`. (2) Native hooks with `"bus": "mod"` go to a second generated class, `ModListeners`, registered on NeoForge's entry-constructor `IEventBus` and on Forge 1.20.1's `FMLJavaModLoadingContext.get().getModEventBus()`; on legacy Forge a `mod` hook is an emit error. `"bus": "fml"` on legacy Forge runs the body in the entry's `FMLInitializationEvent` method after `Boot.init`. (3) A glue member may lower to an adapter (`{"adapter": "<set>:<class>#<method>"}`), called with the glue stub's own descriptor; the adapter closure and C4/C6 include glue adapters. (4) A `loader:` reference's descriptor on a Legacy Fabric target names Minecraft classes as LF ids. (5) An events file may declare `"usedBy": "<API method>"`: its recipes are wired only where the mod calls that method (`KEY_HANDOVER`, used by `KeyBinding#register`). (6) A hooks.json rule may list `"mutable"` fields of the hooked class; the Mixin shadows each with visible `@Shadow @Final @Mutable`. (7) Payload `{"arg": 0}` is the hooked object in a Mixin; `{"arg": 1}` is the event in a native body. (8) C3 treats every array type as a subtype of `Object`. Why: the M3 recipes (screen open and close, key hand-over, testkit glue) cannot be expressed in the M2 schema; binding-format §4.5 and §7 describe each form.

**D-066 — Key hand-over per loader (S-07, S-16).** 2026-11-27. **Provisional (worker)** for the owner to confirm. (1) Fabric and Legacy Fabric: a Mixin at `Options#load()` HEAD (hook `keys.options`) appends the queued mappings to the options' key array (`keyMappings`, made mutable per D-065; `allKeys` on 1.8.9). NeoForge 1.21.1 and Forge 1.20.1: a mod-bus listener for `RegisterKeyMappingsEvent` registers each mapping. Forge 1.8.9: after `Boot.init` in the FML init method, the queued mappings are appended to `GameSettings.keyBindings`, which is the whole body of `ClientRegistry.registerKeyBinding`. (2) No loader needs Blanket to re-read `options.txt` (R-EXP-26), so the re-read of events-and-hooks §4.7 is not built. (3) 26.1.2 categories are registered once per category id (R-EXP-27); the category key is `key.category.<modid>.<category>`, which settles the open M2 deviation. (4) `KeyCodes` tables cover every `Key` constant, distinct, checked against the real LWJGL 2 `Keyboard` and GLFW constant values. Why: S-07 and S-16 were ASSUMED in risks.md and are settled by javap evidence (R-EXP-26, R-EXP-27).

**D-067 — L4 testkit shape.** 2026-11-27. **Provisional (worker)** for the owner to confirm. (1) The harness, not the in-game testkit, brings the game into a world: every target joins a local offline dedicated server (flat world, bound to 127.0.0.1, `difficulty peaceful`, R-EXP-28), 1.8.9 by `--server/--port` and the others by `--quickPlayMultiplayer`. testing-strategy §4's "loads a world from a template save" is replaced. (2) The harness passes `-Dblanket.testkit.probes` and `-Dblanket.testkit.out`; without both the testkit does nothing. Probes are a JSON file next to the sample (`samples/hud-box/testkit-probes.json`): GUI-scaled or anchor-relative world positions with a colour or a dominant channel, plus a key round that presses the first registered key and expects a named probe to change. The anchor is the player's block at the first tick end after the join with the player on the ground (R-EXP-29). The result is `testkit-result.json` (tick counts, joins, leaves, probe results per round, key result, listener failures, pass). (3) The verdict fails on a missing result file within the timeout, any failed probe or key check, fewer than 100 tick ends or unequal start and end counts, not exactly one join, any listener failure, a Mixin error line or a crash marker in the game log. (4) A glue member gets a body only when a shipped class calls it, so neither a class under `testkit/` nor the testkit's `KeyPress` adapter is in a jar built without `--testkit`. Why: a server join works the same on all six targets (M2 gate) while template saves differ per version; the formats let the sample, the runtime and the harness be built in parallel.

**D-068 — M3 choices recorded after verification.** 2026-11-27. **Provisional (orchestrator)** for the owner to confirm. (1) NeoForge (and Forge 1.13.2–1.21.5) native listeners stay a generated class of `@SubscribeEvent` instance methods registered with `IEventBus.register(Object)`, not the explicit-class `addListener` overload events-and-hooks §5 first chose; this form ran on neoforge:1.21.1 and forge:1.20.1 in the M0, M1, M2 and M3 gates, and each method names its event class in its descriptor, so no generic inference is involved. (2) The Fabric `screen.open` rule for 26.x covers 26.1.2 only (`26.1..26.1.2`): `setScreen` moves to `Gui` in 26.2 (R-EVT-11), a release that is not a core target before M7. (3) `samples/hud-box` draws no text in M3: `Canvas2D` has no text until M4 (rendering-api); the M4 gate adds it. (4) The two Forge 1.20.1 names `ForgeModernEmitter` writes for the mod bus (`FMLJavaModLoadingContext#get()`, `#getModEventBus()`) are checked by `javap` on `javafmllanguage-1.20.1-47.4.28` only: that class is not in the universal jar `loader-artifacts.json` names, so `lookup` and C7 cannot see it; the M3 gate runs it on forge:1.20.1. (5) A blocking screen fails the L4 gate through D-018: HUD_RENDER does not fire while a screen is open, so the testkit's HUD round fails after 200 ticks without a HUD frame (`SessionTest.noHudFrameForTwoHundredTicksFailsTheRound`). (6) In M3, SCREEN_OPEN runs in game only through the join screens hud-box logs, and WORLD_LEAVE has no in-game run; the M4 gate (a mod screen opened by key) adds a SCREEN_OPEN check with `isModScreen`, and a WORLD_LEAVE check is due with it. Why: the M3 verification (docs/progress/M3-verify.md findings 1, 2, 6, 7, 8) asked for each of these to be recorded.
