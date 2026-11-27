# Binding format

Bindings are the data that tell the generator how each API member becomes Minecraft code on each
target. They live in the `blanket-bindings` module, ship inside the `blanket-core` distribution as
plain files, and are read only at generate time. A mod author never writes bindings (the per-target
escape hatch is an overlay jar, [build-and-gradle.md](build-and-gradle.md) §2.1, D-036).

Every symbol name below that is marked *verified* was checked in the real mapping file or game jar
for that version on 2026-10-28 ([research-log.md](research-log.md) R-MAP-08 to R-MAP-14).

## 1. Files

```
blanket-bindings/
  targets.json              targets: loaders, eras, versions, mapping sources, runtime namespaces (§3)
  features.json             Feature availability per target (§6)
  types/<ApiType>.json      representation + member bindings of one API type (§4)
  glue/<GlueType>.json      the same, for runtime-internal glue types the GL backend uses (§4.5)
  hooks.json                named injection points inside Minecraft (§7)
  events/<Event>.json       event lowering recipes (§7, detail in events-and-hooks.md)
  loader-artifacts.json     Forge/NeoForge artifact version per loader target, for native-event checks (§7, D-058)
  adapters/<set>/
    adapter-set.json        stub version, namespace, range (§5)
    src/...                 adapter Java sources
```

All files are JSON (UTF-8, no comments; free text goes in `"note"` fields). Every file has
`"schema": 1`. Unknown keys are an error, so typos fail loudly. The one exception is
`loader-artifacts.json` (§7), a flat object from target id to artifact version with no `schema` key.

## 2. Shared grammar

### 2.1 Target selector `when`

```json
{ "loader": "fabric" | ["fabric", "legacy-fabric"], "mc": "<version-match>" }
```

- `loader` ∈ `fabric`, `legacy-fabric`, `neoforge`, `forge` ([loader-emitters.md](loader-emitters.md)).
  Omitted means any loader.
- `<version-match>`: an exact release id (`"1.21.1"`), an inclusive range (`"1.16.5..1.21.11"`), an
  open range (`"1.21.9.."`, `"..1.12.2"`), or a regex between slashes (`"/^1\\.20\\..*/"`). Omitted
  means any version. Versions compare numerically by dotted segments, so `26.1 > 1.21.11`
  (v1 `Version`, [reuse-from-v1.md](reuse-from-v1.md)). Only release ids are valid targets.
- All given keys must match. `blanket-libs.json` uses exactly this grammar
  ([libraries.md](libraries.md) §2), parsed by the same code.

### 2.2 Rule lists: first match wins

Wherever data varies by target it is a list of rules, each with a `when`. The generator takes the
**first** rule whose `when` matches the target; order rules from most specific to least. For every
target in the supported set (§3), the bindings validator ([testing-strategy.md](testing-strategy.md)
check C1) requires that every list has a matching rule and warns about rules that no supported target
reaches (dead rules).

### 2.3 Symbol references

A symbol reference names one Minecraft class, field or method in one namespace:

```
ref      := [ns ":"] class ["#" member]
ns       := "mojang" ["@" version]    Mojang ProGuard names of that version (default version: the anchor)
          | "intermediary"            Fabric intermediary (class_N / method_N / field_N), 1.14 .. 1.21.11
          | "lf"                      Legacy Fabric intermediary, 1.3 .. 1.13.2 (separate id space)
          | "official"                names in the unobfuscated 26.x jars
          | "loader"                  loader and library classes (never obfuscated; used literally)
class    := dotted name for mojang/official/loader (net.minecraft.client.Minecraft)
          | slash name for intermediary/lf (net/minecraft/class_310)
member   := name "(" args ")" ret     method, JVM descriptor
          | name ":" type             field, JVM descriptor
```

Class names inside descriptors use slashes and the same namespace as the reference:
`mojang:net.minecraft.client.Minecraft#setScreen(Lnet/minecraft/client/gui/screens/Screen;)V`.
No namespace prefix means `mojang@<anchor>`. The anchor is `"anchor"` in `targets.json` (`1.21.1`); a
type file may set its own `"anchor"`.

Members are always referenced on their **declaring** class (`Entity#getX`, not `LocalPlayer#getX`),
because mapping files list members only there. The generator emits the call against the declaring
class, which the JVM accepts for any subclass receiver.

### 2.4 How a reference resolves on a target

Resolution turns any reference into the target's **runtime name** (the name that must appear in the
output jar). It never loads a whole mapping set into a model: each mapping file is streamed once
and only rows for wanted symbols are kept ([mappings-and-versions.md](mappings-and-versions.md) §4).

| From | Route to the target's obfuscated (or official) name |
|---|---|
| `mojang@A` | A's ProGuard file: Mojang name → A-obf; A's intermediary: A-obf → intermediary id; continue as `intermediary` |
| `intermediary` | target 1.14 .. 1.21.11: target intermediary file: id → target-obf. Target 26.x: id → 1.21.11-obf → 1.21.11 Mojang name, then require that name with a matching descriptor in the 26.x jar. Target ≤ 1.13.2: unresolvable |
| `lf` | target ≤ 1.13.2: target LF intermediary file: id → target-obf. Other targets: unresolvable |
| `srg` | SRG ids are never written in bindings; they are only a runtime namespace (below) |
| `official` | target 26.x: used as is. Other targets: unresolvable |
| `loader` | owner and member name used as is on every target; Minecraft classes inside the descriptor are written in Mojang anchor names and resolved like any class reference ([pseudocode.md](pseudocode.md) P-09). On a target in the Legacy Fabric range (1.8.9 has no Mojang names to route through) a Minecraft class is written as its `lf` id, `net/minecraft/class_388`, and resolved like an `lf` class (M3: `GuiOpenEvent#gui` on Forge 1.8.9) |

Then target-obf → runtime name by the target's runtime namespace ([loader-emitters.md](loader-emitters.md)):

| Runtime namespace | Last step |
|---|---|
| `intermediary` (Fabric 1.14–1.21.11) | target intermediary file: obf → id |
| `lf` (Legacy Fabric) | target LF file: obf → id |
| `mojang` (NeoForge and Forge on Mojang-named runtimes) | target ProGuard file: obf → Mojang name |
| `official` (26.x on every loader) | identity |
| `obf` (Forge 1.8.9–1.12.2) | identity: FML remaps notch references in mod classes to SRG when they load ([loader-emitters.md](loader-emitters.md) §5) |
| `srg` (Forge 1.13.2–1.16.5) | target MCPConfig `joined.tsrg`: obf class → SRG class name, obf member → `func_N_x` / `field_N_x` (D-033) |
| `srgmoj` (Forge 1.17–1.20.4, NeoForge 1.20.1) | class: target ProGuard file, obf → Mojang name; member: target `joined.tsrg`, obf → `m_N_` / `f_N_` (D-033) |

Forge 1.13.2 has no Fabric intermediary and no Mojang mappings; its bindings use the `lf:` rules
(the LF range ends at 1.13.2) and the last step goes through `joined.tsrg`.

Last, every resolved class and member is looked up in the target's actual client jar (class index,
[lowering-and-adapters.md](lowering-and-adapters.md) §6). A mapping hit that is missing from the jar
is an `UNRESOLVED` error, never a silent pass.

### 2.5 Errors

| Code | Meaning | Message names |
|---|---|---|
| `UNBOUND` | no rule matches the target | API member, target |
| `UNRESOLVED` | a rule matches but a reference does not resolve or is absent from the jar | API member, reference, namespace step that failed, target |
| `SHAPE` | a direct lowering's stack effect differs from the API member's (§4.3) | both descriptors |
| `UNSUPPORTED` | a feature-gated member is reachable on a target without the feature (§6) | member, feature, target, the calling mod method |
| `ORDER` | an aliased enum's constants are in a different order than the API's | enum, target |
| `VERSION` | a class's major version is above the target's Java baseline (lowering §7; D-061) | class, major version, target's baseline |
| `COLLISION` | two mod methods have the same name and descriptor after remapping (lowering §4.8; D-061) | both methods, target |
| `FORBIDDEN` | a pattern the generator rejects: pattern switch over API types, non-literal `Feature` in `supports` (lowering §3; D-061) | the construct, target |

Every error carries the target and is reported for all targets at once (the generator does not stop
at the first target).

## 3. `targets.json`

```json
{
  "schema": 1,
  "anchor": "1.21.1",
  "gamePackages": ["net/minecraft/", "com/mojang/"],
  "obfuscatedClass": "[a-z][a-z0-9]{0,3}(\\$.*)?",
  "loaders": {
    "fabric": [
      { "when": { "mc": "1.14..1.21.11" }, "runtime": "intermediary", "emitter": "fabric" },
      { "when": { "mc": "26.1.." },        "runtime": "official",     "emitter": "fabric" }
    ],
    "legacy-fabric": [
      { "when": { "mc": "1.8.9..1.13.2" }, "runtime": "lf",           "emitter": "fabric" }
    ],
    "neoforge": [
      { "when": { "mc": "1.20.1" },          "runtime": "srgmoj",     "emitter": "neoforge" },
      { "when": { "mc": "1.20.2..1.21.11" }, "runtime": "mojang",     "emitter": "neoforge" },
      { "when": { "mc": "26.1.." },          "runtime": "official",   "emitter": "neoforge" }
    ],
    "forge": [
      { "when": { "mc": "1.8.9..1.12.2" },  "runtime": "obf",         "emitter": "forge-legacy" },
      { "when": { "mc": "1.13.2..1.16.5" }, "runtime": "srg",        "emitter": "forge-modern" },
      { "when": { "mc": "1.17..1.20.4" },   "runtime": "srgmoj",     "emitter": "forge-modern" },
      { "when": { "mc": "1.20.6..1.21.11" }, "runtime": "mojang",     "emitter": "forge-modern" },
      { "when": { "mc": "26.1.." },          "runtime": "official",   "emitter": "forge-modern" }
    ]
  },
  "versions": {
    "1.8.9":   { "java": 8,  "mappings": { "lf": "net.legacyfabric:intermediary:1.8.9:v2" }, "adapters": "legacy" },
    "1.12.2":  { "java": 8,  "mappings": { "lf": "net.legacyfabric:intermediary:1.12.2:v2" }, "adapters": "legacy" },
    "1.13.2":  { "java": 8,  "mappings": { "lf": "net.legacyfabric:intermediary:1.13.2:v2", "srg": "de.oceanlabs.mcp:mcp_config:1.13.2" }, "adapters": "legacy13" },
    "1.16.5":  { "java": 8,  "mappings": { "mojang": true, "intermediary": "net.fabricmc:intermediary:1.16.5:v2", "srg": "de.oceanlabs.mcp:mcp_config:1.16.5" }, "adapters": "modern" },
    "1.20.1":  { "java": 17, "mappings": { "mojang": true, "intermediary": "net.fabricmc:intermediary:1.20.1:v2", "srg": "de.oceanlabs.mcp:mcp_config:1.20.1" }, "adapters": "modern" },
    "1.21.1":  { "java": 21, "mappings": { "mojang": true, "intermediary": "net.fabricmc:intermediary:1.21.1:v2" }, "adapters": "modern" },
    "1.21.11": { "java": 21, "mappings": { "mojang": true, "intermediary": "net.fabricmc:intermediary:1.21.11:v2" }, "adapters": "modern" },
    "26.1.2":  { "java": 25, "mappings": { "official": true }, "adapters": "unobf" }
  },
  "supported": [ "legacy-fabric:1.8.9", "forge:1.8.9", "legacy-fabric:1.9.4", "…", "fabric:26.3", "forge:26.3", "neoforge:26.3" ]
}
```

- `gamePackages` and `obfuscatedClass` (both required, D-059) say which class names are the game's own: a name that
  starts with one of the `gamePackages` prefixes (each ends with `/`), or that matches the `obfuscatedClass` regex
  in full (inner-class suffix included). A game class with no row in a mapping file is `UNRESOLVED`; only other
  classes (JOML, LWJGL, the JDK) pass through as library classes (§2.4).
- `loaders.<id>` is a rule list: first match gives the runtime namespace and emitter for a version.
  A version with no matching era for a loader is not a valid target for that loader.
- `versions.<id>.java` must equal `javaVersion.majorVersion` in Mojang's version JSON; the core
  test suite checks it ([research-log.md](research-log.md) R-MAP-02).
- `mappings.mojang: true` means "download `client_mappings` named in the version JSON"; it is only
  valid where Mojang publishes one (1.14.4–1.21.11, R-MAP-03).
- `adapters` names the adapter set compiled for the version (§5).
- `supported` is generated (§3.1) and is the matrix Blanket tests and promises ([mappings-and-versions.md](mappings-and-versions.md)
  §7). A target outside it but with a valid era generates with the warning
  `target <t> is outside the tested matrix`.

This file shows a few `versions` rows; the real file has one row per release in the matrix. Mapping
entries gain `"srg": "de.oceanlabs.mcp:mcp_config:<mc>"` for SRG-era versions (D-033).

### 3.1 Generating the matrix

`supported` is not written by hand. The maintainer task `updateTargets` reads Fabric meta (game
versions with an intermediary and a stable loader), Legacy Fabric meta, the Forge maven
`maven-metadata.xml` and the NeoForge maven, keeps release ids only, intersects with the
eras in `loaders`, and applies the NeoForge filter `["1.20.1", "1.21.1", "1.21.11", "<newest 26.x>"]`
(D-034). The result is committed, so a Blanket release pins its matrix. `versions.<id>.java` comes from
Mojang's version JSON.

## 4. Type files `types/<ApiType>.json`

```json
{
  "schema": 1,
  "api": "com.blanket.api.Player",
  "representation": [ <representation rule>, ... ],
  "members": { "<member key>": [ <member rule>, ... ], ... },
  "note": "free text"
}
```

### 4.1 Representation rules

```json
{ "when": {...}, "alias": "<class ref>" }
{ "when": {...}, "owned": "com.blanket.runtime.owned.<Class>" }
```

`alias`: every occurrence of the API type in descriptors, signatures, casts, `instanceof`, arrays and
lambda bootstrap arguments is rewritten to the resolved class
([lowering-and-adapters.md](lowering-and-adapters.md) §4). `owned`: the API type stays a class shipped
in the embedded runtime (relocated); its members are ordinary methods and need no member rules.
Types marked owned in [api-surface.md](api-surface.md) §2 have no representation list; the API's
stub annotation `@Owned` says so.

### 4.2 Member keys

The key is the member name if it is not overloaded in the API type, else name plus JVM descriptor in
API names: `"x"`, `"offset(Lcom/blanket/api/Direction;)Lcom/blanket/api/BlockPos;"`. Fields do not
exist on API types (accessors only). Static factories are keyed like methods (`"of"`).

### 4.3 Member rules and lowering kinds

```json
{ "when": {...}, "requires": "<FEATURE>"?, "lower": <lowering>, "note": "..."? }
```

| Lowering | JSON | What the generator emits |
|---|---|---|
| invoke | `{ "invoke": "<method ref>" }` | `INVOKEVIRTUAL`/`INVOKESTATIC`/`INVOKEINTERFACE` chosen from the resolved method's access flags and owner kind |
| field read | `{ "get": "<field ref>" }` | `GETFIELD` or `GETSTATIC` |
| field write | `{ "put": "<field ref>" }` | `PUTFIELD` or `PUTSTATIC` |
| construct | `{ "new": "<method ref to <init>>" }` | a call to a synthesized private static bridge in the calling class that does `NEW; DUP; load args; INVOKESPECIAL; ARETURN` (API factories are static methods, so arguments are already on the stack when the call is reached) |
| chain | `{ "chain": [ <lowering>, ... ] }` | each step in order; the first step takes the API receiver and arguments (a direct lowering or a cast), every later step is an `invoke`, a `get` or a `cast` on the previous result, or a `const` or `newarray` step that pushes an argument. A later `invoke` takes exactly the `const`/`newarray` steps written right before it, in order and with matching types (boolean `Z`, integer `B`/`S`/`C`/`I`/`J`/`F`/`D`, fraction `F`/`D`, string `Ljava/lang/String;`, newarray `[<prim>`); with none before it, it takes no parameters. A pushed value no invoke consumes is an error. A later step cannot be a `put`, `adapter` or nested chain, and the first step cannot be a `const` or `newarray` (D-053, D-056). Example: `[{"cast": "...DeltaTracker"}, {"const": true}, {"invoke": "...#getGameTimeDeltaPartialTick(Z)F"}]` |
| cast (chain step only) | `{ "cast": "<class ref>" }` | `CHECKCAST` to the class: a checked change of the static type of the value on top of the stack, not a conversion. Only valid as a step of a `chain`, never as a top-level lowering (D-053) |
| new array (chain step only) | `{ "newarray": "<Z\|B\|C\|S\|I\|J\|F\|D>", "length": <n> }` | `BIPUSH n; NEWARRAY`: pushes a new primitive array as the argument of the next `invoke` step, for example `Matrix4f#get([F)[F` with `{"newarray": "F", "length": 16}`. Never a top-level lowering (D-056) |
| adapter | `{ "adapter": "<set>:<class>#<method>" }` | `INVOKESTATIC` to the adapter method; its descriptor is the API member's descriptor with the receiver (if any) as the first parameter, after type remapping |
| constant | `{ "const": true \| false \| <int> \| <string> }` | `ICONST`/`LDC` (used by `Blanket.supports`); as a later chain step, an argument of the next `invoke` (D-056) |
| unsupported | `{ "unsupported": "<reason>" }` | nothing; any reachable use is an `UNSUPPORTED` error (§6) |

**Shape check for direct kinds** (`invoke`, `get`, `put`, `new`, `chain`): after type remapping, the
API member's stack effect (receiver, then arguments, then result) must equal the lowered code's, with
two allowances: the receiver may be a subclass of the target owner, and the result may be a subclass
of the remapped API result. No primitive widening, no boxing. Anything else must be an `adapter`;
otherwise the build fails with `SHAPE`.

### 4.4 Feature requirement

`"requires": "OFFHAND"` marks a rule as only usable where the feature is available (§6). A member
with a `requires` rule gets an implicit `unsupported` lowering on targets without the feature.

### 4.6 Packet types

A type file for a packet view adds a `packet` block naming the packet classes it matches, per era:

```json
"packet": {
  "direction": "in",
  "classes": [
    { "when": { "mc": "..1.12.2" }, "match": ["lf:net/minecraft/class_xxxx"] },
    { "when": { "mc": "1.19.." },  "match": ["net.minecraft.network.protocol.game.ClientboundSystemChatPacket",
                                             "net.minecraft.network.protocol.game.ClientboundPlayerChatPacket"] }
  ]
}
```

`match` is a list because one view may cover several classes (`ChatMessage` on 1.19+); such a view
must be `owned`, with adapters that read each class. The generated `PacketClassifier` tests the classes
of used packet types only.

### 4.5 Glue types

`glue/*.json` has the same format for types in `com.blanket.runtime.glue` (for example
`GlueFrame#projectionMatrix([F)V`). The runtime's own code calls glue stubs; the generator lowers
those calls exactly like mod calls. This keeps all Minecraft knowledge in bindings, including the
render backend's ([rendering-api.md](rendering-api.md) §4).

Shape check for glue members (C3): an `Object` parameter of the glue stub accepts any reference-type parameter
of the lowering, receiver included; the generator emits a `checkcast` per argument. So
`GlueWorld.viewProjection(Object, Object)` lowers to the direct invoke
`Camera#getViewRotationProjectionMatrix(Matrix4f)Matrix4f` on 26.x (D-057).

A glue member may also bind a whole-rule `{ "adapter": "<set>:com.blanket.adapters.<Class>#<method>" }` (M3): the
glue body loads the stub's parameters in order and calls the adapter with the stub's own descriptor, so an `Object`
parameter stays `Object` and the adapter casts or tests it itself (`instanceof` needs this, a chain has no such step).
The adapter closure and checks C4 and C6 treat the call like an API member's adapter. An adapter in a chain is still an
API-member-only form. The glue stub, and with it the adapter's class and everything the class references, ships in
every jar that reaches the stub's class, so keep such adapters free of references that exist on only part of a set.

## 5. Adapter sets `adapters/<set>/adapter-set.json`

```json
{
  "schema": 1,
  "name": "modern",
  "stubs": "1.21.1",
  "authoring": "mojang",
  "shipping": "intermediary",
  "covers": { "mc": "1.16.5..1.21.11" }
}
```

- `stubs`: the version whose era stub jar the sources compile against
  ([lowering-and-adapters.md](lowering-and-adapters.md) §5.1).
- `authoring`: names used in the Java sources (`mojang` for 1.14.4+, Legacy Yarn names via
  `yarn-legacy` for ≤1.13.2 sets, `official` for 26.x).
- `shipping`: namespace the compiled classes are remapped to when `blanket-bindings` is built
  (`intermediary`, `lf` or `official`). Shipped adapter bytecode is resolved per target exactly like
  mod bytecode (§2.4), so one compiled class serves every target its references resolve on.
- `covers`: the versions CI resolves this set against. A reference that fails on a covered version
  fails the Blanket build, not a user's build.

Sets for 1.0 (D-034): `legacy` (stubs 1.8.9, authoring `yarn-legacy`, shipping `lf`, covers
1.8.9..1.12.2), `legacy13` (stubs 1.13.2, authoring `yarn-legacy`, shipping `lf`, covers 1.13..1.13.2:
the Flattening and GLFW make 1.13 its own era), `modern` (stubs 1.21.1, covers 1.14..1.21.11; classes for
older sub-eras carry `@Stubs`), `unobf` (stubs 26.1.2, shipping `official`, covers 26.1..). Shipping
namespaces are not runtime namespaces: an `lf` adapter shipped for `forge:1.13.2` or an `intermediary`
adapter shipped for `forge:1.16.5` is remapped at generate time through the same routes as bindings
(§2.4) to `srg`/`srgmoj` names. A version can only use one set, named in `targets.json`.

Adapter reference syntax in rules: `"modern:com.blanket.adapters.ItemStacks#count"`. When rules for
different eras point at different sets, each rule names its own set.

## 6. `features.json`

```json
{
  "schema": 1,
  "features": {
    "OFFHAND": {
      "available": [ { "when": { "mc": "1.9.." } } ],
      "note": "off-hand slot added in 1.9"
    }
  }
}
```

- `Blanket.supports(Feature.X)` is bound in `types/Blanket.json` to `{"const": true}` or
  `{"const": false}` per target, generated from this file.
- After constant folding the generator removes unreachable code
  ([lowering-and-adapters.md](lowering-and-adapters.md) §4.7) and then checks reachability: a use of a
  member whose rule is `unsupported` (explicitly or through `requires`) in reachable code is an
  `UNSUPPORTED` error.
- `@Requires(Feature.X)` on a mod method or class: on targets without X, the body of each annotated
  method is replaced by `throw new IllegalStateException("<modid>: <method> needs feature X, absent on <target>")`
  and its references are not checked. The shrink pass then removes it if nothing reachable calls it.

## 7. Hooks and events (schema)

`hooks.json` names injection points once so several events can share them:

```json
{
  "schema": 1,
  "hooks": {
    "<hook id>": [
      { "when": {...}, "method": "<method ref>", "at": <at>, "mutable": ["<field ref>", ...]?, "note": "..." }
    ]
  }
}
```

`<at>` is `"HEAD"`, `"TAIL"` (before the last return), `"RETURN"` (before every return),
`{ "invoke": "<method ref>", "shift": "BEFORE" | "AFTER", "ordinal": <n>? }`, or
`{ "invokeString": "<method ref taking one String>", "ldc": "<string>" }` (Mixin `INVOKE_STRING`, the call
whose string argument is that constant; needed for the 1.8.9 world hook at `Profiler#swap("hand")`,
R-EVT-09, R-EXP-06; D-051). Every hook becomes a
Mixin injector with `require = 1` and `expect = 1`
([events-and-hooks.md](events-and-hooks.md) §3). Raw bytecode offsets are not expressible.

`mutable` (optional, non-empty) lists fields of the hooked class, written in the namespace of `method`, that code run
by the hook assigns although the jar declares them `final`. The generated Mixin class shadows each with `@Shadow @Final
@Mutable`, which makes Mixin remove `final` from the target field (sponge-mixin `MixinApplicatorStandard`, the
`isDecoratedMutable` branch). The key hand-over needs it on Fabric, where `Options#keyMappings` is `public final`
(M3 KEYS):

```json
"keys.options": [
  { "when": { "loader": "fabric", "mc": "1.21.1" }, "method": "net.minecraft.client.Options#load()V", "at": "HEAD",
    "mutable": [ "net.minecraft.client.Options#keyMappings:[Lnet/minecraft/client/KeyMapping;" ] } ]
```

`events/<Event>.json`:

```json
{
  "schema": 1,
  "event": "HUD_RENDER",
  "usedBy": "<API method>"?,
  "recipes": [
    { "when": {...}, "mixin":  { "hook": "<hook id>", "dispatch": "<Dispatch method>", "before": [<glue source>, ...]?, "payload": { "<name>": <source>, ... } } },
    { "when": {...}, "mixin":  [ { "hook": ..., "dispatch": ..., ... }, { "hook": ..., "dispatch": ... } ] },
    { "when": {...}, "native": { "event": "<loader ref>", "bus": "game" | "mod" | "fml", "dispatch": "<Dispatch method>",
                                 "filter": { "<getter or field ref>": "<static field ref>", ... }?, "before": [...]?, "payload": {...} } },
    { "when": {...}, "native": [ { "event": ..., "bus": ..., "dispatch": ..., ... }, { "event": ..., ... } ] },
    { "when": {...}, "via":    "<EVENT>" }
  ]
}
```

A recipe has exactly one of `mixin`, `native` and `via`. Every `mixin` injection and every `native` body has a required
`dispatch` key: the name of the
`com.blanket.runtime.Dispatch` method it calls, for example `tickEnd`, `hud`, `hudRecord`, `hudReplay`, `world`,
`worldLegacy` (D-057). The method's parameter count equals the number of payload entries. `mixin` is one injection
object or an array of them; each injection is its own Mixin injector with its own hook, dispatch, `before` and
payload (26.x HUD: `gui.record` calls `hudRecord`, `gui.replay` calls `hudReplay`). `native` is likewise one body or an
array of them; each body is its own listener method, for an event that needs two loader events on one target (a screen
opening and a screen closing, the second passing `{"const": null}`):

```json
{ "when": { "loader": "forge" }, "native": [
    { "event": "loader:<opening event>", "bus": "game", "dispatch": "screenOpen", "payload": { "screen": { "getter": "loader:<opening event>#<getter>" } } },
    { "event": "loader:<closing event>", "bus": "game", "dispatch": "screenOpen", "payload": { "screen": { "const": null } } } ] }
```

`via` names another event (its constant, for example `CLIENT_TICK_START`) when the runtime synthesizes this event from
that event's hook, so the event has no hook of its own: `WORLD_JOIN` and `WORLD_LEAVE` are fired by `Dispatch.tickStart`
(D-015). The generator wires the named event's recipe for the target, and wires each recipe once, so a mod using
`CLIENT_TICK_START`, `WORLD_JOIN` and `WORLD_LEAVE` gets one tick-start hook. C1 accepts a `via` recipe when the named event
has a `mixin` or `native` recipe for the target; a named event without an events file or a recipe for the target, a
named event whose recipe there is itself `via` (a chain or a cycle) and an event naming itself are errors:

```json
{ "schema": 1, "event": "WORLD_JOIN", "recipes": [ { "when": {}, "via": "CLIENT_TICK_START" } ] }
```

An event is used, and its recipe wired, only where the mod or an embedded class other than `com.blanket.runtime.Dispatch`
reads its `Events` constant ([events-and-hooks.md](events-and-hooks.md) §1 principle 5): `Dispatch` reads every event to
fire it, which is no subscription.

`usedBy` (optional) makes an events file a **hand-over** instead of an API event: `event` is then a name of its own (not
an `Events` constant), and its recipe is wired where the mod calls the named API method (`com.blanket.api.<Type>#<method>`,
any descriptor). The one hand-over is `KEY_HANDOVER`, used by `com.blanket.api.KeyBinding#register`: it hands the
mappings queued during `init()` to the game ([events-and-hooks.md](events-and-hooks.md) §4.7). A hand-over's recipes are
`mixin` or `native`, never `via`:

```json
{ "schema": 1, "event": "KEY_HANDOVER", "usedBy": "com.blanket.api.KeyBinding#register", "recipes": [
  { "when": { "loader": "fabric" }, "mixin": { "hook": "keys.options", "dispatch": "keysOptions", "payload": { "options": { "arg": 0 } } } },
  { "when": { "loader": "neoforge", "mc": "1.21.1" }, "native": { "event": "loader:net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent",
    "bus": "mod", "dispatch": "keysRegister", "payload": { "event": { "arg": 1 } } } } ] }
```

`bus` names where a native body listens: `game` (the loader's game event bus), `mod` (the mod's own event bus:
NeoForge's constructor `IEventBus`, Forge's `FMLJavaModLoadingContext` bus; legacy Forge has none), or `fml`: a
lifecycle event the generated entry class receives itself. On legacy Forge the `fml` event is
`FMLInitializationEvent`, and the body runs in the entry's init method right after `Boot.init`, with the event in the
same slot as in a listener.

`filter` (native only, D-057) maps a getter or field reference on the event object to a static field reference,
for example `{"loader:...TickEvent#phase:L.../TickEvent$Phase;": "loader:...TickEvent$Phase#END:L.../TickEvent$Phase;"}`.
The generated listener reads each key and returns early unless the value is identical (`==`) to the static field;
entries are ANDed. A key is a field (`Owner#name:T`) or a method without parameters (`Owner#name()T`); a value is a
field. Other forms are an error.

`loader-artifacts.json` (D-058) lists, per Forge or NeoForge target, the loader artifact whose universal jar the
validator indexes to check native event classes and their getter, field and filter references:
`{"forge:1.8.9": "1.8.9-11.15.1.2318-1.8.9", "forge:1.20.1": "1.20.1-47.4.28", "neoforge:1.21.1": "21.1.265"}`
(Forge: `net.minecraftforge:forge:<v>:universal`; NeoForge: `net.neoforged:neoforge:<v>:universal`). A supported
target whose binding uses a `loader:` reference and has no entry is `UNRESOLVED` at step `loader-jar` (D-059).

Payload sources: `{"arg": n}` (in a Mixin, the n-th argument of the hooked method from 1, and `0` for the hooked object
itself, `this`; in a native body, `1` is the event, the listener's one argument), `{"this": "<field ref>"}`,
`{"getter": "<method or field ref>"}` (on the native event object: a method without parameters is called, a field
`Owner#name:T` is read with GETFIELD, D-057), `{"adapter": "..."}`,
`{"const": v}` (`v` may be `null` for a reference parameter, D-056), and `{"glue": "<GlueType>#<member>", "args": [<source>, ...]}` (D-054). A glue source calls a
glue member with the value of each `args` source, in order (`args` may be omitted for a member with no
parameters). Each argument is any payload source, including another glue source. The member is bound per
target like any glue member (section 4.5), and its lowering consumes the arguments in order. Its result is the
value passed to `Dispatch`, so it has the type of the `Dispatch` parameter it fills (for example `float`,
`float[]` or `double`). It is not `Object`, because `Dispatch` is typed in M1 (D-055) and the `Object...`
form of D-051 is not used; its parameters may stay `Object` (D-056). A glue member with a `void` result cannot
be a payload source, because a payload value must exist.

`before` (D-056) lists glue sources the hook runs, in order, before it computes the payload and calls `Dispatch`.
Each names a `void` glue member with its descriptor, for example
`{"glue": "GlueGui#flush(Ljava/lang/Object;)V", "args": [{"arg": 1}]}` (`GuiGraphics#flush()` before immediate HUD
drawing, M0 on fabric 1.21.1, neoforge 1.21.1, forge 1.20.1). An entry without a `)V` descriptor is an error.

Each payload entry fills one parameter of the `Dispatch` method for the event, in the order the entries are
written. `Dispatch` keeps its typed M0 signatures for M1 (D-055).

**Payload values reach the runtime as `Object` (D-051).** The generated hook passes each payload value
to `Dispatch.<event>(Object...)` unconverted. Conversions are glue members that take the raw object and
are bound per target like all glue (§4.5), for example `GlueFrame.partialTick(Object)` (`DeltaTracker`
→ `float` on 1.21+, the `float` argument boxed on older eras), `GlueWorld.matrix(Object, float[])`
(`Matrix4f#get([F)`), `GlueGui.flush(Object)` (`GuiGraphics#flush()` before immediate HUD drawing, run
through `before`). D-055 keeps `Dispatch` typed in M1, so glue results have the `Dispatch` parameter's type.
This keeps hook bodies free of Minecraft names: a hook only loads values and calls `Dispatch`. M0 wrote
these conversions inline in each hook (R-EXP-06); M1 moves them into glue. The full set of v1 recipes and their verified facts is in
[events-and-hooks.md](events-and-hooks.md) §4.

## 8. Worked examples

Ids and names in these examples are *verified* unless marked otherwise
([research-log.md](research-log.md) R-MAP-08..14).

### Example 1 — a stable method on every target: `Client.get()`

```json
"get": [
  { "when": { "mc": "..1.13.2" }, "lower": { "invoke": "lf:net/minecraft/class_1600#method_2965()Lnet/minecraft/class_1600;" } },
  { "when": {},                   "lower": { "invoke": "net.minecraft.client.Minecraft#getInstance()Lnet/minecraft/client/Minecraft;" } }
]
```

Resolution of the second rule (anchor 1.21.1): Mojang `Minecraft` = obf `fgo`, `getInstance` = obf `Q`;
intermediary `class_310` / `method_1551`.

| Target | Runtime class / method in the output jar |
|---|---|
| `fabric:1.21.1` | `net/minecraft/class_310` / `method_1551` |
| `neoforge:1.21.1` | `net/minecraft/client/Minecraft` / `getInstance` |
| `fabric:26.1.2` | via 1.21.11 Mojang name `getInstance` (obf `V` there), present in the 26.1 jar → `net/minecraft/client/Minecraft` / `getInstance` |
| `legacy-fabric:1.8.9` | first rule: `net/minecraft/class_1600` / `method_2965` |
| `forge:1.8.9` | first rule, obf column of LF 1.8.9: `ave` / `A` |

### Example 2 — a rename that needs no override: `Player.onGround()`

```json
"onGround": [
  { "when": { "mc": "..1.13.2" }, "lower": { "get": "lf:net/minecraft/class_864#field_3197:Z" } },
  { "when": { "mc": "..1.15.2" }, "lower": { "get": "mojang@1.14.4:net.minecraft.world.entity.Entity#onGround:Z" } },
  { "when": {},                   "lower": { "invoke": "net.minecraft.world.entity.Entity#onGround()Z" } }
]
```

The method is `isOnGround()` in 1.16.5 and `onGround()` from 1.20.1. Both are intermediary
`method_24828`, so the anchor-named rule resolves on 1.16.5 to `isOnGround` for Mojang-named runtimes
and to `method_24828` for Fabric. The binding author writes nothing for the rename. (The second rule
covers 1.14.4..1.15.2 where only the field exists; 1.15.2 has both, and the rule order picks the field.
Whether `onGround()` exists in 1.16 to 1.19 under some name is settled by resolution: if a supported
target lacked it, the build of `blanket-bindings` would fail with `UNRESOLVED`.)

### Example 3 — field to method: `Player.x()`

```json
"x": [
  { "when": { "mc": "..1.13.2" }, "lower": { "get": "lf:net/minecraft/class_864#field_3252:D" } },
  { "when": { "mc": "..1.14.4" }, "lower": { "get": "mojang@1.14.4:net.minecraft.world.entity.Entity#x:D" } },
  { "when": {},                   "lower": { "invoke": "net.minecraft.world.entity.Entity#getX()D" } }
]
```

1.14.4 has the public field `x` and no `getX()`; 1.15.2 adds `getX()` (`method_23317`, unchanged
through 1.21.11 and present in 26.1). The LF id `field_3252` is the same in 1.8.9, 1.12.2 and 1.13.2
(obf `s`, `p`, `q`), so one rule covers the whole legacy range. Lowering a call to a field read has the
same stack effect (receiver in, `double` out), so the shape check passes. Inside a lambda's method
handle the same field read becomes a bridge method ([lowering-and-adapters.md](lowering-and-adapters.md)
§4.3).

### Example 4 — absent symbol, guarded by a feature: `Player.offHandItem()`

```json
"offHandItem": [
  { "when": {}, "requires": "OFFHAND", "lower": { "invoke": "net.minecraft.world.entity.LivingEntity#getOffhandItem()Lnet/minecraft/world/item/ItemStack;" } }
]
```

On `legacy-fabric:1.8.9` and `forge:1.8.9` the feature is absent, so the member is unsupported. This
mod code builds on every target:

```java
if (Blanket.supports(Feature.OFFHAND)) {
    drawItem(player.offHandItem());
}
```

On 1.8.9 `supports` folds to `false`, the branch is removed, and nothing references `offHandItem`.
Without the guard, the 1.8.9 targets fail:

```
error UNSUPPORTED [legacy-fabric:1.8.9, forge:1.8.9]
  com.example.hud.HandsHud.render(Lcom/blanket/api/HudRender;)V calls Player.offHandItem()
  which needs Feature.OFFHAND (available on mc 1.9..). Guard it with Blanket.supports(Feature.OFFHAND)
  or annotate the method @Requires(Feature.OFFHAND).
```

### Example 5 — argument shape change: `KeyBinding.register(String, Key, String)`

`KeyMapping`'s constructor takes the category as a `String` up to 1.21.8 and as a
`KeyMapping$Category` from 1.21.9 (also 1.21.11 and 26.1). The key code is a GLFW code (1.13 to
26.2) or an LWJGL 2 code (≤1.12.2) and must be translated from `Key`. Registration also differs per
loader ([events-and-hooks.md](events-and-hooks.md) §4.7). So the member always goes through an adapter:

```json
"register": [
  { "when": { "mc": "..1.12.2" },         "lower": { "adapter": "legacy:com.blanket.adapters.Keys#register" } },
  { "when": { "mc": "1.14.4..1.21.8" },   "lower": { "adapter": "modern:com.blanket.adapters.Keys#register" } },
  { "when": { "mc": "1.21.9..1.21.11" },  "lower": { "adapter": "modern:com.blanket.adapters.KeysCategory#register" } },
  { "when": { "mc": "26.1.." },           "lower": { "adapter": "unobf:com.blanket.adapters.Keys#register" } }
]
```

The `modern` set's two classes compile against 1.21.1 stubs; `KeysCategory` references
`KeyMapping$Category`, which does not exist in 1.21.1, so it is compiled against a second stub
version. That is the one rule for this case: an adapter class whose references only resolve on part
of its set's range declares its own `"stubs"` in a class-level annotation
`@Stubs("1.21.11")` and CI resolves it only on the `when` ranges that point at it
([lowering-and-adapters.md](lowering-and-adapters.md) §5.2).

### Example 6 — return shape change: `Client.crosshairTarget()`

`Target` is owned. Minecraft has `HitResult` with `BlockHitResult`/`EntityHitResult` subclasses from
1.13, and a single `MovingObjectPosition` with a type enum before. The field `Minecraft.hitResult`
exists from 1.14.4 through 26.1 (verified). Both eras use adapters that build a `Target`:

```json
"crosshairTarget": [
  { "when": { "mc": "..1.12.2" }, "lower": { "adapter": "legacy:com.blanket.adapters.Targets#crosshair" } },
  { "when": { "mc": "..1.21.11" }, "lower": { "adapter": "modern:com.blanket.adapters.Targets#crosshair" } },
  { "when": {},                   "lower": { "adapter": "unobf:com.blanket.adapters.Targets#crosshair" } }
]
```

```java
// adapters/modern/src/com/blanket/adapters/Targets.java  (compiled against 1.21.1 Mojang-named stubs)
public static Target crosshair(Minecraft mc) {
    HitResult hit = mc.hitResult;
    if (mc.level == null || hit == null) return null;
    Vec3 loc = hit.getLocation();
    switch (hit.getType()) {
        case BLOCK: {
            BlockHitResult b = (BlockHitResult) hit;
            return Target.block(b.getBlockPos(), b.getDirection(), loc);
        }
        case ENTITY: return Target.entity(loc);
        default:     return Target.miss(loc);
    }
}
```

`Target.block(BlockPos, Direction, Vec3)` is an owned-runtime factory whose parameter types are the
aliased API types, which here are the real Minecraft classes, so no conversion is needed.

### Example 7 — chain: `Player.foodLevel()`

```json
"foodLevel": [
  { "when": { "mc": "..1.13.2" }, "lower": { "chain": [
      { "invoke": "lf:net/minecraft/class_1016#method_3188()Lnet/minecraft/class_1001;" },
      { "invoke": "lf:net/minecraft/class_1001#method_3238()I" } ] } },
  { "when": {}, "lower": { "chain": [
      { "invoke": "net.minecraft.world.entity.player.Player#getFoodData()Lnet/minecraft/world/food/FoodData;" },
      { "invoke": "net.minecraft.world.food.FoodData#getFoodLevel()I" } ] } }
]
```

Legacy Yarn names for the LF ids: `PlayerEntity.getHungerManager()` (`method_3188`) returning
`HungerManager` (`class_1001`), `getFoodLevel()` (`method_3238`). The owner class id `class_1016` for
`PlayerEntity` is *ASSUMED* here (the method id is verified); the bindings build resolves it against
the jar and fails if wrong ([risks.md](risks.md) R-02 covers LF identity in general).

### Example 8 — normalization adapter: `Player.mainHandItem()` on ≤1.10.2

```json
"mainHandItem": [
  { "when": { "mc": "..1.10.2" }, "lower": { "adapter": "legacy:com.blanket.adapters.Stacks#mainHand" } },
  { "when": {}, "lower": { "invoke": "net.minecraft.world.entity.LivingEntity#getMainHandItem()Lnet/minecraft/world/item/ItemStack;" } }
]
```

```java
// legacy set (Legacy Yarn names): never return null for an ItemStack
public static ItemStack mainHand(ClientPlayerEntity p) {
    return Stacks.nonNull(p.getMainHandStack());
}
static ItemStack nonNull(ItemStack s) { return s == null ? EMPTY : s; }
static final ItemStack EMPTY = new ItemStack((Item) null, 0, 0);   // per-jar sentinel
```

Every `ItemStack` member on ≤1.10.2 is likewise an adapter that treats `null` and `EMPTY` as empty
(`isEmpty`, `count`, `itemId` → `"minecraft:air"`, `displayName` → `"Air"` from the translation of
`tile.air.name`). Constructing `ItemStack` with a `null` item on 1.8.9 is *ASSUMED* safe as long as the
sentinel never reaches Minecraft code; the adapters guarantee that by converting `EMPTY` back to
`null` at every boundary that passes a stack into Minecraft ([risks.md](risks.md) R-12).

### Example 9 — era split by data model: `BlockState.propertyValue(String)`

```json
"propertyValue": [
  { "when": { "mc": "..1.12.2" }, "lower": { "adapter": "legacy:com.blanket.adapters.States#propertyValue" } },
  { "when": { "mc": "..1.21.11" }, "lower": { "adapter": "modern:com.blanket.adapters.States#propertyValue" } },
  { "when": {}, "lower": { "adapter": "unobf:com.blanket.adapters.States#propertyValue" } }
]
```

1.8.9 exposes `BlockState.getPropertyMap()` (Legacy Yarn, `method_9026`, an `ImmutableMap`); modern
versions expose `getValues()` and `Property#getName(Comparable)`. The adapters iterate, compare the
property name, and return the value's serialized name, so the API result is the same string on every
target. Note in the binding file: on 1.8.9 some blocks keep part of their state in metadata that is
not a property; `propertyValue` reports only properties.

### Example 10 — compile-time constant: `Blanket.supports(Feature)`

`types/Blanket.json` is generated from `features.json`, one rule list per feature constant:

```json
"supports(OFFHAND)": [
  { "when": { "mc": "1.9.." }, "lower": { "const": true } },
  { "when": {},                "lower": { "const": false } }
]
```

The generator recognizes `GETSTATIC Feature.OFFHAND; INVOKESTATIC Blanket.supports` and replaces the
pair with the constant. A `Feature` value that is not a constant at the call (stored in a field,
passed through a parameter) is a build error: `supports` must be called with a literal constant so it
can fold.

### Example 11 — class rename after 1.21.11: the HUD hook on 26.x

The HUD hook point is `Gui.render(GuiGraphics, DeltaTracker)` through 1.21.11. In the 26.1 jar there
is no `render`; the method is `extractRenderState(GuiGraphicsExtractor, DeltaTracker)` (verified with
`javap`). Resolution through the 1.21.11 bridge (§2.4) would look for `render` and fail, so the hook
has an explicit `official` rule:

```json
"gui.render": [
  { "when": { "mc": "26.1.." }, "method": "official:net.minecraft.client.gui.Gui#extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", "at": "TAIL" },
  { "when": { "mc": "1.21..1.21.11" }, "method": "net.minecraft.client.gui.Gui#render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", "at": "TAIL" },
  { "when": { "mc": "1.20.1..1.20.6" }, "method": "mojang@1.20.1:net.minecraft.client.gui.Gui#render(Lnet/minecraft/client/gui/GuiGraphics;F)V", "at": "TAIL" },
  { "when": { "mc": "..1.12.2" }, "method": "lf:net/minecraft/class_371#method_9420(F)V", "at": "TAIL" }
]
```

The name being findable is not enough on 26.x: `extractRenderState` records draw commands for later,
so issuing GL calls there would draw at the wrong time. The HUD recipe therefore does not use this
hook for drawing on 1.21.6+; see [events-and-hooks.md](events-and-hooks.md) §4.3. This example shows the
data form; the event document decides which hook each era uses.

### Example 12 — an aliased value type and its factory: `BlockPos.of(int,int,int)`

```json
{
  "api": "com.blanket.api.BlockPos",
  "representation": [
    { "when": { "mc": "..1.13.2" }, "alias": "lf:net/minecraft/class_2552" },
    { "when": {},                   "alias": "net.minecraft.core.BlockPos" }
  ],
  "members": {
    "of": [
      { "when": { "mc": "..1.13.2" }, "lower": { "new": "lf:net/minecraft/class_2552#<init>(III)V" } },
      { "when": {},                   "lower": { "new": "net.minecraft.core.BlockPos#<init>(III)V" } }
    ]
  }
}
```

`class_2552` is Legacy Yarn's `net/minecraft/util/math/BlockPos` in 1.8.9 (verified). Every descriptor
in the mod that mentions `com/blanket/api/BlockPos` becomes `net/minecraft/class_2552` on
`legacy-fabric:1.8.9`, the class's obf name on `forge:1.8.9`, `net/minecraft/class_<id>` on Fabric, and
`net/minecraft/core/BlockPos` on NeoForge and 26.x.

## 9. Validation done when `blanket-bindings` is built

1. Every public API member has a member rule list or the type is owned (C1).
2. Every rule list matches every `supported` target; dead rules are warnings.
3. Every reference resolves on every supported target it applies to, and is present in that
   target's jar (C2, real-data, [testing-strategy.md](testing-strategy.md) §2).
4. Direct lowerings pass the shape check on every supported target (C3).
5. Every adapter set compiles and its references resolve on every version it covers (C4).
6. Aliased enums have the API's constant order (C5).

A user's `generate` re-runs checks 3 and 4 only for the members the mod uses, for the targets it
requests.
