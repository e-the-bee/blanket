# Mappings and versions

Which mapping data Blanket reads for each Minecraft version, how one symbol is followed across
versions, how the resolver stays within "only what the mod uses", the 1.13/1.14 boundary, and the
recommended target matrix. Facts cite [research-log.md](research-log.md).

## 1. Mapping sources per version range

| Minecraft | Published mappings | Blanket reads | License / terms | Source |
|---|---|---|---|---|
| 1.3 – 1.13.2 | none from Mojang; Legacy Fabric intermediary (`official` → `intermediary`); Legacy Yarn (`intermediary` → `named`) | LF intermediary at generate time; Legacy Yarn only when building the `legacy` adapter stub jar | CC0-1.0 (both) | V R-MAP-06, R-MAP-15 |
| 1.14 – 1.14.3 | Fabric intermediary only (no Mojang mappings) | Fabric intermediary | CC0-1.0 | V R-MAP-03, R-MAP-15 |
| 1.14.4 – 1.21.11 | Mojang `client_mappings` (ProGuard format) and Fabric intermediary | both | Mojang header terms (§8); CC0-1.0 | V R-MAP-03 (present for every version checked from 1.14.4 to 1.21.11) |
| 1.13.2 – 1.20.4 (Forge), 1.20.1 (NeoForge) | MCPConfig `joined.tsrg` (obf → SRG) | only for `srg`/`srgmoj` targets, last resolution step only (D-033) | zlib-style; notice stays with the data, which Blanket never redistributes | V R-MAP-16 |
| 26.1 + | none: the jar is unobfuscated (official names = Mojang names); Fabric's 26.x "intermediary" is an empty placeholder | the jar itself | — | V R-MAP-01, R-MAP-07 |

MCP's human-name files (`methods.csv`, `fields.csv`, `params.csv`) are never read. MCPConfig's
`joined.tsrg` is read only to turn an obfuscated name into the SRG name an SRG-era jar must contain
(settled decision 8 as amended by D-033). Parsing uses FabricMC's `mapping-io` (Apache-2.0), which reads
ProGuard, Tiny v1/v2 and TSRG/TSRG2 through a streaming visitor (R-PA-08, decision D-011).

## 2. Runtime namespaces

The names the output jar must contain are a property of the loader-era, listed in
[loader-emitters.md](loader-emitters.md) §1 and encoded in `targets.json`
([binding-format.md](binding-format.md) §3): `intermediary`, `lf`, `mojang`, `official`, `obf`, `srg`,
`srgmoj`. The
resolver's last step converts a target's obfuscated name into that namespace
([binding-format.md](binding-format.md) §2.4).

## 3. Tracking one symbol across versions

Bindings name symbols in **Mojang names at the anchor version** (1.21.1) unless a rule says otherwise.
Following a symbol to another version uses the identity each ecosystem guarantees:

1. **Fabric intermediary ids are stable from 1.14 to 1.21.11.** This is their purpose, and it holds in
   the data checked: `Entity` is `class_1297` in 1.14.4, 1.16.5, 1.20.1, 1.21.1 and 1.21.11; `getX()` is
   `method_23317` in every version that has it; `isOnGround()` (1.16.5) and `onGround()` (1.20.1+) are
   both `method_24828`, so a Mojang rename is followed with no data (V R-MAP-09, R-MAP-10).
2. **26.x continues from 1.21.11 by name.** There is no intermediary after 1.21.11 (V R-MAP-07,
   R-PA-06). The resolver maps intermediary → 1.21.11 Mojang name and requires the same name and
   descriptor in the 26.x jar. Where Mojang renamed at 26.1 (`GuiGraphics` → `GuiGraphicsExtractor`,
   `Gui.render` → `Gui.extractRenderState`, V R-MAP-12) the lookup fails with `UNRESOLVED` and the binding
   needs an `official:` rule. UniversalCraft's hand-written `26.1-1.21.11.txt` lists the same renames
   (R-PA-04), which confirms the size of the problem: tens of names, not thousands.
3. **Legacy Fabric ids are stable across the LF range but are a separate id space.** `Entity.x` is
   `field_3252` in 1.8.9, 1.12.2 and 1.13.2 (obf `s`, `p`, `q`), `Minecraft.getInstance` is `method_2965`
   in all three (V R-MAP-13). But the same id format means different things in Fabric's numbering:
   LF `class_1600` is the client class, Fabric's is `class_310` (V R-MAP-06, R-LDR-11). Nothing links LF
   ids to Fabric ids, so a legacy rule is always written explicitly with `lf:` references.

There is no attempt to infer renames by structure (v1's `StructuralDiffer`). A failed resolution is a
build error with a hint: `blanket explain <member> --target <t>` lists members of the expected owner
with the same descriptor, which is usually enough for a binding author to write the override
([build-and-gradle.md](build-and-gradle.md) §1).

## 4. Resolver: only what the mod uses

Settled decision 3 says mappings are made only for the symbols the mod uses. The resolver keeps to
that as follows.

1. **Used set.** The scan stage collects every API member the mod references, every API type it
   mentions, and every event it subscribes to. Binding rules for those, plus the adapter closure
   ([lowering-and-adapters.md](lowering-and-adapters.md) §5.4), yield the set of Minecraft references
   to resolve for one target: typically tens to a few hundred.
2. **Streaming lookup.** Each mapping file involved (anchor ProGuard, anchor intermediary, target
   intermediary or LF file, target ProGuard) is read once with a `mapping-io` visitor that keeps only:
   - class rows for classes in the wanted set and for classes appearing in wanted members' descriptors
     (needed to translate descriptors between namespaces);
   - member rows whose owner and name match a wanted member.
   Everything else is discarded as it streams past. Nothing is persisted except the per-target
   resolution (§9).
3. **Jar check.** Each resolved symbol is looked up in the target's client jar through the lazy class
   index ([lowering-and-adapters.md](lowering-and-adapters.md) §6), which reads only the classes asked for
   and their supertypes.

For SRG targets, `joined.tsrg` is streamed the same way: only rows whose obf name is wanted are kept.

What this rules out: building a database of all classes and members, diffing whole versions, or
remapping the whole game jar. The only full-jar processing is the era stub jar inside Blanket's own
development build, which never runs during `generate` (D-006).

## 5. The 1.13/1.14 boundary

The problem: Mojang names start at 1.14.4, Fabric intermediary at 1.14, and LF intermediary ends at
1.13.2 with unrelated ids. No data source links a 1.13.2 symbol to its 1.14 counterpart.

The solution has three parts:
1. **1.14 – 1.14.3 need nothing special.** They have Fabric intermediary, so anchor names reach them
   through intermediary ids (§3 rule 1); the jar check confirms presence. (They are not in the v1
   matrix, but the mechanism covers them.)
2. **≤ 1.13.2 is a separate binding range.** Each binding that applies to legacy targets has rules
   with `lf:` references, written once per member and valid across the whole LF range thanks to LF id
   stability (§3 rule 3). Binding authors find the ids with Legacy Yarn names (CC0), e.g.
   `ClientPlayerEntity`, `getMainHandStack` → `method_3179` (V R-MAP-14). This is explicit data, not
   inference, which is the right trade-off for a range that no longer changes.
3. **Adapters for ≤ 1.13.2 are their own set** (`legacy`), authored in Legacy Yarn names and shipped in
   LF ids ([lowering-and-adapters.md](lowering-and-adapters.md) §5.1).

Cost: every API member that supports legacy targets needs one legacy rule. In the first-release API
that is about 60 rules ([api-surface.md](api-surface.md) §3), a bounded one-time effort.

Legacy Forge reuses the same `lf:` rules: the resolver reads the LF file's `official` column to get
notch names ([loader-emitters.md](loader-emitters.md) §5). `forge:1.13.2` reuses them too, then takes the
obf name through 1.13.2's `joined.tsrg` to SRG ([binding-format.md](binding-format.md) §2.4).

## 6. API alias classes per first-release target

Each alias type must map to one class on each target ([api-surface.md](api-surface.md) §2). Status of
that check:

| API type | 1.8.9 (LF id / Legacy Yarn) | 1.21.1 (Mojang) | 26.1.2 (official) |
|---|---|---|---|
| `Client` | `class_1600` `MinecraftClient` V | `net.minecraft.client.Minecraft` V | same V |
| `Player` | `class_518` `ClientPlayerEntity` V (type of `MinecraftClient.player`) | `net.minecraft.client.player.LocalPlayer` V (type of `Minecraft.player`) | same V |
| `World` | `class_478` `ClientWorld` V | `net.minecraft.client.multiplayer.ClientLevel` V | same V |
| `BlockPos` | `class_2552` V | `net.minecraft.core.BlockPos` **A** | **A** |
| `Vec3` | `class_236` `Vec3d` V | `net.minecraft.world.phys.Vec3` V (return type of `Entity.position()`) | same V |
| `Box` | `class_231` `Box` V | `net.minecraft.world.phys.AABB` V (return type of `getBoundingBox()`) | same V |
| `ItemStack` | `class_1071` V | `net.minecraft.world.item.ItemStack` V | **A** |
| `KeyBinding` | `class_327` `KeyBinding` V | `net.minecraft.client.KeyMapping` V | same V |
| `BlockState`, `Direction` | **A** | **A** | **A** |

V = verified (R-MAP-09, R-MAP-11, R-MAP-14); **A** = ASSUMED. Every **A** cell is closed by the M1 resolver run against real data; spike S-02 in [risks.md](risks.md).

## 7. Target matrix

### 7.1 Release 1.0 (D-034)

| Loader | Releases | Eras |
|---|---|---|
| Legacy Fabric | every release 1.8.9 – 1.13.2 | `legacy-fabric` |
| Fabric | every release 1.14 – newest 26.x | `fabric-int`, `fabric-off` |
| Forge | every release with a Forge build, 1.8.9 – newest 26.x (no 1.20.5, R-LDR-14) | `forge-legacy`, `forge-srg`, `forge-srgmoj`, `forge-moj`, `forge-off` |
| NeoForge | 1.20.1, 1.21.1, 1.21.11, newest 26.x | `neoforge-srgmoj`, `neoforge-moj`, `neoforge-off` |

About 130 targets (estimate; the exact list is generated, [binding-format.md](binding-format.md) §3.1).
An author still picks targets in the Gradle block; the matrix is what Blanket promises, not what every
build produces.

**Era representatives.** Tests and milestones use one representative per (loader, era, render model)
cell instead of every release ([testing-strategy.md](testing-strategy.md) §5). The core set, which
M0–M5 build against, is the previous five targets plus `forge:1.20.1` (the SRG proof, added to M0):
`legacy-fabric:1.8.9`, `forge:1.8.9`, `fabric:1.21.1`, `neoforge:1.21.1`, `fabric:26.1.2`,
`forge:1.20.1`.

### 7.2 After 1.0

New Minecraft releases are supported within four weeks of release on Fabric, Forge and NeoForge
(newest 26.x). No supported release is dropped (D-034).

### 7.3 Never in 1.x

- Snapshots and pre-releases: their names and hooks churn weekly; `targets.json` accepts release ids only.
- Ornithe 1.8.9 (different intermediary; could be added as its own loader-era later).
- Fabric/Legacy Fabric and Forge releases older than 1.8.9.

## 8. Mojang mapping terms: the conditions Blanket relies on

The header of every `client_mappings.txt` checked (1.14.4, 1.21.1, 1.21.11; identical bytes) reads
(V R-MAP-08):

> (c) 2020 Microsoft Corporation. These mappings are provided "as-is" and you bear the risk of using
> them. You may copy and use the mappings for development purposes, but you may not redistribute the
> mappings complete and unmodified. Microsoft makes no warranties, express or implied, with respect to
> the mappings provided here. Use and modification of this document or the source code (in any form)
> of Minecraft: Java Edition is governed by the Minecraft End User License Agreement available at
> https://account.mojang.com/documents/minecraft_eula.

v1 recorded a legal review approving build-time use (v1 `docs/project/decisions.md`, "Legal Gate",
2026-07-06). Blanket carries it forward under these exact conditions:

1. Mapping files are downloaded by the user's own build from Mojang's URLs in the version JSON,
   checked against Mojang's SHA-1, and cached under the user's Blanket cache directory. Blanket never
   hosts, mirrors, commits or bundles them.
2. Output jars contain only the class and member names the mod's bytecode needs, as bytecode
   references, never a mapping file or a name table.
3. Blanket's published artifacts contain Mojang names only inside binding JSON (a few hundred symbols
   the API needs) and never a mapping file "complete and unmodified". Adapter bytecode ships in
   intermediary or official names ([lowering-and-adapters.md](lowering-and-adapters.md) §5.2).
4. Era stub jars, which do embody a whole version's Mojang-named signatures, are built on maintainer
   machines and CI only and are never published.
5. If Mojang changes the terms in a new mappings file, the resolver reads the header and stops with an
   error when it differs from the text above, so a terms change cannot pass unnoticed (check C8,
   [testing-strategy.md](testing-strategy.md)).

Intermediary, Legacy Intermediary and Legacy Yarn are CC0-1.0 (V R-MAP-15) and carry no conditions.

## 9. Caching

| Item | Location | Key | Invalidated by |
|---|---|---|---|
| Downloads (version JSON, client jar, mapping files, intermediary jars, loader artifacts) | `~/.blanket/cache/downloads/` (override `BLANKET_CACHE`) | URL; verified by SHA-1 from Mojang or SHA-256 from the lockfile | never (content-addressed) |
| Per-target resolution | `build/blanket/<target>/resolution.json` | hash of the used set + bindings version + input hashes | any key change |
| Class index entries | in memory only | — | — |

The resolution file contains only the resolved symbols for that mod and target. It is a build
artifact inside the user's project, not distributed.

## 10. Adding a new Minecraft release

1. Add the version to `targets.json` (Java from the version JSON) and, if a new era starts, the era row.
2. Run the bindings validator ([binding-format.md](binding-format.md) §9) against it. Every `UNRESOLVED`
   is a rename or shape change: add a rule (`official:` reference, or a new adapter).
3. Re-run the in-game smoke tests for the new target ([testing-strategy.md](testing-strategy.md) §4).
4. Release a Blanket version. Mod authors rebuild (settled decision 7).
