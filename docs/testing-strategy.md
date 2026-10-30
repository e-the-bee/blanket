# Testing strategy

v1 had 147 green tests and never ran in a real game. v2's rule: **every milestone gate is a real game
running a generated jar** ([milestones.md](milestones.md)); offline tests exist to make those gates
cheap to pass, not to replace them.

## 1. Layers

| Layer | Needs | Runs | Purpose |
|---|---|---|---|
| L1 unit | JDK only | every push | parsers, selectors, rule matching, lowering on synthetic classes, command list, lockfile |
| L2 bindings validation | downloaded game data (cached) | every push touching `blanket-bindings`, nightly | checks C1–C9 against every supported target (§5) |
| L3 golden images | Xvfb + Mesa llvmpipe | every push touching `blanket-runtime`, nightly | GL backend pixels in GL 2.1 compatibility and GL 3.2 core contexts |
| L4 in-game smoke | game + loader per target, Xvfb + llvmpipe | nightly, every PR touching bindings/runtime/emitters, every release | the generated jar loads, hooks fire, pixels appear |
| L5 manual | a human and a GPU | before each release | real GPU drivers, macOS, Windows, multiplayer server join |

## 2. L1 and L2 checks

L1 highlights:
- **Lowering on fake Minecraft.** Small hand-written "game" classes (like experiment E1,
  [research-log.md](research-log.md) R-EXP-01) and fake bindings exercise every lowering kind, bridges,
  folding, dead-code removal, remapping, frames. Every output is loaded in a fresh class loader and run
  with `-Xverify:all` on JDK 8 (class version 52 path) and JDK 25.
- **Golden lowering output.** For the sample mods, the textual ASM dump of each lowered class per target
  is stored; diffs are reviewed like code.
- **Selector and rule tests** shared by bindings and `blanket-libs.json` (one parser).
- **Reproducibility.** Generating twice yields byte-identical jars.

L2 checks, named because other documents refer to them:

| Check | What fails it |
|---|---|
| C1 coverage | a public API member or glue member without a rule list, or a rule list with no rule for a supported target |
| C2 resolution | a reference that does not resolve or is absent from the target's client jar |
| C3 shape | a direct lowering whose stack effect differs from the API member ([binding-format.md](binding-format.md) §4.3) |
| C4 adapters | an adapter set that does not compile, or whose references do not resolve on a covered version |
| C5 enum order | an aliased enum's constant order differs from the API's |
| C6 overrides | a `ScreenHost` (or any adapter subclass) method that does not override a real method on a covered version |
| C7 hooks | a hook target missing, or an `INVOKE` point that matches a different number of call sites than required, on any supported version; a native event class missing from the loader jar; a `MIXIN` recipe on an era without Mixin (legacy Forge, Forge before 1.15.2) |
| C8 mapping terms | the header of a downloaded Mojang mappings file differs from the text in [mappings-and-versions.md](mappings-and-versions.md) §8, or the MCPConfig `LICENSE` differs from R-MAP-16 |
| C10 API compatibility | `blanket-api` is not source- and binary-compatible with the last release of the same major (japicmp; [contributing.md](contributing.md) §1.5) |
| C9 matrix | `targets.json` differs from what `updateTargets` generates from the loaders' metadata, or a supported target has no era row |

L2 downloads about 60 MB per obfuscated target (client jar ~25 MB, ProGuard mappings ~12 MB,
intermediary ~1 MB) and ~30 MB per 26.x target, cached on the runner (R-MAP-01, sizes observed
2026-10-28).

## 3. L3 golden images

As in [rendering-api.md](rendering-api.md) §6: fixed scenes (every `Canvas2D` primitive, clipping,
transforms, opacity, text at three sizes, `Draw3D` lines/boxes/quads/mesh/billboard with both depth
modes), rendered into an FBO at 512×512, compared with stored PNGs (tolerance 2/255 per channel,
≤0.1% pixels). Plus the state-guard test. Golden PNGs are regenerated only with an explicit task and
reviewed visually in the PR.

## 4. L4 in-game smoke tests

**The smoke mod** (`samples/hud-box`) uses: `CLIENT_TICK_END` (counts ticks), `WORLD_JOIN`,
`HUD_RENDER` (fills a 40×40 rectangle of colour `#FF00FF` at GUI position (10, 10) and draws text),
`WORLD_RENDER` (a filled box of colour `#00FF00` at a fixed world position 3 blocks in front of the
spawn point), `Client.player()` reads (logs position), and `KeyBinding.register`.

**Testkit hooks.** `blanket generate --testkit` adds, only in test builds, a relocated testkit module
to the jar with its own generated hooks (same mechanism as events): it loads a world from a template
save on the title screen, waits until 100 client ticks after `WORLD_JOIN`, reads back the main
framebuffer with `glReadPixels`, evaluates pixel probes (the HUD rectangle's centre must be
`#FF00FF ± 8`, the box's projected centre must be greenish), writes `testkit-result.json` (probes,
event counts, exceptions, Mixin errors from the log), and quits the game. Release builds never include
it.

**Harness** (`blanket-testkit`): provisions the instance as in [build-and-gradle.md](build-and-gradle.md)
§4, copies the world template and the jar, starts the game under Xvfb with Mesa llvmpipe
(`LIBGL_ALWAYS_SOFTWARE=true`), applies a timeout of 6 minutes, and fails if the result file is missing
or any probe fails. The game log is archived as a CI artifact.

**Gate per target:** game reaches the world (a title screen or a blocking warning screen is a failure, D-050), ≥100 `CLIENT_TICK_END` and exactly matching tick counts
from start/end, `WORLD_JOIN` fired once, both pixel probes pass, no Mixin error, no Blanket listener
exception.

Assumptions to retire in M0 (spike S-14): offline launch for single-player works on the core six;
llvmpipe provides GL 2.1 compatibility (1.8.9) and 3.3 core (26.1.2) contexts fast enough for 100 ticks
in the timeout (llvmpipe implements up to GL 4.6, V R-GFX-09; speed **ASSUMED**).

## 5. CI matrix and cost

With ~130 targets (D-034), running every check on every target every night is not affordable. Targets
are split into two tiers.

- **Tier 1, era representatives (~22):** one target per (loader, era, render model, input system)
  cell, plus the most-played release of each loader: `legacy-fabric:1.8.9, 1.12.2, 1.13.2`;
  `forge:1.8.9, 1.12.2, 1.13.2, 1.16.5, 1.18.2, 1.20.1, 1.20.6, 1.21.1, 1.21.11, newest 26.x`;
  `fabric:1.14.4, 1.16.5, 1.18.2, 1.20.1, 1.21.1, 1.21.11, 26.1.2, newest 26.x`;
  `neoforge:1.20.1, 1.21.1, newest 26.x`. Full L4 (all samples).
- **Tier 2, every other target:** L2 (C1–C9) and a launch smoke (`hud-box` only: game reaches the
  world, both probes pass, no Mixin error).

| Job | Trigger | Targets | Time (estimate) |
|---|---|---|---|
| L1 | every push | — | 3 min |
| L2 | push touching bindings, nightly | all (~130) | ~25 min warm cache, sharded over 4 jobs |
| L3 | push touching runtime, nightly | 2 GL contexts (+ Vulkan after M8) | 2–4 min |
| L4 tier 1 | nightly, PR touching bindings/runtime/emitters | ~22, one job each in parallel | 4–6 min per target |
| L4 tier 2 | weekly, every release | ~108 | 3–4 min per target |
| L5 | release | core six + `forge:1.12.2`, `fabric:1.16.5`, `fabric:26.3` on the maintainer's Windows PC (required); macOS and Linux + AMD when available (D-048) | ~1–2 h human time |

Monthly cost (ASSUMED until measured, S-14): nightly tier 1 ≈ 110 runner-min × 30 ≈ 3,300; weekly tier 2
≈ 380 × 4 ≈ 1,500; L2 nightly ≈ 750; total ≈ 5,500–6,000 runner-minutes per month. Hosted CI is free
for public repositories, which is why the 0.x emitter is pulled into the public repository's CI with a
deploy key instead of running CI in the private one (D-035). Cache: ~60 MB per obfuscated target plus
~1 MB `mcp_config` per SRG target, ≈ 8 GB for the full matrix; the cache is split per tier so tier-1
jobs stay warm.

## 6. What is deliberately not tested automatically

- Multiplayer servers (client-only v1; L5 joins a vanilla server once per release).
- Interaction with other popular mods (Sodium, Iris, OptiFine on 1.8.9). Listed as risk R-09; L5 runs
  the smoke mod with Sodium/Iris on `fabric:1.21.1` before each release.
- Real GPU driver differences beyond the L5 machines; release notes name the OS and GPU each release was checked on (D-048).
