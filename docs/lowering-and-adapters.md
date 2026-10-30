# Lowering and adapters

**Lowering** is the stage that rewrites the mod's compiled classes, per target, so they reference
real Minecraft classes and members instead of `blanket-api` stubs. **Adapters** are hand-written
Java helpers for the cases data alone cannot express. This document covers both, plus the class
index, frames, class versions, shrinking and relocation that make the result valid bytecode.

Terms are as in [binding-format.md](binding-format.md). The pipeline position of each pass is in
[architecture.md](architecture.md) §3.

## 1. Inputs and outputs

Per target, the lower stage receives:
- the mod's classes (compiled once against `blanket-api`);
- the **resolution**: for every used API member, the chosen rule with every reference resolved to
  runtime names (from the resolve stage);
- the embedded runtime and the adapter classes reachable from the resolution, in their shipping
  namespace;
- the **class index** for the target (§6).

It returns rewritten class files in runtime names, ready for the wire stage (events, entry class)
and the shrink stage.

## 2. Pass order for one class on one target

1. **Member lowering** (§4.1, §4.2): each instruction whose owner is an API type is replaced by its
   lowering, emitted directly in runtime names.
2. **Handle and bootstrap rewriting** (§4.3): `invokedynamic` bootstrap arguments that point at API
   members are redirected, synthesizing bridge methods where needed.
3. **Feature folding** (§4.7): `Blanket.supports(<constant>)` becomes `true`/`false`.
4. **Dead-code removal** (§4.7), then the reachability check for unsupported members.
5. **Type remapping** (§4.5): an ASM `ClassRemapper` maps API alias types to their runtime classes and
   the runtime/adapter packages to the mod's relocated packages. Descriptors, signatures, casts,
   `instanceof`, arrays, `ldc` class constants, annotations, local variable tables, `MethodType` and
   `Handle` constants are covered by `ClassRemapper` itself.
6. **Frame and max recomputation** with the class index (§6).
7. **Class-version check** (§7).

Passes 1–4 run on the ASM tree API (`ClassNode`), because folding and dead-code removal need the
whole method. Pass 5 is a visitor chain into the writer.

**Proof so far (E1, [research-log.md](research-log.md) R-EXP-01):** a throwaway ASM tool remapped
`api/Player` → `mc/LocalPlayer` and lowered `Player.x()` to either `INVOKEVIRTUAL Entity.getX()D` or
`GETFIELD Entity.posX D` in Java 8 classes containing a direct call, a lambda, a method reference, an
anonymous `ToDoubleFunction<Player>` (with javac's generic bridge method) and a `List<Player>` loop.
All variants ran under `-Xverify:all` on JDK 25 with the expected results. Pointing a lambda's method
handle at the field directly failed with `LambdaConversionException: Unsupported MethodHandle kind:
getField`; a synthesized static bridge fixed it. This is why §4.3 exists.

## 3. What mod code may and may not do

The API stub contract ([api-surface.md](api-surface.md) §1 rule 5) makes most misuse impossible to
compile. The generator rejects the rest with a build error that names class, method and line:

| Pattern | Verdict | Reason |
|---|---|---|
| Subclassing or implementing an alias type | cannot compile (types are `final`, constructors private) | An alias type disappears into a Minecraft class. |
| Calling an alias type's constructor | cannot compile | Only static factories are lowerable without stack reordering. |
| `Class.forName("com.blanket.api...")`, reflection on API types, `MethodHandles.lookup().findVirtual(Player.class, ...)` | build error when the string or class constant is visible | Reflection would see the runtime class with runtime (obfuscated) member names. |
| `Feature` value that is not a literal constant at `supports(...)` | build error | Folding needs a constant (binding example 10). |
| Pattern-matching `switch` over API types (Java 21 `SwitchBootstraps.typeSwitch` / `enumSwitch` with class or enum descriptors as strings) | build error with "use if/instanceof or a classic switch" | The bootstrap arguments encode class names as strings that remapping does not see. Classic enum `switch` is fine (§4.6). |
| `IFNULL`/`IFNONNULL` on a value typed `ItemStack` | warning | Empty stacks are never null in the API ([api-surface.md](api-surface.md) §3.6). |
| Storing API handles in static fields across worlds | allowed, documented as a mod bug | `Player` and `World` change on world change on every target; same as vanilla. |
| Serializing API objects (Java serialization) | unsupported, undocumented in the API | No API type is `Serializable`. |

## 4. Lowering rules

### 4.1 Member calls

For `INVOKEVIRTUAL`/`INVOKESTATIC` whose owner is an API alias type, the generator looks up the member
key ([binding-format.md](binding-format.md) §4.2) and emits the chosen lowering:

- `invoke`, `get`, `put`, `const`: one instruction (opcode chosen from the resolved member's access
  flags and whether the owner is an interface, so `IBlockState` on 1.8.9 gets `INVOKEINTERFACE` while
  `BlockState` gets `INVOKEVIRTUAL`).
- `chain`: the steps in order.
- `adapter`: `INVOKESTATIC <relocated adapter class>.<method>` with the API descriptor (receiver
  first). The adapter's own declared descriptor, after remapping, must equal it; the generator checks.
- `new`: a call to a bridge (§4.2).

The shape check ([binding-format.md](binding-format.md) §4.3) has already passed in the resolve stage,
so each replacement has the same stack effect as the stub call it replaces. Lowering never needs to
look at surrounding instructions.

### 4.2 Constructors and other non-local lowerings

API values are created through static factories (`BlockPos.of(x, y, z)`). When the factory lowers to
a Minecraft constructor, the arguments are already on the stack, but `NEW`/`DUP` must come before
them. Instead of reordering the caller, the generator synthesizes one private static bridge per
(class, factory) in the calling class:

```
private static synthetic net/minecraft/core/BlockPos blanket$new$BlockPos$of(III) {
    NEW net/minecraft/core/BlockPos; DUP; ILOAD 0; ILOAD 1; ILOAD 2;
    INVOKESPECIAL net/minecraft/core/BlockPos.<init>(III)V; ARETURN
}
```

and replaces the call with `INVOKESTATIC` to it. The JIT inlines it. The same bridge mechanism serves
§4.3.

### 4.3 Method handles in `invokedynamic`

`LambdaMetafactory` and `StringConcatFactory` bootstraps take `Handle` and `MethodType` constants.
`ClassRemapper` remaps the types inside them. A `Handle` that points at an API member (method
references such as `Player::x`) is redirected:

- if the lowering is a single `invoke` whose descriptor is the API descriptor after remapping (receiver
  included), the handle points at the target method directly (E1, `invoke` variant);
- in every other case (field read, chain, adapter with different arity, constant, `new`), the generator
  synthesizes a private static bridge in the class with the API descriptor (receiver first), whose body
  is the lowering, and points the handle at it as `H_INVOKESTATIC`. The bootstrap's
  `instantiatedMethodType` is unchanged; only the implementation method moves.

Lambdas the mod writes itself (`q -> q.x() + 1`) are ordinary private methods (`lambda$...`) and are
lowered like any method.

### 4.4 Nullness of `ItemStack` on ≤1.10.2

Lowering is purely per instruction, so the empty-stack rule ([api-surface.md](api-surface.md) §3.6) is
implemented entirely in adapters (binding example 8). The generator only adds the warning for
`IFNULL`/`IFNONNULL` on values it can type as `ItemStack` with ASM's `Analyzer` and a
`SimpleVerifier` over the class index. It never rewrites null checks.

### 4.5 Type remapping

The remapper's map is built from the resolution:
- every alias API type → its runtime class name;
- `com/blanket/runtime/**` → `<base>/blanket/runtime/**`;
- `com/blanket/adapters/<set>/**` → `<base>/blanket/adapters/**`;
- API owned types (`com/blanket/api/Screen`, `Target`, ...) → `<base>/blanket/api/**`, because their
  real classes ship in the jar.

`<base>` is the mod's base package ([build-and-gradle.md](build-and-gradle.md) §2, `mod.basePackage`).
No API type survives in the output; the emit stage verifies that no `com/blanket/` reference remains.

String constants are never remapped. Unlike Shadow's default (`skipStringConstants = false` rewrites
matching strings, R-PA-13), Blanket's relocation only touches class references, so mod log messages
and resource paths that happen to contain `com.blanket` stay intact.

### 4.6 Enums

An aliased API enum (`Direction`) is accessed through `GETSTATIC Direction.NORTH`, lowered to the
resolved Minecraft field. javac compiles a classic `switch` on an enum into a synthetic
`$SwitchMap$...` array filled from `values()` and `ordinal()`; both are remapped calls on the real enum.
`name()` returns the string literal passed to the enum constructor, which obfuscation does not change,
so `Direction.NORTH.name()` is `"NORTH"` on every target. The `ORDER` check
([binding-format.md](binding-format.md) §2.5) guarantees `ordinal()` agrees with the API.

### 4.7 Constant folding and dead-code removal

After `supports` calls become `ICONST_0`/`ICONST_1`, the generator:
1. replaces `ICONST_x; IFEQ/IFNE L` pairs with `GOTO L` or nothing;
2. runs ASM's `Analyzer` to find instructions with no frame (unreachable);
3. replaces unreachable instructions with `NOP ... ATHROW` (the standard ASM technique, which keeps
   offsets and exception tables valid) and drops exception table entries that cover only removed code;
4. scans reachable code for references to `unsupported` members and reports `UNSUPPORTED`.

Folding only recognizes the literal pattern `GETSTATIC Feature.X; INVOKESTATIC Blanket.supports`.
`if (!Blanket.supports(...))` and `&&`/`||` compile to the same branch instructions, so they fold
too. Storing the result in a local first (`boolean ok = Blanket.supports(X); if (ok) ...`) also works,
because the analyzer pass in step 2 runs after a simple constant propagation over locals that hold a
folded constant and are written once.

### 4.8 Overload and signature collisions

Two mod methods that differ only in API types can collide after remapping (for example if two API
types ever aliased to one Minecraft class). The generator checks every class for duplicate
name+descriptor after remapping and fails with both source signatures. No two first-release API types
alias to the same class on any target.

### 4.9 Inheritance

- Mod classes may extend owned abstract classes (`Screen`) and implement owned interfaces (`ClientMod`,
  `Listener`). Those types are relocated, not aliased, so inheritance is ordinary Java.
- An alias type is never a supertype of a mod class (§3), so no mod class inherits Minecraft
  members, and Minecraft's own method renames cannot accidentally make a mod method an override.
- A mod method whose name matches a Minecraft method name has no effect, since it is not in a
  Minecraft class hierarchy.

### 4.10 What is preserved

Line numbers, source file names, local variable names and generic signatures (remapped) are kept, so
stack traces from a generated jar point at the author's source lines.

## 5. Adapters

### 5.1 Era stub jars

An era stub jar is a signature-only copy of one Minecraft version's client jar (classes, fields and
method signatures, no code), in the adapter set's authoring names. It is built inside Blanket's own
repository, from the downloaded client jar and that version's mappings, by a Gradle task in
`blanket-bindings`. It is never published, never used by `generate`, and never leaves the Blanket
maintainer's machine or CI cache. This is the only place where Blanket processes every symbol of a
version, and it serves Blanket's own development, not a user's build; settled decision 3 is about the
generate pipeline ([decisions.md](decisions.md) D-006).

Authoring names: Mojang names for `modern` (1.14.4+), official names for `unobf` (26.x), and Legacy
Yarn names (CC0) for `legacy` (≤1.13.2), because LF intermediary ids are unreadable and Mojang names do
not exist for those versions.

### 5.2 Writing and compiling

Adapters are `public final` classes with `public static` methods, in `com.blanket.adapters`, under
`blanket-bindings/adapters/<set>/src`. They may reference:
- Minecraft classes from the stub jar;
- API types (`com.blanket.api.*`): alias types appear by their Minecraft class instead, since an
  adapter is written against the real classes; owned types (`Target`, `Key`) by their API name;
- `com.blanket.runtime.*` (event bus, GL backend glue);
- JDK classes available in Java 8.

Compilation: `javac --release 8` for every set (Java 8 is the lowest baseline any set covers; javac
reads newer stub class files regardless of `--release`). A class that needs symbols from a different
version than its set's `stubs` carries `@Stubs("<version>")` and is compiled in its own javac
invocation against that stub jar (binding example 5).

Then the compiled classes are remapped from authoring names to the set's shipping namespace
(`intermediary`, `lf` or `official`, [binding-format.md](binding-format.md) §5) with the full mapping
of the stub version, and packaged into `blanket-bindings.jar`. Because shipping names are
intermediary-style ids for obfuscated eras, Blanket's distributed artifacts contain Mojang names only
in binding JSON, never a mapping file (conditions in [mappings-and-versions.md](mappings-and-versions.md) §8).

### 5.3 The `Screen` host

`Screen` is owned and subclassed by the mod. Minecraft needs an instance of its own `Screen` class.
Each adapter set contains a class `ScreenHost extends net.minecraft.client.gui.screens.Screen` (or the
era's equivalent) that holds the mod's `com.blanket.api.Screen` and forwards `init`, `render`,
`mouseClicked`, `mouseReleased`, `mouseDragged`, `mouseScrolled`, `keyPressed`, `charTyped`, `onClose` and
`isPauseScreen`, translating key codes to `Key` and wrapping the frame in a `Canvas2D`.
`Client.openScreen(Screen)` lowers to an adapter that creates the host and calls `setScreen`. The
mod's class never extends a Minecraft class, so §4.9 holds.

Override signatures differ per era (for example `render(PoseStack, int, int, float)` on 1.16.5,
`render(GuiGraphics, int, int, float)` on 1.20.1 to 1.21.11; mouse and key methods take event records
on 26.x, S§7 "Input event types (new at 26.1)"). Each era's host overrides its era's signatures.
Overrides are checked like any reference: a host method that does not override a real method on a
covered version fails the Blanket build (check C6, [testing-strategy.md](testing-strategy.md) §2),
because a silently non-overriding method would never be called.

### 5.4 Selection, remapping and embedding at generate time

1. The resolution names adapter methods. The generator loads those classes from
   `blanket-bindings.jar` (shipping namespace) and computes their closure (adapter classes referenced
   by adapter classes, owned runtime classes).
2. Every Minecraft reference inside the closure is added to the target's used set and resolved like
   a mod reference ([binding-format.md](binding-format.md) §2.4). Adapters therefore obey settled
   decision 3 as well: only what they use is resolved.
3. The closure is remapped to runtime names, relocated under `<base>/blanket/adapters/`, and added to
   the output.

### 5.5 Generics, lambdas and frames inside adapters

Adapters are ordinary javac output and go through the same §6 frame computation after remapping.
They may use lambdas; their `Handle`s point at real Minecraft methods (already lowered at compile
time) and are remapped by `ClassRemapper`.

## 6. Class index and frames

The **class index** answers "does class/member X exist" and "what are the supertypes of X" for one
target without loading classes. It reads class files lazily by entry name, `SKIP_CODE`, from:
1. the target client jar;
2. the loader artifact for the target (Fabric Loader, NeoForge, Forge universal), for native event
   classes and entry-point interfaces;
3. the JDK's class library for the target's Java version (`ct.sym` for `--release N`, read through
   the running JDK's `jrt:` file system);
4. the mod jar, the embedded runtime and the adapter closure.

It caches only what it reads. Member lookup walks superclasses and interfaces, which is how a
binding's `Entity#getX` is verified for a `LocalPlayer` receiver.

Frames: every rewritten method gets `COMPUTE_FRAMES` with a `ClassWriter` whose `getCommonSuperClass`
walks the index (never `Class.forName`, which would need the game on the generator's classpath and
is the failure v1's agent avoided). Interfaces merge to `java/lang/Object`, as in ASM's default. E1
used a stub that always answered `Object`; that only passed because no merged value was used as a
specific type, which is why the real implementation consults the hierarchy.

## 7. Class-file versions

| Class group | Version written |
|---|---|
| Mod classes | unchanged (compiled once) |
| Generated classes (entry, mixins, bridges in new classes, registration glue) | 52 (Java 8) |
| Embedded runtime and adapters | 52 (compiled with `--release 8`) |

Every target accepts version 52. The check fails the build if any class in a target's output has a
major version above the target's Java baseline (`targets.json` `java`: 8 → 52, 16 → 60, 17 → 61,
21 → 65, 25 → 69). In practice this means the mod must be compiled with `--release` equal to the lowest
Java baseline among its requested targets; the Gradle plugin sets this automatically
([build-and-gradle.md](build-and-gradle.md) §2). Downgrading newer bytecode (JvmDowngrader, R-PA-11)
is not in v1: it is LGPL-2.1 and its API stubs would have to be shaded into every output, which needs a
license decision ([decisions.md](decisions.md) D-013).

## 8. Shrink and relocation

**Shrink** is class-level. Roots: the generated entry class, generated mixin classes, generated
event dispatch, and every mod class (mod classes are never removed; the author owns them). The
closure over class references of the embedded runtime and adapters is kept; every other runtime or
adapter class is dropped. Method-level shrinking is not done: the runtime is small and class-level
removal already drops whole subsystems such as the GL backend in a mod that never draws.

**Relocation** prefix: `<base>.blanket` (`runtime`, `adapters`, `api`, `generated` underneath).
Two Blanket-built mods with different base packages never share a class.

## 9. Hard cases

| Case | Handling |
|---|---|
| Lambda over an aliased type whose member lowers to a field | §4.3 bridge (E1) |
| Method reference to an API static factory | §4.3 bridge to the §4.2 `new` bridge |
| `instanceof Player` / `(Player) o` | `ClassRemapper` rewrites to the runtime class; correct because alias = one class |
| Arrays `Player[]`, varargs `Object...` holding API values | remapped like any descriptor |
| Generic signature `List<Player>` | `ClassRemapper` remaps `Signature` attributes |
| `Direction.valueOf("NORTH")` | remapped `valueOf` on the real enum; works because `name()` is unobfuscated (§4.6) |
| API type in an annotation value (`@Requires`) | `@Requires` is consumed and removed by the generator; no other API annotations exist |
| `Player.class` as a map key | remapped class constant; works as identity, documented as non-portable for reflection |
| Shape-changing API (item components 1.20.5) | Not reachable through the v1 API: `ItemStack` exposes id, count, name, emptiness, all of which survive 1.20.5. Any future member that reads item data is an adapter per era by construction. |
| Interface on one target, class on another (`IBlockState` vs `BlockState`) | opcode chosen per target from the class index (§4.1) |
| Mod compiled with `-parameters` or with `MethodParameters` | preserved, harmless |
| Java 21 record patterns over API values | records cannot be API types (alias types are final classes, not records); patterns over owned records do not exist in v1 |
