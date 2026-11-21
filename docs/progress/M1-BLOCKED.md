# M1 blocked: incomplete, not ready for verification

## What is blocking
T9 (bindings for the M0 subset) is partial. Three binding gaps stop the HUD and world hooks from being written. They are grammar questions that need the owner's decision, so I stopped rather than choose. T10, T11, T12 and the six-target gate are not started. T10's `validateBindings` cannot be green until the HUD and world bindings exist, and T12 needs them to generate M0.

## Done in this session (commits on the public root)
- 74aaec7 `M1 T9: cast chain step and glue payload source (D-053, D-054)`: `Lowering` accepts `{"cast": "<class>"}` as a chain step only; a later step must be a zero-argument invoke, a get or a cast. `EventBinding` accepts `{"glue": "GlueType#member", "args": [...]}` as a payload source. `docs/binding-format.md` sections 4.3 and 7 updated. Tests: GrammarExtensionsTest (15), all pass.
- d6466de `M1 T9: glue for GlueFrame and GlueWorld, hooks.json, ClientTick event`: `blanket-bindings/glue/GlueFrame.json`, `glue/GlueWorld.json`, `hooks.json`, `events/ClientTick.json`. Test BindingsGlueHooksTest (4) resolves every glue member and hook on the six targets against the real mapping data (`skipped="0"`), with M0 names as the expected results.
- `./gradlew build`: BUILD SUCCESSFUL at d6466de, all ten suites green.

## The three gaps (evidence in blanket-emit/m0-spike/gen/src/blanket/m0/Gen.java)
A. A chain step cannot take a constant argument. `DeltaTracker.getGameTimeDeltaPartialTick(true)` (Gen.java HudMixin and WorldMixin on fabric 1.21.1 and 26.1.2, and the neoforge 1.21.1 HUD) needs a `true` on the stack, and a later chain step is zero-argument (binding-format section 4.3). The parser rejects it with `chain step 1 must take no arguments`.
B. No array in the grammar. The world hook makes `float[16]` from `Matrix4f` (`new float[16]`, `Matrix4f.get(float[])`) on fabric 1.21.1, 26.1.2, neoforge 1.21.1 and forge 1.20.1. No lowering kind or payload source allocates an array.
C. No effect-only glue. `GuiGraphics.flush()` is void. Payload values must exist and `Dispatch.hud(float)` has no slot for a side effect, so `glue/GlueGui.json` cannot be written.

## Decision needed (options, none chosen)
- A: an `args` list on a later chain step with payload sources as entries (`{"const": true}` for the boolean); or `{"const": v}` as a chain step.
- B: an array lowering (for example `{"new": "[F", "length": 16}`) plus an array payload source; or matrix conversion through adapters (M2), so the world hook waits for M2.
- C: an effect-only payload entry that runs without filling a `Dispatch` parameter; or a hook-level action before the `Dispatch` call.
- If none is wanted in M1: the HUD and world hooks move to M2 with adapters, and M1 finishes with the tick and glue subset that is bound.

Also needs a decision: D-054 says glue returns `Object`. The implementation returns the type of the `Dispatch` parameter (D-055 keeps `Dispatch` typed). Recorded in `docs/progress/M1.md` under Deviations.

## What is needed to continue
1. The owner chooses an option for A, B and C (or moves the HUD and world hooks to M2).
2. Write `glue/GlueGui.json`, `events/HudRender.json`, `events/WorldRender.json` in the chosen form. BindingsGlueHooksTest then extends to them.
3. Then T10 (`lookup`, `scaffold`, `validateBindings`), T11 (`check`, `explain`), T12 (Gen port to the RESOLVE plan), and the gate.
4. `blanket-emit/m0-spike/build.sh` stays retired.
