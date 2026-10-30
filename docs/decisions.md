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
