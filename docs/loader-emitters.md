# Loader emitters

An **emitter** writes the loader-specific parts of one target's jar: the generated entry class,
metadata files, the Mixin config, native event registration and the jar layout. One emitter serves one
**loader-era** (a loader plus the Minecraft range where its runtime names and file formats are the
same). Every cell below is marked **V** (verified, with the research-log entry) or **A** (ASSUMED,
with the spike in [risks.md](risks.md)).

## 1. Loader-eras

| Id | Loader | Minecraft | Runtime names in the jar | In 1.0 | Source |
|---|---|---|---|---|---|
| `fabric-int` | Fabric Loader | 1.14 – 1.21.11 | Fabric intermediary (`class_N`, `method_N`, `field_N`) | yes (every release) | V R-LDR-10 (`MappingConfiguration` picks `intermediary` when mappings are present) |
| `fabric-off` | Fabric Loader | 26.1 + | official (= Mojang names) | yes (every release) | V R-LDR-10, R-MAP-07 (intermediary 26.x is an empty placeholder), R-PA-06 ("the game will use Mojang's names at runtime") |
| `legacy-fabric` | Fabric Loader (stock) + Legacy Fabric intermediary | 1.3 – 1.13.2 (1.0: every release 1.8.9 – 1.13.2) | LF intermediary (own numbering, collides in format with Fabric's) | yes (every release) | V R-LDR-11, R-MAP-06 |
| `neoforge-moj` | NeoForge | 1.20.2 – 1.21.11 | Mojang names | yes (1.21.1, 1.21.11) | V R-LDR-05 (20.2 "MojMaps everywhere", installer merges classes, fields and methods) |
| `neoforge-off` | NeoForge | 26.1 + | official | yes (newest 26.x) | V R-LDR-05 |
| `forge-legacy` | Forge (FML on LaunchWrapper) | 1.8.9 – 1.12.2 | **notch (obfuscated) names**; FML remaps them to SRG as each class loads | yes (every release; 1.9–1.11.2 **A**, S-18) | V R-LDR-01, R-LDR-02 (§5) |
| `forge-moj` | Forge | 1.20.6 – 1.21.11 | Mojang names | yes (1.0) | V R-LDR-07 (installer renames with MOJMAPS; MDK has no `reobfJar` from 1.20.6) |
| `forge-off` | Forge | 26.1 + | official | yes (1.0) | V R-LDR-07 for the namespace; event model **A** (EventBus 7, R-EVT-06) |
| `forge-srg` | Forge | 1.13.2 – 1.16.5 | SRG: class names and `func_N_x`/`field_N_x` members from MCPConfig `joined.tsrg` | yes (1.0) | V R-LDR-07, R-MAP-16 |
| `forge-srgmoj` | Forge | 1.17 – 1.20.4 | Mojang class names + SRG `m_N_`/`f_N_` members | yes (1.0) | V R-LDR-07 |
| `neoforge-srgmoj` | NeoForge | 1.20.1 | Mojang class names + SRG members | yes (1.0) | V R-LDR-05 |
| — | Forge | 1.20.5 | no Forge build exists | — | V R-LDR-14 |
| — | Quilt Loader | any | loads Fabric jars | covered by `fabric-*` jars | V R-LDR-12 |
| — | Ornithe | ≤ 1.14.4 | Calamus intermediary (incompatible with LF) | not a target in v1 | V R-LDR-12 |

**SRG eras (D-033).** A jar for `forge-srg`, `forge-srgmoj` and `neoforge-srgmoj` must contain SRG
member names, which exist only in MCPConfig's obf→SRG table (`joined.tsrg`). Blanket reads that one
table per version at build time (never MCP's human-name CSVs) and emits SRG names directly; nothing
remaps them at load on these eras (ModLauncher, unlike legacy FML). Legacy Forge needs no table (§5).

## 2. Per loader-era facts

### 2.1 `fabric-int`, `fabric-off`, `legacy-fabric` (one emitter: `fabric`)

| Item | Value | |
|---|---|---|
| Metadata | `fabric.mod.json` at the jar root | V R-LDR-10 |
| Fields written | `schemaVersion: 1`, `id`, `version`, `name`, `description`, `authors`, `license`, `icon`, `environment: "client"`, `entrypoints.client: ["<base>.blanket.generated.Entry"]`, `mixins: [{"config": "<modid>.blanket.mixins.json", "environment": "client"}]`, `depends: {"fabricloader": ">=0.16.0", "minecraft": "<exact target version>", "java": ">=<target java>"}` | V for `id`, `version`, `entrypoints`, `mixins`, `environment` (R-LDR-10); `schemaVersion` and `depends` keys **A** (S-05) |
| Entry point | generated class implements `net.fabricmc.api.ClientModInitializer`; `onInitializeClient()` creates the mod's `ClientMod`, runs the init sequence (§3); public no-arg constructor | V R-LDR-10 |
| Mixin | bundled by Fabric Loader (0.20.1 bundles `sponge-mixin 0.17.5+mixin.0.8.7`); config declared in `fabric.mod.json` `mixins` | V R-LDR-10 |
| Refmap | none: annotation strings already use runtime names, and Mixin's `ReferenceMapper` returns the original string when no mapping exists | V R-LDR-13 |
| Java baseline | the game's: 8 (≤1.16.5), 16 (1.17), 17 (1.18–1.20.4), 21 (1.20.5–1.21.11), 25 (26.x); Loader itself needs Java 8 | V R-MAP-02, R-LDR-10 |
| Class-file limit | the baseline's version (52/60/61/65/69); Blanket emits 52 for generated code | V (derived from baseline) |
| Jar layout | classes, `fabric.mod.json`, `<modid>.blanket.mixins.json`, `assets/<modid>/icon.png`, `assets/<modid>/lang/…`, licenses under `META-INF/licenses/` | V for root metadata; nested jars (`jars`) not used |
| Mod id rule | Blanket enforces `^[a-z][a-z0-9_]{1,63}$` for all loaders (the strictest common form; Fabric also allows `-`) | **A** (S-05) |
| Legacy specifics | stock Fabric Loader 0.20.1 with `net.legacyfabric:intermediary:<mc>`; LF ids stable across 1.8.9 / 1.12.2 / 1.13.2 for the members checked | V R-LDR-11, R-MAP-13 |

### 2.2 `neoforge-moj`, `neoforge-off` (emitter: `neoforge`)

| Item | Value | |
|---|---|---|
| Metadata | `META-INF/neoforge.mods.toml` from 1.20.5 (the only recognized file; jars without it are skipped silently); `META-INF/mods.toml` for 1.20.2–1.20.4 | V R-LDR-05 |
| Fields written | `modLoader = "javafml"`, `loaderVersion = "[<major>,)"`, `license`, `[[mods]] modId, version, displayName, description, logoFile`, `[[dependencies.<modid>]]` for `neoforge` and `minecraft` with `type = "required"`, `versionRange`, `side = "CLIENT"`, `[[mixins]] config = "<modid>.blanket.mixins.json"` | V `license`, `modId` required, `[[mixins]]` from FML 2.0 (20.3/20.4) (R-LDR-05); dependency table form **A** (S-05) |
| Entry point | generated class annotated `@net.neoforged.fml.common.Mod(value = "<modid>", dist = Dist.CLIENT)` with one public constructor `(IEventBus modBus, ModContainer container)`; constructor runs the init sequence | V constructor parameter types and `dist` from FML 4.0 / NeoForge 21.0 (R-LDR-05); on 20.2–20.6 there is no `dist`, so the constructor returns early when `FMLEnvironment.dist` is not `CLIENT` (**A**, S-05) |
| Event bus | `NeoForge.EVENT_BUS` for game events; mod bus (constructor parameter) for `IModBusEvent` events such as GUI layer and key registration | V R-EVT-04 |
| Mixin | bundled (sponge-mixin 0.16.4 in 21.1.265; 0.17.4 in 26.x); `[[mixins]]` in the toml from 20.3 | V R-LDR-05; 20.2 has no `[[mixins]]` parser, so `neoforge-moj` starts at **20.3 for mods that need a Mixin** (all v1 recipes on NeoForge are native events, so 20.2 works too) |
| Java baseline | 17 (1.20.2–1.20.4), 21 (1.20.5–1.21.11), 25 (26.x) | V R-MAP-02 |
| Jar layout | classes, the toml, mixin config, `pack.mcmeta` (not required on 21.1, V R-EXP-04; written anyway, D-050), assets | — |

### 2.3 `forge-legacy` (emitter: `forge-legacy`)

| Item | Value | |
|---|---|---|
| Metadata | `mcmod.info` at the jar root: JSON array with one entry (`modid`, `name`, `description`, `version`, `mcversion`, `authorList`, `url`, `logoFile`) | V field set (R-LDR-03); exact key casing `modid` vs `modId` **A** (S-04) |
| Entry point | generated class annotated `@net.minecraftforge.fml.common.Mod(modid = "<modid>", name, version, clientSideOnly = true, acceptedMinecraftVersions = "[<mc>]")` with an `@Mod.EventHandler public void init(FMLInitializationEvent e)` that runs the init sequence | V annotation fields and nested `@EventHandler` (R-LDR-03); `FMLInitializationEvent` as the init point **A** (S-04) |
| Event bus | one bus, `MinecraftForge.EVENT_BUS`; registration only by `register(Object)` with `@SubscribeEvent` methods | V R-EVT-01 |
| Mixin | not bundled. v1 recipes on this era are native events only. Any future non-native hook uses a generated coremod (D-014) | V R-PA-09 |
| Names | notch class and member names, read from the LF intermediary's `official` column (no SRG, no MCP) | V R-LDR-01, R-LDR-02 |
| Java baseline / class-file limit | Java 8; Forge's ASM 5.0.3 (1.8.9) / 5.2 (1.12.2) reads class files up to version 52, so **every class in the jar must be ≤ 52** | V R-LDR-04 |
| Jar layout | classes, `mcmod.info`, `assets/<modid>/lang/en_US.lang` (`key=value`), logo; no `TweakClass` attribute (a jar with `TweakClass` is not scanned for `@Mod`, V R-LDR-03) | lang format **A** (S-04) |

### 2.4 `forge-srg`, `forge-srgmoj`, `forge-moj`, `forge-off` (emitter: `forge-modern`)

| Item | Value | |
|---|---|---|
| Metadata | `META-INF/mods.toml`: `modLoader`, `loaderVersion`, `license`, `[[mods]] modId` required; `clientSideOnly = true` where the loader version knows it | V R-LDR-07; first loader version with `clientSideOnly` **A** (S-11) |
| Entry point | `@Mod("<modid>")` class with a no-arg constructor (1.13.2–1.20.4) or one taking `FMLJavaModLoadingContext` (1.20.6+); client-only work is guarded by `FMLEnvironment.dist` | V R-LDR-07 for 1.20.6+; 1.13.2–1.20.4 form **A** (S-11) |
| Event bus | `MinecraftForge.EVENT_BUS` + the mod bus from `FMLJavaModLoadingContext.get().getModEventBus()` until 1.21.5; EventBus 7 (static `BUS` per event) from 1.21.6; `RenderLevelStageEvent` gone after 1.21.1, world drawing via `AddFramePassEvent` | V R-EVT-06 for 1.20.6+; older eras **A** (S-11) |
| Mixin | not bundled before 1.15.2; bundled since 1.15.2-31.2.44 / 1.16.1-32.0.72; configs listed in the `MixinConfigs` manifest attribute. Every v1 recipe on this emitter is a native event, so no era here needs Mixin | V R-LDR-07 |
| Names | `srg` and `srgmoj` per D-033; `mojang` from 1.20.6; `official` on 26.x | V R-LDR-07 |
| Java | 8 (1.13.2–1.16.5), 16 (1.17), 17 (1.18–1.20.4), 21 (1.20.6–1.21.11), 25 (26.x) | V R-MAP-02 |
| Jar layout | classes, `META-INF/mods.toml`, **`pack.mcmeta` with the target's pack format (required: Forge 1.20.1 blocks on a warning screen without it)**, assets | V R-EXP-04, D-050 |

### 2.5 `neoforge-srgmoj` (emitter: `neoforge`)

NeoForge 1.20.1 is Forge 1.20.1 renamed: `META-INF/mods.toml`, package `net.minecraftforge` (the
`net.neoforged` rename starts at 20.2), SRG members (R-LDR-05). The `neoforge` emitter writes it with the
`forge-modern` rules for 1.20.1 and only changes the loader id in the dependency table (**A**, S-11).

## 3. The init sequence (every emitter)

The generated entry class does, in order:
1. create the embedded runtime context (mod id, logger);
2. register native listeners for the events the mod uses (§5 of [events-and-hooks.md](events-and-hooks.md));
3. instantiate the mod's `ClientMod` (public no-arg constructor) and call `init()`;
4. close the key-binding registration window and hand queued bindings to the loader recipe
   ([events-and-hooks.md](events-and-hooks.md) §4.7).

A failure in step 3 is rethrown wrapped with the mod id, so the loader's crash screen names the mod.

## 4. Mixin config (Fabric family and, where used, NeoForge)

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "<base>.blanket.generated.mixin",
  "compatibilityLevel": "JAVA_8",
  "client": [ "<Event>$<HookId>", "..." ],
  "injectors": { "defaultRequire": 1 }
}
```

`JAVA_8` because generated mixin classes are version 52 ([lowering-and-adapters.md](lowering-and-adapters.md)
§7). An empty `client` list means the config is not written at all.

## 5. Why notch names work on legacy Forge

Verified in Forge source (R-LDR-01, R-LDR-02):
- The universal jar contains `deobfuscation_data-<mc>.lzma` (1.8.9: 27,891 lines, e.g.
  `CL: ave net/minecraft/client/Minecraft`).
- `DeobfuscationTransformer` applies `FMLRemappingAdapter(FMLDeobfuscatingRemapper)` to every class's
  bytes on 1.8.9; on 1.12.2 it exempts only library packages in production. Mod classes are included.
- `FMLDeobfuscatingRemapper` maps a member by its notch owner and falls back to the original name, so
  a notch reference becomes SRG and an SRG reference passes through.
- `FMLRemappingAdapter` merges super maps first, so mod methods that override game methods are renamed
  too (not used by v1, whose mod classes never extend Minecraft classes, but adapter classes such as
  `ScreenHost` do).
- `LaunchClassLoader` defines classes under their transformed names.

Consequence: Blanket writes notch names for `forge:1.8.9`, using the `official` column of the
Legacy Fabric intermediary file it already reads for `legacy-fabric:1.8.9`. No SRG or MCP data is
read. The only limit (also verified): string constants are not remapped, so anything that names a
Minecraft member in a string (reflection, Mixin annotations) would break. v1 emits neither on this era.

A coremod transformer (D-014) would see classes after FML's deobfuscation unless it sorts before it;
a generated coremod therefore uses `@SortingIndex` below FML's deobfuscation transformer so it sees
notch names (**A**, spike S-12, only needed when the first non-native legacy-Forge hook appears).

## 6. What emitters do not do

- No jar-in-jar: shaded libraries are embedded and relocated, `dependency`-mode libraries are declared
  in metadata only ([libraries.md](libraries.md) §4).
- No access wideners / access transformers in v1: no v1 binding needs access to a private member.
  When one does, the binding kind `get`/`invoke` on a non-public member becomes a build error until the
  emitter learns to write an access widener (Fabric) or access transformer (NeoForge/Forge); recorded
  as backlog in [milestones.md](milestones.md) M6.
