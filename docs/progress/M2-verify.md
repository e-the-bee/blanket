# M2 verification
Verdict: PASS (0 blocker, 0 major; 5 minor findings, all closed: 1 and 4 by code, 2, 3 and 5 by documentation; gate reproduced from the final run's raw artefacts)
Verified by: Opus (M2Verifier), 2026-11-27

Scope: public main `d016941` (64 commits after `caef564`), blanket-emit `804891c` (15 commits after `35329b7`). All Gradle/javap runs on testbox machine 110 (OpenJDK 21) through `S:/blanket-wt/tb.sh`.

## Commands run
1. `bash gradlew clean build --no-build-cache --no-daemon --console=plain :blanket-emit:emitM0 :blanket-emit:emitApiTour` -> BUILD SUCCESSFUL; emitM0 wrote 6 jars, `checkM0Jars`: "jar check passed: 6 jars, 154 classes"; emitApiTour wrote 6 jars, `checkApiTourJars`: "jar check passed: 6 jars, 214 classes". Test XMLs (`*/build/test-results/test/*.xml`, includes blanket-emit): 427 tests, 0 skipped, 0 failures, 0 errors (ApiTourEmitTest ran, not skipped).
2. Test-weakening scan: `git diff caef564 HEAD -- '*src/test*'` and `git -C blanket-emit diff 35329b7 HEAD -- '*test*'` for removed asserts, `@Disabled`, `assumeTrue`, deleted test files.
3. `bash gradlew :blanket-bindings:lookup --ref "<ref>" --no-daemon -q` for 25 references (list below); `:blanket-bindings:remapAdapters`; `javap` on `blanket-bindings/build/stubs/1.8.9-yarn-legacy.jar`, `26.1.2-official.jar` and the remapped `adapters/legacy/com/blanket/adapters/legacy/Boxes.class`.
4. Boundary grep of `blanket-core/src/main` and `blanket-emit/src/main` for `net/minecraft|com/mojang|class_\d+|method_\d+|field_\d+|m_\d+_|f_\d+_`; `options.release` in api/runtime/bindings build files; SPDX header on every file added since the M1 verification (both repos).
5. `git diff caef564 HEAD -- docs/decisions.md docs/research-log.md` (additions only?).
6. `bash gradlew :blanket-api:apiCompat --no-daemon` -> "Comparing ... blanket-api-0.0.0.jar against ... baseline/blanket-api-0.2.0.jar / No changes." BUILD SUCCESSFUL.
7. `ls S:/blanket-wt/M2-gate-final/` (orchestrator's final gate run, container 143, `commits.txt` = "public d016941, blanket-emit 804891c").

### Check 3: re-derived references (lookup output, all match the type files)
| Reference (type file) | Re-derived |
|---|---|
| lf `class_864#field_3252:D` (Player.x) | lf 1.8.9 `class_864.field_3252`, forge 1.8.9 `pk.s D` |
| lf `class_1354#field_5202` (Direction) | forge 1.8.9 `cq.c Lcq;` |
| lf `class_231#field_585:D` (Box) | forge 1.8.9 `aug.d D` |
| lf `class_236#field_607:D` (Vec3) | forge 1.8.9 `aui.c D` |
| lf `class_1167#method_10572()I` (BlockPos) | forge 1.8.9 `df.n ()I` |
| lf `class_1061#method_10787()Lclass_236;` (Player.position, interface row) | forge 1.8.9 `m.d ()Laui;` |
| lf `class_1158#method_8580(Lclass_2552;)Lclass_2232;` (World.blockState) | forge 1.8.9 `adq.p (Lcj;)Lalz;` |
| lf `class_1150#method_4678()J` (World.gameTime) | forge 1.8.9 `adm.K ()J` |
| lf `class_327#method_6619()Z` / `method_841()Z` (KeyBinding) | forge 1.8.9 `avb.d` / `avb.f` |
| official `Entity#getYRot()F` | fabric 26.1.2 resolves |
| official `Inventory#getSelectedSlot()I` | fabric 26.1.2 resolves |
| official `Player#getFoodData()` | fabric 26.1.2 resolves |
| mojang `LivingEntity#getHealth()F` | int `class_1309.method_6032`, srg `m_21223_` |
| mojang `BlockGetter#getBlockState` | int `class_1922.method_8320`, srg `m_8055_` |
| mojang `Vec3i#getZ()I` | int `class_2382.method_10260`, srg `m_123343_` |
| mojang `Minecraft#level` | int `class_310.field_1687`, srg `f_91073_` |
| mojang `KeyMapping#consumeClick()Z` / `isDown()Z` | int `class_304.method_1436`/`method_1434`, srg `m_90859_`/`m_90857_` |
| mojang `Inventory#selected:I` | int `class_1661.field_7545`, srg `f_35977_` |
| mojang `FoodData#getFoodLevel()I` | int `class_1702.method_7586`, srg `m_38702_` |
| mojang `AABB#minX:D` / `maxZ:D` | int `class_238.field_1323`/`field_1324`, srg `f_82288_`/`f_82293_` |
| mojang `Entity#position()` | int `class_1297.method_19538`, srg `m_20182_` |
| mojang `Level#getGameTime()J` | int `class_1937.method_8510`, srg `m_46467_` |
| mojang `Minecraft#getWindow()` | int `class_310.method_22683`, srg `m_91268_` |
| adapter legacy `Boxes.inflate` -> `Box.expand(DDD)` | javap stub jar: `public Box expand(double,double,double)`; remapped class calls `class_231.method_587:(DDD)` and reads `class_236.field_605..607`, `class_231.field_582..587` |
| adapter unobf `Chat.showMessage` -> `ChatComponent.addClientSystemMessage(Component)` | javap 26.1.2 stub jar: present |

Mojang references also resolve on neoforge:1.21.1 to the same names (mojang runtime). No mismatch; every name has lookup evidence. Note: `lookup` with a bare `Class#member` (no descriptor) exits 1 with only Gradle's failure text; with the full descriptor from the type file it resolves (see minor finding 4).

### Checks 2, 4, 5, 6 summary
- T1-T8: code and named tests exist and ran. Read samples: RemapTest builds a major-53 class and asserts `ErrorCode.VERSION` with class, version and target in the message (RemapTest.java:195-207); E1PortTest runs lowered classes in a forked `java -Xverify:all` and asserts exit 0 (E1PortTest.java:189-195); ApiTourEmitTest asserts off-hand constant and call absent on both 1.8.9 jars and present on the four others (ApiTourEmitTest.java:82-90). Assertions, not smoke runs.
- Test changes: only two `-assert` lines in public tests, both replaced by stricter/updated ones (ScaffoldTest now asserts the `AssertionError("blanket stub")` body per D-061; the M0 "one instruction" assertion went with the M0 lowering). blanket-emit deleted `LowererTest` together with `Lowerer`/`Relocator` (replaced by core LOWER and EmitLowerTest, D-061/INTG). One new `assumeTrue` (ApiTourEmitTest, only when run outside Gradle); it did not skip here. No `@Disabled`.
- Boundaries: no Minecraft names in blanket-core/blanket-emit main sources; matches are loader packages only (`net/minecraftforge` in the Forge emitters and in Urls/DownloadedTargetData, as in M1). blanket-api and blanket-runtime `options.release.set(8)`; adapters compiled `--release 8` (compileAdapters). Emitter code only in `blanket-emit/`.
- Scope: nothing from M3+ built (KeyBinding.register queues only; registration recipe deferred to M3 per D-061). All T1-T8 deliverables present. `docs/progress/M2-gate/` (screenshots, logs, 1.8.9 javap output required by prompts/m2.md Gate) does not exist yet; it depends on the pending gate.
- Docs: decisions.md and research-log.md diffs since `caef564` are additions only (D-061, D-062, D-063; R-EXP-12..22); no existing entry edited. Every surprise line in M2.md cites an R-EXP entry except the ScaffoldTask build bug (stated, acceptable). New decisions are marked "Provisional (orchestrator)" rather than "Provisional (worker)" (minor finding 3).

## Findings
1. [minor, FIXED] New files without SPDX header — evidence: originally `BindingsKeysTest.java:1`, `BindingsWorldTest.java:1`, `blanket-emit/m0-spike/tools/check_apitour.py:1`. BindingsKeysTest fixed in `36df7c5` (f909061), check_apitour.py in emit `44d0833`; `blanket-core/src/test/java/com/blanket/core/bindings/BindingsWorldTest.java:1` now reads `// SPDX-License-Identifier: GPL-3.0-only` in the main checkout (orchestrator docs step, uncommitted when read). Closed once committed.
2. [minor, CLOSED by documentation] C10 is not meaningful for M2 — evidence: `apiCompat` prints "No changes." against `blanket-api/baseline/blanket-api-0.2.0.jar`, which was committed in T1 (4ab1df2) and is the same API (`git log caef564..HEAD -- blanket-api/src` shows only 4ab1df2), so the comparison is the T1 jar against itself (no release exists, D-060/D-061). Resolution: docs/progress/M2.md:94 records that C10 is trivially green in M2 and that the first real comparison is against the 0.2.0 jar in M3.
3. [minor, CLOSED by documentation] D-061, D-062, D-063 are marked "**Provisional (orchestrator)**", verify.md check 6 expects "**Provisional (worker)**" — evidence: `docs/decisions.md` added lines. Resolution: docs/progress/M2.md:95 lists it as a deviation with the same meaning (the owner confirms them). Still for the owner to accept the wording.
4. [minor, FIXED in 36df7c5] `lookup` with a member reference without descriptor exited 1 with only Gradle's failure text — evidence then: S:/sandbox/m2v-lookup.txt lines 161-173. Re-check on testbox from main `36df7c5`: `gradlew :blanket-bindings:lookup --ref 'lf:net/minecraft/class_1158#method_8580'` prints the table row `* FAIL at query: member reference needs a descriptor (name(args)ret or name:type) in the lf namespace: ...` and `lookup: member reference needs a descriptor ...`, exit 1; LookupMainTest gained a case (f909061). Closed.
5. [minor, FIXED] docs/progress/M2.md was "Status: in progress", the Gate section only "Running", and `docs/progress/M2-gate/` was missing — now: M2.md:3 "ready for verification (gate passed ...)", M2.md:45-61 has machine, commands, the six-target table, the off-hand javap count, the M0 regression and the earlier failing runs; `docs/progress/M2-gate/` holds 35 files (README.md, per target `-check.txt`, `.log`, `.png`, `-f3.png`, `-m0.png`, `javap-summary.txt`, javap of both 1.8.9 jars and of fabric:1.21.1). Research-log R-EXP-23 (1.21.1 saturation) and R-EXP-24 (era toString/empty-stack names) cover the gate-run surprises. Uncommitted when read; closed once committed.

No blocker or major findings; no open findings.

## Gate reproduction
Source: `S:/blanket-wt/M2-gate-final/` (orchestrator run, container 143, `commits.txt` = public `d016941`, blanket-emit `804891c`; `jars.sha256` present, 12 lines). I read the raw files and every screenshot myself; `SUMMARY.md` was only a pointer. Container `build.log`: validateBindings "0 errors, 0 warnings", "jar check passed: 6 jars, 214 classes" and "6 jars, 154 classes", BUILD SUCCESSFUL.

| Target | api-tour checker (`<dir>/check-*.txt`, last line) | F3 shot (`<dir>/shot-f3.png`) | M0 regression (`<dir>/m0-shot.png`, `m0-run.txt`) |
|---|---|---|---|
| legacy-fabric:1.8.9 | 26/26 keys pass -> PASS | XYZ 0.500 / 4.00000 / 0.500, Facing north (-180.0 / 90.0), minecraft:furnace facing north, 7 sticks, no off-hand slot | magenta HUD rectangle + green box; "box anchored at 1091.0 4.0 1699.0" |
| forge:1.8.9 | 26/26 keys pass -> PASS | same values as legacy-fabric, Forge 11.15.1.2318 | rectangle + box; anchored 1091.0 4.0 1699.0 |
| fabric:1.21.1 | 26/26 keys pass -> PASS | XYZ 0.500 / -60.00000 / 0.500, north (-180.0 / 90.0), Targeted Block 0,-61,0 minecraft:furnace facing north, 7 sticks, 3 apples off-hand | rectangle + box; anchored -10.0 -60.0 0.0 |
| neoforge:1.21.1 | 26/26 keys pass -> PASS | same as fabric:1.21.1 | rectangle + box; anchored 1.0 -60.0 0.0 |
| forge:1.20.1 | 26/26 keys pass -> PASS | same as fabric:1.21.1 | rectangle + box; anchored 7.0 -60.0 0.0 |
| fabric:26.1.2 | 26/26 keys pass -> PASS | XYZ 0.500 / -60.00000 / 0.500, north (-180.0 / 90.0), 7 sticks, 3 apples (26.1.2 F3 has no targeted-block panel) | rectangle + box; anchored 7.0 -60.0 2.0 |

- No title screen or blocking warning screen in any shot (D-050). The "Minecraft Crash Report" line in `forge-1.8.9/m0-run.txt` is Forge's SplashProgress spec print ("This is just a prompt for computer specs ... THIS IS NOT A ERROR"); the forge-1.20.1 match is the EARLYDISPLAY driver hint. Neither is a crash.
- Off-hand branch (`javap-*.txt`, `javap-summary.txt`): `"[apitour] offhand="` constant 0 times in both 1.8.9 jars, once in each of the four others; the off-hand call is present in the four others (`class_1309.method_6079` on fabric:1.21.1, `LivingEntity.getOffhandItem` on 26.1.2) and absent in both 1.8.9 jars, whose only off-hand text is the `"[apitour] offhand.skip=true"` constant of the else-branch. Agrees with ApiTourEmitTest in my clean build.
- Provenance note: the two 1.8.9 M0 regression shots come from a rerun in fresh container 145 on public `36df7c5` / emit `44d0833` (`m0-commits.txt`), because the run in 143 reused the scene's world. The diff `d016941..36df7c5` touches only Lookup/LookupMain and two tests, and `804891c..44d0833` only a comment line in check_apitour.py, so the emitted M0 jars are not affected.
- Builder comparison: matches the orchestrator's SUMMARY.md on every target; the earlier runs' failures (GlueGui NoClassDefFoundError, tile.air.name, lowercase direction, 1.21.1 saturation) do not recur.
