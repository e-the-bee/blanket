# M3 verification
Verdict: PASS (0 blocker, 0 major, 8 minor; gate reproduced 12 of 12)
Verified by: Opus (prompts/verify.md, N=3), 2026-11-27
Code verified: public `96a4925`, blanket-emit `c1dc022`; diffs read since the M2 verification (public `36df7c5`,
blanket-emit `44d0833`). Read: `prompts/common.md`, `prompts/m3.md`, `docs/milestones.md` M3, `docs/events-and-hooks.md`,
`docs/testing-strategy.md` §4, `docs/progress/M3.md`, D-064..D-067, R-EXP-25..29, the M3 execution plan (slices and
contracts), `git log` of both repositories.

## Commands run

Builds and lookups ran on testbox machine 110 through `S:/blanket-wt/tb.sh` (copy of the work tree, one slot); javap
ran on the jars in `blanket-emit/m0-spike/.cache` (JDK 21 `javap`, read-only); the gate ran in a fresh container 157.

### 1. Build and tests
- `tb.sh W:/java-mods/blanket/repo/blanket "bash gradlew clean build --no-build-cache --no-daemon --console=plain"` →
  `BUILD SUCCESSFUL in 2m 16s`, exit 0. JUnit XML totals: **592 tests, 0 skipped, 0 failures, 0 errors** (result files:
  core 69, emit 10, testkit 11, runtime 9, api 2, cli 2). validateBindings inside the build: `0 errors, 2 warnings`
  (`GlueKeys.register: SHAPE not checked` on neoforge:1.21.1 and forge:1.20.1, finding 4).
- Test-diff audit: `git diff 36df7c5..HEAD -- '*src/test*'` (48 files, +4561/−8) and `git -C blanket-emit diff 44d0833..HEAD
  -- '*src/test*'` (21 files, +862/−7). No test file deleted or renamed (`--diff-filter=DR` empty), no `@Disabled`. The
  removed assertion lines are rewrites with the same strength: `BindingFilesTest` message now names `via`; ValidatorTest's
  C7 member label gained `(event FX_TICK)` and a new two-event case; emit `EmitterTest`/`EmitLowerTest` follow the
  `<Event>$<HookId>` Mixin class names. Added skips: `KeyCodesTablesTest` assumes the stub library list exists (it ran:
  0 skipped); `OnlineProvisionTest` is `@Tag("online")`, excluded from `build` as the plan says; `TestkitApiTourEmitTest`
  keeps three stale "is not written yet" assumptions (finding 5).

### 2. Every task (code and tests read)
- **T1 Mixin emitter** (emit `wire/MixinWriter`): one class per hook named `<Event>$<HookId>`, `@Mixin` invisible,
  `@Inject` visible with `require=1`, config `"required": true` + `"defaultRequire": 1` (`FabricEmitter.java:67-71`).
  `GoldenClassesTest` compares Textifier dumps for HEAD, TAIL, RETURN, INVOKE shift+ordinal, INVOKE before,
  INVOKE_STRING and the `@Shadow @Final @Mutable` form (13 goldens under `src/test/resources/golden/`). C7 runs inside
  planning (`Planner.check` → `ModCheck.run` → `TargetChecks`), and the diagnostic names hook and event
  (`ValidatorTest`: `hook tick.end (event FX_TICK)`, wrong call-site count → `2 call sites`). The emitted hud-box jars
  confirm it (javap -v on 110): e.g. fabric:1.21.1 `CLIENT_TICK_START$tick_start` targets `net.minecraft.class_310`
  `method_1574()V` HEAD `require=1`; 26.1.2 `HUD_RENDER$gui_replay` INVOKE in `GameRenderer#render`; 1.8.9
  `WORLD_RENDER$world_legacy` INVOKE_STRING `method_9776(IFJ)V`.
- **T2 Native listeners**: legacy Forge `Listeners` on `MinecraftForge.EVENT_BUS` (`EventBus.register(Object)`) and the
  `fml` body in the `FMLInitializationEvent` method; Forge 1.20.1 game bus + `ModListeners` on
  `FMLJavaModLoadingContext.get().getModEventBus()`; NeoForge `NeoForge.EVENT_BUS` + constructor `IEventBus` (javap of the
  emitted Entry classes). Phase filters: `BindingsEventsTest` expects `GETFIELD TickEvent.phase == GETSTATIC Phase.START`
  on both Forge eras; golden `forge-listeners-game.txt` shows the `IF_ACMPNE` filter. NeoForge uses `register(Object)` +
  `@SubscribeEvent`, not the documented `addListener` (finding 1).
- **T3 Runtime dispatcher**: `EventSource` (throttle once per listener per 60 s, counter, other listeners run;
  `EventSourceTest` with a fake clock asserts 4 failures counted, 2 log lines, stack trace only for the first);
  `Dispatch.tickStart` + `WorldChange` (identity, leave before join; `WorldChangeTest`); `Dispatch.screenOpen` +
  `ScreenChange` (`ScreenChangeTest`, 6 cases incl. Forge replacement); HUD guard `GlueScreen.current() != null` in
  `hudRecord` (D-018). Behavioural tests, not "it runs" tests. SCREEN_OPEN and WORLD_LEAVE never run in a game (finding 7).
- **T4 Keys**: `KeyHandover.json` with `usedBy` (`BindingsKeyHandoverTest`: wired on six targets when `register` is used,
  absent otherwise); `KeyHandoverTest` (append order, once per queue, no duplicates); `KeyCodesTablesTest` reads the
  `static final int` constants from the real LWJGL 2 / GLFW jars and checks the tables are complete, distinct and
  inverse; `ForgeLegacyKeysTest`. The `options.txt` re-read is not built: R-EXP-26 / D-066 with javap offsets (I re-checked
  1.8.9 and 1.21.1, check 3). The 26.1.2 category key form is answered in R-EXP-27.
- **T5 Harness + `--testkit`**: `blanket-testkit` (VerdictTest 15 cases, 56 offline tests), in-game
  `runtime/testkit` (`SessionTest` 14 cases incl. key round, anchor wait, no-HUD-frame failure; `SpecTest`), `--testkit`
  (`TestkitEmitTest`: with the flag the entry calls `Testkit.start` right after `Boot.init`, without it no jar has a
  testkit class). The emitted jars confirm both (Entry javap: `Boot.init` then `Testkit.start`). Blocking screens are
  failed only indirectly (finding 8).
- **T6 hud-box**: `samples/hud-box/.../HudBox.java` matches testing-strategy §4 (tick counts, join, anchor on the ground,
  `#FF00FF` 40×40 at (10, 10), green box, key `toggle`/`H`); `checkHudBoxJars`: `jar check passed: 6 jars, 423 classes`.

### 3. Names come from data (40 references re-derived)
`lookup` (`tb.sh ... "bash gradlew -q :blanket-bindings:lookup --ref '<ref>' --target <t>"`, script
`S:/blanket-wt/m3v-lookup.sh`), runtime names printed:

| # | Reference (where used) | Target → runtime name | OK |
|---|---|---|---|
| 1 | `lf:class_1600#method_2928(Lclass_388;)V` (hooks `screen.open`) | legacy-fabric:1.8.9 → `class_1600.method_2928 (Lclass_388;)V` | yes |
| 2 | `Minecraft#setScreen(Screen)V` (`screen.open`) | fabric:1.21.1 → `class_310.method_1507 (Lclass_437;)V` | yes |
| 3 | `official:Minecraft#setScreen(Screen)V` (`screen.open`) | fabric:26.1.2 → `Minecraft.setScreen` | yes |
| 4 | `lf:class_1600#method_2954()V` (`tick.start`) | legacy-fabric:1.8.9 → same | yes |
| 5 | `official:Minecraft#tick()V` (`tick.start`) | fabric:26.1.2 → `Minecraft.tick ()V` | yes |
| 6 | `lf:class_347#method_865()V` (`keys.options`) | legacy-fabric:1.8.9 → same | yes |
| 7 | `Options#load()V` (`keys.options`) | fabric:1.21.1 → `class_315.method_1636 ()V` | yes |
| 8 | `Options#keyMappings:[KeyMapping` (`mutable`, GlueKeys) | fabric:1.21.1 → `class_315.field_1839 [Lclass_304;` | yes |
| 9 | `official:Options#load()V` | fabric:26.1.2 → `Options.load ()V` | yes |
| 10 | `official:Options#keyMappings` | fabric:26.1.2 → `Options.keyMappings` | yes |
| 11 | `lf:class_1600#field_3816:Lclass_388;` (GlueScreen.current) | lf:1.8.9 → same; forge:1.8.9 → `ave.m Laxu;` | yes |
| 12 | `lf:class_1600#method_2965()` (GlueKeys.options) | forge:1.8.9 → `ave.A ()Lave;` | yes |
| 13 | `lf:class_1600#field_3823:Lclass_347;` (GlueKeys.options) | forge:1.8.9 → `ave.t Lavh;` | yes |
| 14 | `lf:class_347#field_945:[Lclass_327;` (GlueKeys) | lf:1.8.9 → same; forge:1.8.9 → `avh.ax [Lavb;` | yes |
| 15 | `lf:class_1600#method_2949()V` (GlueTestkit.quit) | lf:1.8.9 → same | yes |
| 16 | `Minecraft#stop()V` (GlueTestkit.quit) | fabric:1.21.1 `class_310.method_1592`; neoforge `Minecraft.stop`; forge:1.20.1 `m_91395_` | yes |
| 17 | `official:Minecraft#stop()V` | fabric:26.1.2 → `Minecraft.stop ()V` | yes |
| 18 | `Minecraft#screen:Screen` (GlueScreen.current) | fabric:1.21.1 `class_310.field_1755`; neoforge `screen`; forge:1.20.1 `f_91080_` | yes |
| 19 | `official:Minecraft#screen` | fabric:26.1.2 → `Minecraft.screen` | yes |
| 20 | `KeyMapping#click(InputConstants$Key)V` (KeyPress) | fabric:1.21.1 `class_304.method_1420`; forge:1.20.1 `m_90835_` | yes |
| 21 | `InputConstants#getKey(String)` (KeyPress) | fabric:1.21.1 `class_3675.method_15981`; forge:1.20.1 `m_84851_` | yes |
| 22 | `KeyMapping#saveString()` (KeyPress) | fabric:1.21.1 → `class_304.method_1428` | yes |
| 23 | `PauseScreen` (ScreenKinds modern) | fabric:1.21.1 `class_433`; forge:1.20.1 same name | yes |
| 24 | `CreativeModeInventoryScreen` (ScreenKinds) | fabric:1.21.1 → `class_481` | yes |
| 25 | `official:AbstractContainerScreen` (ScreenKinds unobf) | fabric:26.1.2 → found | yes |
| 26 | `loader:fml...gameevent.TickEvent$Phase#START` (ClientTickStart filter) | forge:1.8.9 → found | yes |
| 27 | `loader:net.minecraftforge.event.TickEvent$Phase#START` | forge:1.20.1 → found | yes |
| 28 | `loader:net.minecraftforge.event.TickEvent#phase` | forge:1.20.1 → found | yes |
| 29 | `loader:...neoforge.client.event.ClientTickEvent$Pre` | neoforge:1.21.1 → found | yes |
| 30 | `loader:...GuiOpenEvent#gui:Lclass_388;` (ScreenOpen) | forge:1.8.9 → `GuiOpenEvent.gui Laxu;` | yes |
| 31 | `loader:...forge...ScreenEvent$Opening#getNewScreen()` | forge:1.20.1 → found | yes |
| 32 | `loader:...forge...ScreenEvent$Closing` | forge:1.20.1 → found | yes |
| 33 | `loader:...neoforge...ScreenEvent$Opening#getNewScreen()` | neoforge:1.21.1 → found | yes |
| 34 | `loader:...neoforge...ScreenEvent$Closing` | neoforge:1.21.1 → found | yes |
| 35 | `loader:...forge...RegisterKeyMappingsEvent#register(KeyMapping)V` (GlueKeys) | forge:1.20.1 → found | yes |
| 36 | `loader:...neoforge...RegisterKeyMappingsEvent#register(KeyMapping)V` | neoforge:1.21.1 → found | yes |
| 37 | `loader:...fml.common.event.FMLInitializationEvent` (KeyHandover `fml`) | forge:1.8.9 → found | yes |
| 38 | `loader:FMLJavaModLoadingContext#get()` (ForgeModernEmitter constant) | forge:1.20.1 → `FAIL at jar: ... not in the loader artifact` | lookup cannot; javap below |
| 39 | `loader:FMLJavaModLoadingContext#getModEventBus()` | forge:1.20.1 → same FAIL | lookup cannot; javap below |
| 40 | control: `loader:net.minecraftforge...ScreenEvent$Opening` on neoforge:1.21.1 | FAIL with candidate `net.neoforged...ScreenEvent$Opening` | negative control behaves |

javap (JDK 21) on the real jars, semantics of the names above:
- 1.8.9 `client-1.8.9.jar` (lfint: `class_1600`=`ave`, `class_347`=`avh`, `method_2928`=`a(Laxu;)V`,
  `method_865`=`avh.a()V`, `method_2949`=`ave.m()V`): `ave.a(axu)` stores `putfield m:Laxu;` (setScreen); `ave.m()` is
  `putfield B:Z` false (scheduleStop); `public axu m; public avh t; public static ave A()`; `public avb[] ax` (not final);
  `avh.<init>(ave,File)` `1070: putfield ax`, `1116: ldc "options.txt"`, `1185: invokevirtual a()V` (load after the key
  array, as R-EXP-26 says); `avh.a()` opens a `BufferedReader(FileReader(File))`. `azc` = lf `class_422` =
  SurvivalInventoryScreen, `ayw` = `class_418` the base (lfyarn), as the legacy `ScreenKinds` comment says.
- 1.21.1 `client-1.21.1.jar` (int: `class_315`=`fgs`, `field_1839`=`W`, `method_1636`=`av`, `class_310`=`fgo`):
  `public final fgm[] W;` (final, hence `@Mutable`), `fgs.<init>` `2709: putfield W`, `3499: invokevirtual av()V`;
  `fgo.q()` = `putfield aZ:Z` false (stop); `fgo.a(fod)` writes `y:Lfod;` (setScreen/screen).
- 26.1.2: `public final KeyMapping[] keyMappings; public void load();` in `Options`; `Minecraft` has `public Screen
  screen; setScreen(Screen); stop(); tick()`.
- Loader jars: forge 1.8.9 universal `public axu gui;` in `GuiOpenEvent`, `TickEvent.phase`, `Phase.START`; forge 1.20.1
  universal `ScreenEvent$Opening.getNewScreen()`, `ScreenEvent$Closing`, `RegisterKeyMappingsEvent implements
  IModBusEvent` with `register(KeyMapping)`; neoforge 21.1 universal the same three plus `ClientTickEvent$Pre`.
- Emitted hud-box jars (`:blanket-emit:emitHudBox` on 110, javap -c/-v): Forge 1.20.1 Entry calls
  `FMLJavaModLoadingContext.get:()L...FMLJavaModLoadingContext;` and `getModEventBus:()L...IEventBus;`; `ModListeners`
  has `@SubscribeEvent keysRegister(RegisterKeyMappingsEvent)`; Mixin classes carry `require=1` and the intermediary
  names of rows 4-8. In gate container 157, JDK 21 `javap -p` on
  `.mc/libraries/net/minecraftforge/javafmllanguage/1.20.1-47.4.28/javafmllanguage-1.20.1-47.4.28.jar`:
  `public static net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext get();` and `public
  net.minecraftforge.eventbus.api.IEventBus getModEventBus();` (rows 38-39 confirmed by javap, not by lookup).

No mismatch found. Every new hooks.json/glue rule carries a javap or lfyarn note.

### 4. Boundaries
- `grep 'net/minecraft|net\.minecraft|com/mojang|class_\d+|method_\d+|field_\d+|m_\d+_|f_\d+_'` over
  `blanket-core/src/main` and `blanket-emit/src/main`: only Forge Maven URLs (`net/minecraftforge`, loader),
  emitter loader constants (`net/minecraftforge/...`), and `Resolver.java:81` the generic Legacy Fabric id pattern
  `net/minecraft/class_\d+` (a namespace rule of binding-format §2.4, no class named). No Minecraft name.
- Java 8: `blanket-api`, `blanket-runtime`, `samples/hud-box` `options.release.set(8)`; adapters `compileAdapters: javac
  --release 8` (`blanket-bindings/build.gradle.kts:160`). `blanket-testkit` is Java 21 (a tool, as planned).
- Emitter code only in `blanket-emit/`: no `spongepowered`/`SubscribeEvent`/Mixin writer in public `src/main` (ASM
  writers there are the existing lowering/adapter tools).
- SPDX: every added `.java`/`.kts` in both repos starts with `// SPDX-License-Identifier: GPL-3.0-only` (loop over
  `git diff --name-only --diff-filter=A`, no file missing).

### 5. Scope
- Nothing from later milestones: no `INPUT` or packet recipe, no new targets (events files cover the core six; the
  Fabric `tick.start` rule `26.1..` is open-ended like the existing `tick.end`).
- Deliverables present: §4.1-4.7 recipes for the core six (`ClientTickStart`, `ClientTick`, `WorldJoin`/`WorldLeave` via
  tick start, `HudRender`, `WorldRender`, `ScreenOpen`, `KeyHandover`), dispatcher, Mixins and native listeners, key
  registration (S-07), key code tables (S-16), `--testkit`, `blanket-testkit`, `samples/hud-box`, CI job definitions
  (`.github/workflows/ci.yml`, L1 + L4 matrix of the six, not run). Missing: none.

### 6. Docs honesty
- `git diff 36df7c5..HEAD -- docs/decisions.md`: additions only (D-064..D-067), all **Provisional (worker)**; no existing
  entry edited. R-EXP-25..29 added.
- Surprises/deviations in `docs/progress/M3.md` with an entry: Forge/NeoForge screen close (R-EXP-25), S-07 order
  (R-EXP-26, D-066), 26.1.2 categories (R-EXP-27), slime (R-EXP-28), glue → adapter and LF descriptors (D-065), 1.8.9
  gate facts (R-EXP-29), world entry (D-067), KEY_HANDOVER (D-065/066), Forge 1.8.9 append (D-066), no re-read (D-066).
  Without an entry: NeoForge listener form (finding 1); the shrink bug, Fabric 26.x `screen.open` range, hud-box without
  text (finding 2).
- ASSUMED items M3 was to retire: events-and-hooks §4.7 rows (S-07) now say "verified by javap"; `risks.md` S-07/S-16
  rows are unchanged and their acceptance criteria were not exercised (finding 3). §4.1/§4.5 rows used on the core six
  are verified (R-EVT, R-EXP-25); Forge 1.20.6+ closing stays **ASSUMED** and is marked.

### 7. The gate, myself
- `gate-m3.sh 157`, two rounds on the core six in a fresh container: **12 of 12 PASS**, every run with the hand-over line
  and the toggle; table and comparison with the builder's run under "Gate reproduction".

### 8. Compatibility (C10)
- `tb.sh ... "bash gradlew :blanket-api:apiCompat ... --rerun-tasks"` → `Comparing binary compatibility of
  blanket-api-0.0.0.jar against baseline/blanket-api-0.2.0.jar`, task green (`--only-incompatible
  --error-on-binary-incompatibility --error-on-source-incompatibility`), `BUILD SUCCESSFUL`. API changes are additions
  (`EventSource(String)`, four `Events` constants, `ScreenOpen`, `ScreenKind`, `WorldJoin`, `WorldLeave`).

## Findings
1. [minor] NeoForge native listeners are registered with `IEventBus.register(Object)` and `@SubscribeEvent`, while
   `docs/events-and-hooks.md:252-256` (and `docs/milestones.md:114`, "NeoForge `addListener` generation") specify
   `NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, Event.class, consumer)` and "explicit-class overloads
   everywhere"; the progress file lists this deviation (M3.md:48) but no research-log or decision entry records it — evidence:
   golden `blanket-emit/src/test/resources/golden/neoforge-entry-mod-bus.txt` (`INVOKEINTERFACE IEventBus.register
   (Ljava/lang/Object;)V`), javap of the emitted `hudbox-0.0.0+1.21.1-neoforge.jar` Entry (offsets 36, 49) — required
   fix: add a **Provisional (worker)** D-entry (or R-EXP entry) giving the choice and its evidence (M0-M3 gates on
   neoforge:1.21.1), and change the NeoForge row of events-and-hooks §5 and the sentence after it to the generated form.
2. [minor] Three progress-file items have no research-log or decision entry, which check 6 requires: (a) surprise
   "Integration: the shrink did not follow the adapter closure" (M3.md:39, "(no research entry; build bug)"); (b) deviation
   "`screen.open` on Fabric 26.x is bound to 26.1.2 only" (M3.md:49); (c) deviation "hud-box draws no text" (M3.md:44) —
   evidence: `git diff 36df7c5..HEAD -- docs/research-log.md docs/decisions.md` adds only R-EXP-23..29 and D-064..067,
   none covering these — required fix: add one R-EXP line each (a: closure and planner used set now start from the plan's
   adapters, found by `checkHudBoxJars` "ScreenHost ... ScreenDraw, which is not in the jar"; b: hooks.json rule
   `26.1..26.1.2` because `setScreen` moves to `Gui` in 26.2, R-EVT-11; c: `Canvas2D` has no text until M4), or cite an
   existing entry in each line of M3.md.
3. [minor] S-07 and S-16 are presented as retired without meeting the acceptance criteria in `docs/risks.md:63,78`
   ("binding appears in the controls screen, rebinding persists across restarts"; "every `Key` constant toggles the
   testkit probe on each target"); `docs/events-and-hooks.md:180-183` says "verified by `javap` (S-07, M3 KEYS)" and
   risks.md is unchanged — evidence: the gate presses one key (`H`); `KeyCodesTablesTest` compares tables with library
   constants only; no run restarts the game with a changed binding — required fix: in `docs/risks.md` record for S-07 and
   S-16 what M3 established (registration point per loader by javap, the in-game hand-over line `[blanket] hudbox: handed
   1 key mapping(s) to the game`, static tables against the real LWJGL 2/GLFW constants) and what remains open with the
   milestone that closes it (persist-across-restart; per-key toggle), or run those checks.
4. [minor] C3 does not check the shape of `GlueKeys.register` on neoforge:1.21.1 and forge:1.20.1: the build prints
   `warning: neoforge:1.21.1 GlueKeys.register: SHAPE not checked: the lowering: method
   net/neoforged/neoforge/client/event/RegisterKeyMappingsEvent.register(Lfgm;)V is not in the index` (and
   `...register(Lenl;)V` on forge:1.20.1); the check rewrites the Minecraft class in a `loader:` descriptor to obfuscated
   jar names although the loader class is indexed with its real descriptor `(Lnet/minecraft/client/KeyMapping;)V` (lookup
   rows 35-36). A wrong descriptor here would only show in a game — required fix: resolve `loader:` member descriptors in
   the shape check the way `lookup` does (runtime namespace of the target), so both warnings disappear; add a
   ValidatorTest/ShapesTest case with a mod-bus `loader:` method taking a Minecraft class.
5. [minor] Stale skip guards in `blanket-emit/src/test/java/com/blanket/emit/TestkitApiTourEmitTest.java` (the loop over
   `ClientTickStart`, `WorldJoin`, `WorldLeave` with `Assumptions.assumeTrue(... "events/" + f + ".json is not written
   yet")`): the files exist, so the test runs today (0 skipped), but if one of them is renamed or removed the test is
   skipped instead of failing — required fix: delete that loop (keep the `blanket.apiTour.modJar` assumption, which only
   guards runs outside Gradle).
6. [minor] `lookup` cannot check the two Forge 1.20.1 names hard-coded in `ForgeModernEmitter.java:34`
   (`FMLJavaModLoadingContext#get()`, `#getModEventBus()`): `FAIL at jar: class
   net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext is not in the loader artifact of forge:1.20.1` (the class is
   in `javafmllanguage`, not the universal jar), so C7/lookup never cover them; the names are right (javap in check 3, and
   forge:1.20.1 logs the mod-bus hand-over in the gate) — evidence: lookup rows 38-39 — required fix:
   add `javafmllanguage` to forge:1.20.1's entry in `loader-artifacts.json` so `lookup` and C7 index it (and add the row to
   `LoaderLookupTest`), or record in D-065 that these two emitter constants are verified by javap only.
7. [minor] `SCREEN_OPEN` and `WORLD_LEAVE` never ran in a game: neither hud-box nor the testkit subscribes to
   `SCREEN_OPEN`, and no run leaves a world (`"worldLeaves": 0` in all 24 result files), so the `screen.open` Mixins, the
   `GuiOpenEvent`/`ScreenEvent$Opening`/`$Closing` listeners, the `ScreenKinds` adapters and `Dispatch.screenOpen` are
   covered by planner and unit tests only (the M3 gate does not require more; the milestone goal says "every v1 event on
   every core target") — required fix: before release 0.3 is published, add an L4 check that opens and closes a screen
   (for example the testkit opening the pause screen and asserting the `ScreenKind` sequence) and a leave, or record the
   gap as an open risk with the milestone that closes it.
8. [minor] The harness verdict has no explicit blocking-screen rule, although `prompts/m3.md` T5 and D-050 require a run to
   fail on any blocking screen; D-067 (3) and testing-strategy §4 list the verdict rules without it. It is enforced only
   indirectly: HUD_RENDER does not fire while a screen is open (D-018, `Dispatch.hudRecord`), so the HUD probe round fails
   (`SessionTest.noHudFrameForTwoHundredTicksFailsTheRound`) — required fix: state this in D-067/testing-strategy §4 (one
   sentence naming the mechanism and the test), or add an explicit `GlueScreen.current() != null` failure at probe time
   with a SessionTest case.

## Gate reproduction
Command (from GAMING-COMPUTER, after `ssh testbox sudo pct list` showed container 156 gone):
`OUT=S:/blanket-wt/M3-verify-gate ROUNDS=2 "C:/Program Files/Git/bin/bash.exe" S:/blanket-wt/gate-m3.sh 157`.
Fresh container 157 (Debian 13, 4 cores, 6 GB, Xvfb 854x480x24, Mesa llvmpipe LLVM 19.1.7), code public `96a4925`,
blanket-emit `c1dc022` (`commits.txt`; includes bca2a8e, the hand-over log line). In-container
`bash gradlew build :blanket-emit:emitHudBox :blanket-testkit:installDist`: `BUILD rc=0`, `0 errors, 2 warnings`,
`jar check passed: 6 jars, 423 classes`, `BUILD SUCCESSFUL in 3m 56s`. Then `blanket-testkit run <target> --jar
hudbox-0.0.0+<mc>-<loader>.jar --probes samples/hud-box/testkit-probes.json --timeout 360` under `xvfb-run`, two
rounds; container removed at the end (`machine 157 removed`), 19:13-19:29.

Artefacts (outside the repo): `S:/blanket-wt/M3-verify-gate/` — `commits.txt`, `build.log`, `jars.sha256`, and per run
`r<round>/<target>/` with `testkit-result.json`, `verdict.txt`, `harness.txt`, `command.txt`, `game.log`, `server.log`,
`shot.png` (missing for neoforge:1.21.1 both rounds and fabric:26.1.2 round 2).

| Target | Round | Verdict (harness rc) | Ticks start = end | Joins / leaves | HUD before key | Box (x, y) | HUD after key | Listener failures | `handed 1 key mapping(s)` | `toggle visible=false` | Mixin error / crash lines |
|---|---|---|---|---|---|---|---|---|---|---|---|
| legacy-fabric:1.8.9 | 1 | PASS (0), 27 s | 127 | 1 / 0 | `#FF00FF` | `#00FD00` (426, 363) | `#83A9F0` | 0 | yes | yes | 0 / 0 |
| forge:1.8.9 | 1 | PASS (0), 29 s | 115 | 1 / 0 | `#FF00FF` | `#00FD00` (426, 363) | `#83AAF1` | 0 | yes | yes | 0 / 0 |
| fabric:1.21.1 | 1 | PASS (0), 81 s | 201 | 1 / 0 | `#FF00FF` | `#00FA00` (427, 367) | `#7496D4` | 0 | yes | yes | 0 / 0 |
| neoforge:1.21.1 | 1 | PASS (0), 58 s | 144 | 1 / 0 | `#FF00FF` | `#00FA00` (427, 367) | `#7496D4` | 0 | yes | yes | 0 / 0 |
| forge:1.20.1 | 1 | PASS (0), 81 s | 153 | 1 / 0 | `#FF00FF` | `#00FA00` (427, 367) | `#7395D3` | 0 | yes | yes | 0 / 0 |
| fabric:26.1.2 | 1 | PASS (0), 61 s | 256 | 1 / 0 | `#FF00FF` | `#00FB00` (427, 367) | `#7B9DDC` | 0 | yes | yes | 0 / 0 |
| legacy-fabric:1.8.9 | 2 | PASS (0), 23 s | 220 | 1 / 0 | `#FF00FF` | `#00FD00` (426, 363) | `#7FA4E9` | 0 | yes | yes | 0 / 0 |
| forge:1.8.9 | 2 | PASS (0), 26 s | 116 | 1 / 0 | `#FF00FF` | `#00FD00` (426, 363) | `#82A9EF` | 0 | yes | yes | 0 / 0 |
| fabric:1.21.1 | 2 | PASS (0), 27 s | 197 | 1 / 0 | `#FF00FF` | `#00FA00` (427, 367) | `#7496D4` | 0 | yes | yes | 0 / 0 |
| neoforge:1.21.1 | 2 | PASS (0), 30 s | 148 | 1 / 0 | `#FF00FF` | `#00FA00` (427, 367) | `#7496D4` | 0 | yes | yes | 0 / 0 |
| forge:1.20.1 | 2 | PASS (0), 37 s | 140 | 1 / 0 | `#FF00FF` | `#00FA00` (427, 367) | `#7496D4` | 0 | yes | yes | 0 / 0 |
| fabric:26.1.2 | 2 | PASS (0), 32 s | 233 | 1 / 0 | `#FF00FF` | `#00FA00` (427, 367) | `#799CDA` | 0 | yes | yes | 0 / 0 |

Columns from `testkit-result.json` (every `pass: true`, `key: {pressed: true, pass: true}`) and `grep -c` on each
`game.log` (`[blanket] hudbox: handed 1 key mapping(s) to the game`; `[hudbox] toggle visible=false`;
`InvalidInjectionException|InjectionError|MixinApplyError|MixinTransformerError`; `listener for .* failed`; crash
markers). The hand-over line appears exactly once per run on all six, so every loader recipe of D-066 ran in a game
(Fabric/Legacy Fabric `Options#load` Mixin, Forge 1.8.9 FML init, Forge 1.20.1 and NeoForge mod-bus
`RegisterKeyMappingsEvent`); the builder's run predates bca2a8e and could not show it.

Comparison with the builder's gate (`docs/progress/M3-gate/`, container 155, public `0325d87`): same verdicts (12/12
PASS), same probe pixels (HUD `#FF00FF`; box `#00FA00`-`#00FE00` at (426, 363) on 1.8.9 and (427, 367) on modern; HUD
after the key is sky, `#7395D3`-`#84ABF2`), tick counts in the same range (builder 115-231, here 115-256), one join and
0 listener failures everywhere. Jar hashes differ (`jars.sha256`), as expected: bca2a8e changed `Dispatch`.
Screenshots: the builder kept none (black after quit); here they were taken around shutdown and show the world with the
green box in front of the player and no magenta rectangle (hidden by the key press) on legacy-fabric:1.8.9, forge:1.8.9,
fabric:26.1.2; fabric:1.21.1 shows the same scene blurred and forge:1.20.1 darkened, consistent with a screen drawn
during shutdown after `result written: pass` and `Stopping!` in `game.log` [INFERENCE]. Screenshots are not a verdict
input (D-067); no title screen or blocking warning screen was reached before the result was written (every run
joined, anchored and passed the HUD probe, which cannot pass while a screen is open, D-018).

## Closure of the minor findings (orchestrator, 2026-11-27)
1. Closed: D-068 (1) records the `register(Object)` listener form; events-and-hooks §5 now shows it (78e19c6).
2. Closed: R-EXP-30 (shrink), D-068 (2) (`screen.open` 26.1.2 range) and D-068 (3) (no text); each M3.md line cites its entry (78e19c6).
3. Closed by documentation: `docs/risks.md` S-07 and S-16 say "partly passed in M3" with what M3 established (javap, the hand-over log line on all six in this run, the static tables) and the open parts moved to M4 and M7 (78e19c6).
4. Fixed: 8724231 (merge 2835514). C3 resolves `loader:` member descriptors in the loader jar with runtime class names; validateBindings prints `0 errors, 0 warnings`; ValidatorTest has the pass, wrong-class and missing cases.
5. Fixed: blanket-emit 3eaccd8 removes the stale event-file skips from `TestkitApiTourEmitTest`.
6. Closed by documentation: D-068 (4) (the two Forge 1.20.1 mod-bus names are javap-checked only).
7. Partly closed: hud-box now logs SCREEN_OPEN and WORLD_LEAVE (78e19c6), so gate logs show SCREEN_OPEN from the join screens; WORLD_LEAVE and a mod-screen SCREEN_OPEN are due with the M4 gate (D-068 (6)).
8. Closed by documentation: D-068 (5) and testing-strategy §4 state that a screen left open fails the HUD round (D-018).
