# Milestones

Every milestone ends with a **gate that a real game running a generated jar can pass or fail**. The
core targets are `legacy-fabric:1.8.9`, `forge:1.8.9`, `fabric:1.21.1`, `neoforge:1.21.1`,
`fabric:26.1.2`, `forge:1.20.1` ([mappings-and-versions.md](mappings-and-versions.md) §7.1), called "the
core six" below. Spikes S-xx are defined in [risks.md](risks.md).

**Releases.** M1–M9 each end with a public release 0.1–0.9 that contains everything except the
`blanket-emit` module (D-035), so 0.x builds cannot produce jars for players. **1.0 is the end of M9**:
full matrix ([mappings-and-versions.md](mappings-and-versions.md) §7.1), widgets, world panels, the `gpu`
backend, and `blanket-emit` published.

| Milestone | Release | Adds |
|---|---|---|
| M0 | — (throwaway) | thesis on the core six |
| M1 | 0.1 | bindings + resolver |
| M2 | 0.2 | lowering + adapters |
| M3 | 0.3 | events |
| M4 | 0.4 | `gl` backend, raw drawing |
| M5 | 0.5 | Gradle plugin, libraries, overlays, run configurations |
| M6 | 0.6 | widgets + world panels |
| M7 | 0.7 | full matrix (every Fabric and Forge release, NeoForge set) |
| M8 | 0.8 | `gpu` backend |
| M9 | 0.9 → **1.0** | hardening, full-matrix L4, docs, `blanket-emit` public |

## M0 — Thesis spike (throwaway code)

**Status: done 2026-10-29.** All six jars pass in real games with screenshots
(`blanket-emit/m0-spike/NOTES.md`, R-EXP-02..09, D-049, D-050). Still open: a person compares one logged
position with F3 (`python tools/run.py fabric:1.21.1 --world`).

**Goal:** prove on real games that one compiled jar can become six native jars, before building any
real machinery. `forge:1.20.1` is in M0 because `srgmoj` is the newest unproven runtime namespace
(D-033).

**Deliverables**
- `samples/m0-spike` (in the private repository, D-048): a mod compiled once against a minimal hand-written `blanket-api` (`Client.get`,
  `Client.player`, `Player.x/y/z`, `Events.CLIENT_TICK_END`, `HUD_RENDER`, `WORLD_RENDER`, `Canvas2D.fillRect`,
  `Draw3D.boxFill`).
- `m0-spike` generator: hard-coded runtime names per target (looked up by hand with `blanket`-less
  scripts over the real mapping files), the ASM lowering from experiment E1, hand-written Mixin classes
  and metadata per target, a ~300-line GL backend (two shaders per dialect, state guard).
- Six jars and a page of notes: runtime names used, Mixin/native recipe per target, what broke.

**Gate:** each jar, dropped into a clean instance of its loader, shows the magenta HUD rectangle and the
green world box, and logs the player position every 20 ticks with values that match the F3 screen.
Checked by a person with screenshots stored in the repo (automation arrives in M3).

**Work breakdown**
1. Instance provisioning by hand for the core six; confirm offline single-player launch (S-14).
2. Hand-resolve ~15 symbols per target (Minecraft, player, position, tick, HUD and world hooks, GL
   framebuffer and matrices); confirm notch names on `forge:1.8.9` (R-LDR-02 says they work) and SRG member names from
   `joined.tsrg` on `forge:1.20.1`.
3. Lowering tool from E1 plus Mixin/native listener emission; entry classes and metadata per emitter.
4. Minimal GL backend with GLSL 120 and 150 variants and the full state guard (S-06).
5. 26.1.2 HUD replay point after `GuiRenderer` (S-08).
6. Write down every surprise in `research-log.md` and adjust these documents.

**Retires:** R-01 (aliasing in a real JVM with real game classes, S-01), R-03 (runtime namespaces), the
first part of R-05/R-06 (state guard and deferred replay, S-06/S-08), S-14 (provisioning and launch).

## M1 — Binding data and resolver (0.1)

**Goal:** replace M0's hand-resolved names with data and a real resolver.

**Deliverables:** `targets.json`, `types/Client.json`, `types/Player.json`, `hooks.json`, the event files
for the three M0 events; contributor tasks `lookup`, `scaffold` and hint output for `validateBindings`
([contributing.md](contributing.md) §2); `blanket-core` scan and resolve stages with mapping-io streaming and the lazy
class index; `blanket check` and `blanket explain`; error codes `UNBOUND`, `UNRESOLVED`, `SHAPE`;
`ArtifactFetcher` port with SHA checks and cache; checks C1, C2, C3, C7, C8 in `validateBindings`.

**Gate:** the M0 sample, regenerated with resolver-produced names only (no hard-coded name left in the
generator), passes the M0 gate on the core six; `blanket check` on a deliberately broken binding
reports `UNRESOLVED` naming the target and the failing namespace step.

**Work breakdown:** selector parser (shared with libraries); rule selection; reference parser; resolution
routes per namespace ([binding-format.md](binding-format.md) §2.4); streaming filters; class index;
resolution cache; error aggregation across targets; real-data tests (S-02, S-03).

## M2 — Lowering and adapters (0.2)

**Goal:** the real rewriter and the adapter pipeline, including a true shape change.

**Deliverables:** all lowering kinds, bridges for `new` and method handles, feature folding and
dead-code removal, `UNSUPPORTED` reachability check, `ClassRemapper` stage, frames from the class index,
class-version check; `blanket-bindings` maintainer tasks `stubJars`, `compileAdapters`, `remapAdapters`;
adapter sets `legacy`, `modern`, `unobf`; the full API of [api-surface.md](api-surface.md) §3.1–3.9 bound
for the core six (checks C1–C6 green); owned API classes and relocation.

**Gate:** `samples/api-tour` (uses `offHandItem` behind `supports(OFFHAND)`, `crosshairTarget`,
`foodLevel`, `mainHandItem`, `blockState(..).propertyValue("facing")`, a lambda and a method reference
over `Player`, an enum switch on `Direction`) runs on the core six and logs the expected values for a
scripted scene (player standing on a known block facing north); on the 1.8.9 targets the off-hand branch
is absent from the bytecode.

**Work breakdown:** tree-API passes; bridge synthesis; folding and analyzer pass; remap map builder;
hierarchy-aware `getCommonSuperClass`; stub jar builder (Mojang, official, Legacy Yarn authoring);
adapter remap to shipping namespaces; adapters for the shape changes in binding examples 5, 6, 8, 9;
`ScreenHost` per era (C6); empty-stack sentinel (S-15).

## M3 — Events (0.3)

**Goal:** every v1 event on every core target, with failure reporting and automation.

**Deliverables:** recipes of [events-and-hooks.md](events-and-hooks.md) §4 for the core six; the
runtime dispatcher; generated Mixins and native listeners; key-binding registration recipes (S-07);
key code tables (S-16); `--testkit` build mode and the L4 harness ([testing-strategy.md](testing-strategy.md) §4).

**Gate:** `samples/hud-box` passes the automated L4 gate on all core six in CI (tick counts, join,
pixel probes, no Mixin error), and pressing its registered key (simulated by the testkit) toggles the
rectangle.

**Work breakdown:** Mixin class emitter with `require = 1`; `INVOKE` point verification; Forge legacy
`@SubscribeEvent` listener generation; NeoForge `addListener` generation; world join/leave synthesis;
testkit hooks (world autoload, readback, result file, quit); CI job definitions.

## M4 — Render backend (0.4)

**Goal:** the complete `Canvas2D`/`Draw3D`/resource API on the `gl` backend, proven next to the real
renderer.

**Deliverables:** [rendering-api.md](rendering-api.md) §2 and §3 in full; build-time font and texture
baking; `Lwjgl2Gl`/`Lwjgl3Gl`; all glue bindings for the core six (S-06); L3 golden-image suite and
state-guard test; mod `Screen` rendering through `ScreenHost`.

**Gate:** `samples/render-showcase` passes L4 on the core six with probes on every primitive kind,
text, a texture, a mesh and a clipped region, **and** a probe on vanilla's hotbar pixels matching a
run without the mod (proves the state guard leaves vanilla drawing intact); a mod screen opened by key
renders and receives a click.

**Work breakdown:** command list and batcher; shaders in two dialects; SDF font baker; texture baker;
matrices per era; depth modes; per-frame budget measurement (R-23).

## M5 — Gradle plugin, hardened emitters, libraries, overlays (0.5)

**Goal:** an author can build a real mod with `./gradlew assemble` and get the core six jars.

**Deliverables:** `blanket-gradle` ([build-and-gradle.md](build-and-gradle.md) §2) with tasks, caching and
the forked generate JVM; `blanketRun<Target>` with debugging; `blanket-libs.json` resolution, the three
modes, compatibility check, relocation, licenses, lockfile ([libraries.md](libraries.md)); metadata
details settled (S-04, S-05); reproducible jars; support report; L5 manual checklist.

**Gate:** `samples/lib-demo` built only through Gradle shades one small library at version A for
`fabric:1.21.1`/`neoforge:1.21.1` and version B for the 1.8.9 targets, uses Gson as `provided`, and passes
L4 on the core six; two consecutive builds give byte-identical jars; L5 manual run passes on
Windows (macOS and Linux when a machine is available, D-048).

Also: overlay merge and `Blanket.overlay` folding (D-036; gate: `samples/overlay-demo` adds a ModMenu
config entry point on `fabric:1.21.1` only); generated IntelliJ/VS Code run configurations (D-041);
license check (D-038).

**Work breakdown:** DSL and tasks; toolchain fork; library resolver and downloader; relocation remapper;
multi-release flattening; service files; license collection and GPL check; lockfile read/write/verify;
overlay merge; emitters' remaining metadata fields; IDE run configurations; docs for authors.

## M6 — Widgets, world panels, input and packets (0.6)

**Goal:** settings screens without hand-drawn controls, and drawing placed in the world (D-039, D-040).

**Deliverables:** `com.blanket.api.ui` ([api-surface.md](api-surface.md) §3.14); `WorldPanel` and
anchored text ([rendering-api.md](rendering-api.md) §2.2); input routing, `INPUT` event, captures and
interactive panels (D-043, S-21); packets (D-044, S-22); overlay Mixin configs; clipboard glue for
`TextField`; the
`WORLD_RENDER` depth contract ([events-and-hooks.md](events-and-hooks.md) §4.4) checked on every core
target.

**Gate:** `samples/widgets` opens a screen with every widget; the testkit clicks, drags and types into
it, and the values round-trip. `samples/world-panel` draws a panel and floating text at fixed positions
behind a wall and behind an armor stand, and in front of both; pixel probes show the hidden ones hidden
and the front ones visible, on the core six.

## M7 — Full matrix (0.7)

**Goal:** every target of [mappings-and-versions.md](mappings-and-versions.md) §7.1.

**Deliverables:** `updateTargets` (generated matrix); adapter set `legacy13` and the `@Stubs` classes for
1.14–1.16.4 in `modern`; event recipes for every era, including `forge-srg`/`forge-srgmoj` native
events and EventBus 7 (S-11, S-18); key code tables for every window system (S-16); 26.2+ hook moves.

**Order:** era representatives first (tier 1 in [testing-strategy.md](testing-strategy.md) §5), then
the remaining releases of each era, which mostly need no new rules (C1–C9 show which do).

**Gate:** tier-1 L4 green on every era representative; L2 (C1–C9) green on every target; tier-2 launch
smoke green on every target.

## M8 — `gpu` render backend (0.8)

**Goal:** drawing on Vulkan-backed and future OpenGL-less releases ([rendering-api.md](rendering-api.md) §5).

**Gate:** `render-showcase` passes L4 on `fabric:26.3` with the Vulkan backend selected (software Vulkan
in CI if S-10 confirms lavapipe works; otherwise the gate runs on the L5 machines) and on the newest
release at that time with its default backend.

**Work breakdown:** pipeline per material, buffer and texture upload through `GpuDevice`, render pass on
the main target, reversed depth (S-09), shader format (S-10), golden tests against the `gl` backend's
images.

## M9 — Hardening and release 1.0

**Goal:** the promise of D-034 holds on every target.

**Deliverables:** full-matrix L4 (tier 1 + tier 2) green; L5 manual run on the core six plus
`forge:1.12.2`, `fabric:1.16.5`, `fabric:26.3` (Vulkan); author docs and samples; `blanket-emit` moved
into the public repository; first public jars of a real mod (the info HUD, D-032).

**Gate:** tier 1 green in two consecutive full runs; tier 2 at least 95% green, with every failing
target listed as a known issue in the release notes and the support report (D-034, R-31,
[pseudocode.md](pseudocode.md) P-25); the info HUD mod's jars pass L4 on every tier-1 target.

## M10 — API growth by demand (after 1.0)

Grow the API from real mods, one backlog item at a time ([api-surface.md](api-surface.md) §6). Gate per
addition: the requesting mod runs on every supported target and C1–C10 stay green. Likely first: entity
access, styled text, access wideners/transformers ([loader-emitters.md](loader-emitters.md) §6),
packet types beyond the §3.16 catalog. Blanket 2.x: overlay source sets compiled by Blanket (D-036).

## Dependencies between milestones

M0 → M1 → … → M5 is a chain (each gate reuses the previous samples). After M5, M6, M7 and M8 can run in
parallel; M9 needs all three. M8 must finish before 1.0 regardless of R-07, because 26.2+ players can
already choose Vulkan.

**Effort (ASSUMED; one developer, part-time):** M0 2–3 weeks; M1–M5 about 4–6 months together; M6 4–6
weeks; M7 2–4 months (mostly hook points and CI time across ~130 targets); M8 1–2 months; M9 4–6 weeks.
1.0 is roughly a year after M0 starts.
