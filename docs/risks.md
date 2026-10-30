# Risks

Every risk with likelihood (L) and impact (I) on a three-step scale (low / med / high), the spike that
retires it early, and the fallback if the spike fails. Every claim labelled **ASSUMED** in the other
documents maps to a spike here.

## 1. Risk register

| Id | Risk | L | I | Spike | Fallback |
|---|---|---|---|---|---|
| R-01 | **(retired by M0, R-EXP-02)** Type aliasing produces bytecode that verifies offline but fails with real game classes (class loading order, loader class loaders, Mixin-transformed targets) | low (E1 passed offline) | high | S-01 | Switch the failing type to `owned` with boundary conversion ([binding-format.md](binding-format.md) §4.1); measure allocation cost on hot paths |
| R-02 | Legacy Fabric intermediary ids are not stable across 1.8.9–1.13.2 for some members, so one `lf:` rule does not cover the range | low (checked members were stable) | med | S-03 | Per-version `lf:` rules where needed; the validator (C2) finds every case |
| R-03 | **(retired by M0 for all six core eras, R-EXP-02)** A loader-era's runtime namespace is not what the data says | low (all verified, R-LDR-*) | high | S-01 | Fix the era row in `targets.json`; data, not code |
| R-04 | A future event has no native hook on legacy Forge, which has no Mixin | med | med | S-12 | Generated coremod (D-014); if `@SortingIndex` cannot see notch names, use the event's nearest native event or mark the event unsupported on that era with a `Feature` |
| R-05 | Raw GL next to Minecraft's renderer corrupts its cached state or draws at the wrong depth/framebuffer | med | high | S-06 | Extend the guard to the failing state; on 1.21.5+ move that target to the `gpu` backend earlier |
| R-06 | The replay point after `GuiRenderer` is missing or wrong on 1.21.6+ / 26.x | med | high | S-08 | Picture-in-picture renderers per era (R-EVT-15) |
| R-07 | Mojang removes OpenGL or makes Vulkan default in a release Blanket must support | high (26.4 snapshots default to Vulkan, R-GFX-05) | high | S-10 | M8 `gpu` backend; until then those targets draw nothing and log once ([rendering-api.md](rendering-api.md) §3.6) |
| R-08 | Record-and-replay draws mod HUD content over vanilla toasts and chat on 1.21.6+, which authors find unacceptable | med | low | S-08 | PiP renderers (same as R-06) |
| R-09 | Incompatibility with popular rendering mods (Sodium, Iris; OptiFine on 1.8.9) that replace the hooked code or GL state handling | med | med | L5 manual run each release | Documented incompatibility in v1 (the target is fixed at build time, so Blanket cannot switch hooks when another mod is present); a better hook point for everyone if one exists |
| R-10 | Generator grows toward a Loom (whole-game remapping, dev environment) | med | med | — (design rule) | Review rule: any change that reads more than the used set needs a decision entry |
| R-11 | Fixed glyph sets miss characters mods need (player names, CJK chat) | high | low | — | Gradle `fonts.glyphs` ranges; later a `gpu`-era runtime rasterizer using LWJGL3 stb where present |
| R-12 | The empty-stack sentinel `new ItemStack((Item) null, 0, 0)` misbehaves on 1.8.9 | low | med | S-15 | Owned `ItemStack` wrapper on ≤1.10.2 targets only |
| R-13 | Shaded libraries break under relocation (reflection by computed names, own bytecode generation, Kotlin reflection) | med | med | M5 `lib-demo` | `relocationExclusions`, `rewriteClassNameStrings`, or `dependency` mode; documented |
| R-14 | Authors resent Java 8 source level when legacy targets are included | high | med | — | JvmDowngrader as an opt-in later, after a license decision (D-013) |
| R-15 | Hooks on synthetic names (`method_62214`, `lambda$addMainPass$0`) break with each release | high | low | C7 per release | Rule update per release; it is caught at build time, never at runtime |
| R-16 | Mojang changes the mappings terms | low | high | C8 | Stop using new mappings until reviewed; obfuscated versions are finite (≤1.21.11), so no new files will appear |
| R-17 | Key codes differ per window system (LWJGL 2, GLFW, SDL3 from 26.3) beyond a static table | med | low | S-16 | Translate through the game's own key-name lookup in an adapter |
| R-18 | Key bindings registered too late (options already loaded) on some loader | med | med | S-07 | Re-read the saved binding from `options.txt` in the recipe ([events-and-hooks.md](events-and-hooks.md) §4.7) |
| R-19 | Four Minecraft releases a year (26.1, 26.2, 26.3 in 2026, R-MAP-01) multiply binding maintenance, now on three loaders with nothing dropped (D-034) | high | high | — | Most new releases need only `official:` renames (R-29); C1–C9 list every gap; the four-week promise slips to "best effort" for a release that needs a new adapter era, stated in the release notes |
| R-20 | Another mod's Mixin conflicts with a generated injector (redirects, overwrites) | low | med | L5 | Blanket uses `@Inject` everywhere except legacy Fabric's `Keyboard.next()`/`Mouse.next()` redirects (D-043), where a second redirecting mod is a hard Mixin conflict; conflicts show as Mixin errors naming the class; documented |
| R-21 | The build-time reading of Mojang mappings is challenged legally | low | high | — | Conditions in [mappings-and-versions.md](mappings-and-versions.md) §8; v1 legal gate; obfuscated-version support could fall back to intermediary-only binding names for Fabric |
| R-22 | (closed by D-033: SRG eras are targets) | — | — | — | — |
| R-31 | The full matrix (~130 targets) makes 1.0 much later and CI much larger than the five-target plan | high | high | M7 order (era representatives first) | Tiered CI ([testing-strategy.md](testing-strategy.md) §5); a release that fails tier 2 on a few targets ships with those targets marked "known broken" in the report rather than blocking 1.0, which matches "full functionality, a couple of bugs" |
| R-32 | MCPConfig data moves or disappears from Forge's maven for old versions | low | high | C8 | Cache in the lockfile cache; the data is zlib-licensed (R-MAP-16), so a project-hosted mirror of `joined.tsrg` with its notice is allowed if needed |
| R-33 | SRG ids are not stable inside an era, so one binding rule does not cover e.g. 1.17–1.20.4 | low (bindings never name SRG ids; they are looked up per target) | low | C2 | — (per-target lookup already handles it) |
| R-34 | `WORLD_RENDER` fires before entities write depth on some era, so panels show through players | med | med | S-20 | Move that era's hook later (Fabric API's `AFTER_ENTITIES`-style point); C7 keeps it per version |
| R-35 | Input cancelling misses a path on some era (a key handled outside the hooked method, or key-binding suppression too late for movement) | med | med | S-21 | Add the missing hook per era; C7 keeps it per version; on eras without Mixin, report the gap in the support report |
| R-36 | Overlay jars built against slightly different loader/game versions than the target load but crash | med | low | M5 `overlay-demo` | Overlay references are checked against the target's class index like adapters; a missing symbol is a build error |
| R-37 | GPL-3.0 deters authors who want permissive or closed mods | high | med | — | Intended by D-038; stated in the README so authors know before they start |
| R-39 | Packet listeners call client-thread API from the network thread | high | med | `--testkit` thread check | Javadoc, `Client.runOnClientThread`, a runtime assertion in testkit builds |
| R-40 | The `packet_handler` name or pipeline layout differs on some era, or early play-phase packets slip past before the handler is added | med | med | S-22 | Per-era handler anchor in glue; document that the first packets after join may be missed |
| R-41 | Custom payload send on 1.20.5+ needs a payload type registered with a codec, which vanilla does not expose for unknown ids | med | low | S-22 | `Feature.CUSTOM_PAYLOAD_SEND` false on those eras until an adapter registers the type |
| R-38 | The private `blanket-emit` module leaks or the deploy key is exposed in CI logs | low | low | — | Nothing secret in the code itself; worst case is an early release. Fork PRs never get the key |
| R-23 | Per-frame overhead (state guard `glGet`s, command list, replay) costs measurable FPS | low | low | S-06 (measured in M4) | Cache immutable state per frame; batch all mod draws per event into one guarded list (already the design) |
| R-24 | Build-time font baking with Java 2D fails on headless CI JDKs without `java.desktop` | low | low | M4 | Bundle a pure-Java TrueType rasterizer in the generator |
| R-25 | In-game automation (offline launch, llvmpipe speed, Xvfb) is flaky or too slow | med | med | S-14 | Fewer ticks per run; real GPU self-hosted runner |
| R-26 | Metadata details marked ASSUMED are wrong (key casing, dependency syntax, `dist` handling, lang formats, `MixinConfigs` on modern Forge) | med | low | S-04, S-05, S-11 | Fix the emitter; every case is visible on first launch |
| R-27 | A lowering bug yields a `VerifyError` only when a rarely used path loads in game | med | med | L1 `-Xverify:all` on every lowered class | `--verify` mode in `generate` loads every output class with the class index as a verifier context |
| R-28 | `ScreenHost` override signatures change per era and a method silently stops overriding | med | med | C6 | — (C6 makes it a Blanket build error) |
| R-30 | Hook points for eras added in M7 differ from the verified neighbours they are assumed to match | med | low | S-17 | Per-version hook rules; C7 finds every case at build time |
| R-29 | Mojang renames after 1.21.11 need hand-written `official:` rules every release | high | low | C2 | UniversalCraft's per-release rename tables as a checklist (R-PA-04) |

## 2. Spikes

| Id | Spike | Milestone | Pass condition |
|---|---|---|---|
| S-01 | Thesis spike: one jar → six jars (core six), aliasing and runtime names in real games | M0 | M0 gate |
| S-02 | Resolve every API alias class and member on the core six from data (closes **A** cells of [mappings-and-versions.md](mappings-and-versions.md) §6) | M1 | C1–C3 green |
| S-03 | LF id stability: resolve the 200 members most referenced by the `legacy` adapters and bindings on 1.8.9, 1.12.2 and 1.13.2 through one `lf:` id each | M1 | ≥ 95% stable; the rest get per-version rules |
| S-04 | Legacy Forge metadata: `mcmod.info` key casing, `FMLInitializationEvent` as init point, `.lang` format, `dependencies` string | M0 (partly), M5 | mod shows in the mod list with name, version, logo; key names translated |
| S-05 | Fabric/NeoForge metadata: `schemaVersion`, `depends` keys and version predicates, mod id rule, NeoForge dependency tables, `dist` behaviour on 20.2–20.6, `pack.mcmeta` need, `en_us.json` | M0 (partly), M5 | mod listed and loads; a declared dependency missing produces the loader's own error |
| S-06 | GL glue and guard in each game: framebuffer id, projection/view matrices, GUI scale, fixed-function colour reset on 1.8.9, per-frame cost | M0, M4 | M4 gate including the vanilla hotbar probe; cost < 0.2 ms per frame for 1,000 quads on the L5 machines |
| S-07 | Key binding registration per loader: Fabric options Mixin point per era, NeoForge `RegisterKeyMappingsEvent`, Forge 1.8.9 `ClientRegistry.registerKeyBinding` | M3 | binding appears in the controls screen, rebinding persists across restarts |
| S-08 | **(passed in M0, R-EXP-03)** 1.21.6+ / 26.1.2: the call of `GuiRenderer#render` inside `GameRenderer#render`, and the replay after it | M0 | rectangle visible over the HUD, under nothing, on `fabric:26.1.2` |
| S-09 | Reversed depth on 26.2+: clear value and compare function | M7 | world box occluded correctly by terrain on `fabric:26.3` |
| S-10 | `gpu` backend: custom pipeline with Blanket's GLSL through Mojang's abstraction; software Vulkan (lavapipe) under Xvfb | M8 | a coloured quad drawn on Vulkan in CI or on an L5 machine |
| S-11 | Modern Forge (1.20.6+): `MixinConfigs` honoured; EventBus 7 listener generation; native event forms for 1.20.6–1.21.5 | M7 | every v1 event fires on `forge:1.21.11` and `forge:1.20.6` |
| S-17 | Hook points assumed for M7 eras: Fabric HUD on 1.14–1.15.2 and 1.17–1.19.4, NeoForge tick event switch at 1.20.5 | M7 | C7 green and L4 passes on each added target |
| S-18 | Forge 1.9–1.11.2 notch remapping (same `DeobfuscationTransformer` as 1.8.9/1.12.2) and the SRG-era native events of [events-and-hooks.md](events-and-hooks.md) §4.9 | M0 (`forge:1.20.1`), M7 | every v1 event fires on `forge:1.10.2`, `forge:1.16.5`, `forge:1.18.2`, `forge:1.20.1` |
| S-19 | Overlay merge: a Loom-built overlay with a ModMenu entry point, merged into the Fabric 1.21.1 jar | M5 | config screen opens from ModMenu; `Blanket.overlay` returns the implementation on that target and `null` on the others |
| S-21 | Input routing per era (§4.10 of events-and-hooks): consumed click does not attack, captured scroll does not change the hotbar slot, captured `W` stops movement only when bound to forward, interactive panel takes clicks, release follows press | M6 | testkit-simulated input on every tier-1 target |
| S-22 | Packet pipeline per era: handler anchor, local channel layout, play-phase timing, `BLOCK_UPDATE`/`CHAT`/`CUSTOM_PAYLOAD` views read correctly, cancel works both ways, custom payload send on 1.20.5+ | M6 | `samples/packets` passes on every tier-1 target |
| S-20 | Depth contract: entity depth present at the `WORLD_RENDER` hook on every era representative, including the 1.21.9+ submit/render split and Fabulous graphics | M6 | panel behind an armor stand is hidden, in front is visible, on every tier-1 target |
| S-12 | Legacy Forge coremod with low `@SortingIndex` sees notch names | when first needed | transformer patches a notch-named method on `forge:1.8.9` |
| S-13 | LWJGL 2 `Display` under Xvfb for golden tests of `Lwjgl2Gl` | M4 | golden scene matches the LWJGL 3 GL 2.1 output |
| S-14 | Instance provisioning, offline single-player launch for the core six, llvmpipe frame rate (**Windows + GPU part passed in M0**, R-EXP-07/08; CI/llvmpipe part moves to M3) | M0 | 100 ticks in a world within 6 minutes per target on the CI runner |
| S-15 | ItemStack sentinel on 1.8.9: construct, query through adapters, never leak to vanilla | M2 | `api-tour` empty-hand values correct on both 1.8.9 targets |
| S-16 | Key code tables: LWJGL 2 (≤1.12.2), GLFW (1.13–26.2), SDL3 (26.3+) | M3, M7 | every `Key` constant toggles the testkit probe on each target |

## 3. Adversarial pass (cases tried against the design)

| Case | Outcome | Where handled |
|---|---|---|
| Shape-changing API: item components (1.20.5) | The v1 `ItemStack` surface (id, count, name, emptiness) survives 1.20.5 unchanged; any data-reading member must be an adapter per era, and the shape check forbids a direct lowering across the change | [lowering-and-adapters.md](lowering-and-adapters.md) §9 |
| Lambda crossing an aliased type with a field lowering | `LambdaConversionException` reproduced in E1; bridge fixes it | [lowering-and-adapters.md](lowering-and-adapters.md) §4.3 |
| Java 21 pattern switch over API types | bootstrap arguments carry class names as strings; rejected with a clear error | [lowering-and-adapters.md](lowering-and-adapters.md) §3 |
| Library whose API differs between versions | caught per target by the compatibility check; no bridging in v1 | [libraries.md](libraries.md) §3.2 |
| Event with no hook on legacy Forge | coremod path decided, not needed by v1 events | R-04, D-014 |
| Vulkan target | no GL context; runtime detection and `gpu` backend plan | R-07 |
| `Gui.render` renamed and re-purposed on 26.1 | resolution fails loudly; explicit `official:` rule; drawing moved to a replay point | binding example 11 |
| Two Blanket mods in one game | separate relocation prefixes, separate Mixin classes, separate runtimes | [lowering-and-adapters.md](lowering-and-adapters.md) §8 |
| Mod compiled at Java 21 with a 1.8.9 target | class-version check fails the build | [lowering-and-adapters.md](lowering-and-adapters.md) §7 |
| `forge:1.8.9` needing SRG names | not needed: FML remaps notch references in mod classes | [loader-emitters.md](loader-emitters.md) §5 |
| Player switches graphics backend mid-session on 26.2+ | `glActive()` is checked every frame | [rendering-api.md](rendering-api.md) §3.6 |
| Mixin annotation strings on legacy Forge (not remapped by FML) | no Mixin on that era; coremods use bytecode, not strings | [loader-emitters.md](loader-emitters.md) §5 |
