# Contributing and versioning

How anyone with access to the repository adds to Blanket, the rules every change follows, and the
versioning system that keeps old mods building. Decisions: D-045 (contribution path), D-046
(versioning).

## 1. Versioning

### 1.1 Numbers

`MAJOR.MINOR.PATCH`, written `v1`, `v1.1`, `v1.1.2`.

| Part | Means | Example |
|---|---|---|
| MAJOR (`v1` → `v2`) | something that worked in the old major may stop compiling, stop generating, or behave differently | renaming `Player.x()`, changing what `ChatMessage.text()` returns, a binding rewrite that changes a value, a new `blanket-libs.json` schema |
| MINOR (`v1.1` → `v1.2`) | additions only; every mod that built with `v1.0` builds with `v1.2` and behaves the same | a new member, type, event, packet type, widget, `Feature`, Minecraft version |
| PATCH (`v1.2.0` → `v1.2.1`) | fixes that make Blanket do what the docs already promise | a binding that returned the wrong value on one target, a crash, a wrong metadata field |

0.x releases (0.1–0.9, D-035) make no compatibility promise. The promise starts at 1.0.

### 1.2 What "compatible" means inside one major

A mod written against `v1.N` must, with any later `v1.M`:
1. **Compile** unchanged: no public API type, member, constant or signature is removed, renamed, or
   changed. New overloads must not make an existing call ambiguous.
2. **Generate** unchanged: a `mod.jar` compiled against an older `v1.N` API jar is accepted by a newer
   generator (binary compatibility of `blanket-api`).
3. **Behave** the same on every target it supported: same documented values, same event timing, same
   nullability. A difference is allowed only when the old behaviour contradicted the docs (a PATCH fix).
4. **Build** with the same Gradle block, `blanket-libs.json` and `blanket.lock` format. New options are
   allowed only with defaults that keep old behaviour.
5. **Keep its targets**: no target supported in `v1.N` is dropped in `v1.M` (D-034).

Not covered: Blanket internals (`blanket-core`, `blanket-runtime` internals, adapters, binding file
layout), which may change in any release, because no mod sees them.

### 1.3 Mappings and bindings

Bindings are how the API meets Minecraft, so their changes are classified by what a mod would see:

| Binding change | Release |
|---|---|
| a rule for a new Minecraft version, a new member's rules, a new adapter | MINOR |
| a fix that makes a member return what its javadoc says on a target where it did not | PATCH |
| a rewrite that changes values, timing or nullability a mod could already observe (for example moving `WORLD_RENDER` to a different point on all eras, or changing `ItemStack` empty handling) | MAJOR |
| a better route or mapping source that produces the same runtime names (same output jar) | PATCH or MINOR |

The golden lowering output (L1, [testing-strategy.md](testing-strategy.md) §2) is the referee: if a
change alters the lowered bytecode of the sample mods on an existing target, the pull request must say
why, and it is a PATCH (documented fix) or goes to the next major.

### 1.4 Branches and majors

- `main` is the current major (`v1.x`). Only MINOR and PATCH changes merge here.
- `next` collects breaking changes for the next major (`v2`). It is rebased on `main` regularly.
- When `v2.0` ships, `main` becomes `v2.x` and `release/v1` keeps `v1`. The previous major gets new
  Minecraft versions and PATCH fixes for 6 months after the next major ships, then it is frozen
  (built mods keep working; settled decision 7 means they never depend on Blanket at runtime anyway).
- Each major has its own API artifact (`io.github.e-the-bee:blanket-api:1.x`, `:2.x`); a mod moves
  majors by changing one version number and fixing what the migration notes list.

### 1.5 Enforcement in CI

| Check | Fails when |
|---|---|
| C10 API compatibility | `blanket-api` on `main` is not source- and binary-compatible with the last `v1.x` release (japicmp, Apache-2.0, run as a Gradle task) |
| L1 golden lowering | lowered bytecode of a sample on an existing target changed without the `behaviour-change` label |
| C9 matrix | a target present in the last release is missing |
| Schema check | a data file the author writes (`blanket-libs.json`, the Gradle DSL) lost or changed a key |

A pull request labelled `breaking` cannot merge into `main`; it targets `next`.

## 2. Tools for contributors

All tasks run in Blanket's repository. Era stub jars and game data are downloaded on first use and
cached ([build-and-gradle.md](build-and-gradle.md) §6); nothing is committed or published.

| Task | Does |
|---|---|
| `./gradlew lookup --ref "Entity#getX()D"` | resolves a Mojang-named reference on every supported target and prints a table: runtime name per target, or the step where it failed with candidates (members of the same owner with the same descriptor, or the same name with a different descriptor) |
| `./gradlew scaffold --kind member --type Player --sig "float armor()"` | adds the API stub method with a javadoc template, a rule list in `types/Player.json` pre-filled where `lookup` finds the anchor name on every era, and `TODO` rules elsewhere; `--kind type`, `event`, `packet`, `feature`, `target` do the same for their files |
| `./gradlew validateBindings` | checks C1–C10 for every target; each failure ends with a **next step** line (for example "no rule for forge:1.12.2 — try `lf:` id `method_3179`, see `lookup --ref ...`") |
| `./gradlew memberSweep -Ptier=1` | builds a generated test mod that calls every API member once per target and runs it in the testkit; fails on any exception or on a value outside the member's documented range |
| `./gradlew stubJars`, `compileAdapters` | as in [build-and-gradle.md](build-and-gradle.md) §6 |

## 3. Recipes

Every recipe ends with the definition of done (§4). Typical time assumes names resolve; adapters for
shape changes take longer.

### 3.1 A member on an existing type (≈15 min with `scaffold`)

1. `./gradlew scaffold --kind member --type <Type> --sig "<signature>"`.
2. Fill the `TODO` rules: a direct lowering where `lookup` shows the member, an adapter where the
   shape differs ([binding-format.md](binding-format.md) §4.3), `requires` + a `Feature` where the
   game lacks it on some versions.
3. Add a row to [api-surface.md](api-surface.md) with the use case.

### 3.2 A new type

1. Decide alias or owned with the rule of [api-surface.md](api-surface.md) §1 rule 4.
2. `scaffold --kind type --name <Type> --alias "<Mojang class>"` (or `--owned`).
3. Members as in §3.1.

### 3.3 An event

1. Choose a recipe kind that exists: `mixin`, `native`, `pipeline`, tick suppression, `coremod`
   ([events-and-hooks.md](events-and-hooks.md)). A new recipe *kind* is generator work and needs a
   decision entry; a new event using existing kinds is not.
2. `scaffold --kind event --name <EVENT>`; add hooks to `hooks.json` per era, using Fabric API's
   mixins and the loaders' native events as references.
3. Add the dispatcher method in `blanket-runtime` (one `fire...` method).

### 3.4 A Feature

`scaffold --kind feature --name <NAME>` adds the enum constant and a `features.json` row; set it true
or false per era. Members that need it get `"requires"`.

### 3.5 A packet type

1. `scaffold --kind packet --name <View> --direction in|out|both`.
2. Fill the `packet.classes` block per era ([binding-format.md](binding-format.md) §4.6) and the view's
   members like any type (alias if one class carries it everywhere, owned with adapters otherwise).
3. Add a constant to `PacketTypes`. Extend `samples/packets` with a probe.

### 3.6 A Minecraft version

1. `./gradlew updateTargets`, then `validateBindings`.
2. Fix every failure the hints point at (usually `official:` renames, R-29).
3. Tier-1 L4 if the version is an era representative, tier-2 launch smoke otherwise.

## 4. Rules for every pull request

1. **Through a pull request**, never a direct push to `main` or `next`. One approving review; changes
   to public API names or to this document need the project owner's review.
2. **Labelled** `addition`, `fix`, `breaking`, `internal` or `docs`. The label decides the release
   part (§1.1); `breaking` targets `next`.
3. **Green checks**: L1; L2 (C1–C10) on every target; L3 when `blanket-runtime` changed; tier-1 L4 when
   bindings, runtime or emitters changed (forks: after merge, D-035); `memberSweep` on tier 1 for new
   members.
4. **No Minecraft names in `blanket-core` or `blanket-emit`.** Minecraft knowledge goes in bindings
   and adapters only ([architecture.md](architecture.md) §6).
5. **Adapters and runtime stay Java 8** (`--release 8`), no new runtime dependency.
6. **A new dependency, recipe kind, runtime namespace or data-file schema change needs a decision
   entry** in [decisions.md](decisions.md).
7. **Docs updated in the same pull request**: the API table row, the binding file, and the support
   report wording if user-visible.
8. **Signed off** (`git commit -s`, Developer Certificate of Origin). Contributions are licensed
   GPL-3.0-only with the project's additional terms (D-038); the sign-off records who wrote what.
9. **Use case stated** in the description for any API addition (D-045).

### Definition of done

- `validateBindings` green on every supported target.
- `memberSweep` green on tier 1 for every new member.
- Golden lowering output unchanged on existing targets, or the change explained and labelled.
- C10 green on `main`.
- The CHANGELOG line written under the next MINOR/PATCH (or `next` for breaking).
