# Third-party libraries

A mod may use third-party Java libraries. The author declares them once in `blanket-libs.json`
(settled decision 5) and each target's jar gets the version the file resolves for it. This document is
the final schema, the resolution algorithm, the three modes, the compatibility check, the lockfile,
relocation and license handling.

## 1. Where it fits

The mod is compiled once against each library's `compile` version (the Gradle plugin puts those jars
on the compile classpath). At generate time, per target, the generator resolves a version, downloads
it, checks that every library member the mod uses exists in it, and then embeds, declares or merely
verifies it according to the mode.

## 2. `blanket-libs.json` schema

```json
{
  "schema": 1,
  "libraries": {
    "<name>": {
      "mode": "shade" | "dependency" | "provided",
      "repo": "<url>" | ["<url>", ...],
      "coordinates": "<group>:<artifact>:<version-template>",
      "url": "<url-template>",
      "compile": "<version>",
      "versions": [ <rule>, ... ],
      "optional": false,
      "relocate": true,
      "relocationExclusions": ["<package prefix>", ...],
      "rewriteClassNameStrings": false,
      "keep": ["<class name or package prefix.*>", ...],
      "license": { "spdx": "<id>", "file": "<path in project>" }
    }
  }
}
```

| Key | Required | Meaning |
|---|---|---|
| `mode` | yes | §4 |
| `coordinates` or `url` | exactly one (may be overridden per rule) | Maven coordinates resolved against `repo` (default Maven Central), or a direct URL. Templates: `$version`, `$mc`, `$loader`. |
| `repo` | no | One or more Maven repositories, tried in order |
| `compile` | yes | Version the mod compiles against |
| `versions` | yes | Rule list, first match wins (§3) |
| `optional` | no (default `false`) | No matching rule means the library is absent on that target instead of a build error (§3.3) |
| `relocate` | no (default `true` for `shade`) | §6 |
| `relocationExclusions` | no | Package prefixes left unrelocated (§6) |
| `rewriteClassNameStrings` | no (default `false`) | Rewrite string constants that are exactly a relocated class or package name (§6) |
| `keep` | no | Classes the shrink pass must keep although nothing references them (reflection, `ServiceLoader` roots) |
| `license` | no | Overrides license detection (§7) |

A **rule**:

```json
{ "when": { "loader": "...", "mc": "..." }, "version": "4.4",
  "coordinates": "...", "url": "...", "repo": "...", "mode": "...",
  "modId": "...", "range": "..." }
```

`when` uses exactly the grammar of bindings ([binding-format.md](binding-format.md) §2.1): exact id,
`a..b`, open ranges, `/regex/`; parsed by the same code. Everything except `when` and `version` is an
optional override of the library-level value. `modId` and `range` apply to `dependency` mode (§4.2).

Example (from PLAN.md, made complete):

```json
{
  "schema": 1,
  "libraries": {
    "foo": {
      "mode": "shade",
      "repo": "https://repo.example.com/releases",
      "coordinates": "com.example:foo:$version",
      "compile": "4.4",
      "versions": [
        { "when": { "mc": "1.21.11" }, "version": "4.4" },
        { "when": { "mc": "26.1.." }, "version": "5.2" },
        { "when": { "loader": "forge", "mc": "1.8.9..1.12.2" }, "version": "3.0" },
        { "when": { "mc": "/^1\\.20\\..*/" }, "version": "4.1" },
        { "when": {}, "version": "4.4" }
      ]
    },
    "gson": {
      "mode": "provided",
      "compile": "2.8.0",
      "versions": [ { "when": {}, "version": "minecraft" } ]
    }
  }
}
```

`"version": "minecraft"` in a `provided` rule means "the version listed in the target's Mojang version
JSON `libraries`" (for Gson: 2.2.4 on 1.8.9, 2.8.0 on 1.12.2, 2.14.0 on 26.3; V R-MAP-05). The
generator looks it up, downloads that exact jar from `libraries.minecraft.net` for checking, and never
embeds it.

## 3. Resolution algorithm

For each target and each library:

1. Walk `versions` top to bottom; take the first rule whose `when` matches.
2. No match: if `optional`, the library is **absent** on this target (§3.3); otherwise build error
   `no version of library 'foo' for target forge:1.12.2 (rules: 5, none matched)`.
3. Merge the rule's overrides onto the library-level values; expand templates.
4. Look up the lockfile (§5). Present and matching coordinates: use the locked URL and hash. Missing:
   error, unless the project has no lockfile at all (first build), in which case it is created.
5. Download into the cache, verify SHA-256 against the lock.
6. Run the compatibility check (§3.2), then the mode's action (§4).

### 3.1 Transitive dependencies

Not resolved. The artifact named is the artifact used. If its POM declares `compile` or `runtime`
dependencies that no library entry covers, the generator warns, naming them. Reason: transitive
resolution per target and version would hide exactly the per-target differences this file exists to
make explicit, and makes the lockfile harder to review.

### 3.2 Compatibility check

The scan stage already lists every member of every library class the mod references (the used set,
[architecture.md](architecture.md) §3.1). Library classes are recognized by being present in the
`compile` jar. For the resolved jar of each target, every referenced class must exist and every
referenced member must exist with the same descriptor (on the class or a supertype, through the class
index). A miss fails that target:

```
error LIBRARY [forge:1.8.9] library 'foo' 3.0 lacks com/example/foo/Client.connect(Ljava/time/Duration;)V
      used by com.example.mymod.Net.open()V (compiled against foo 4.4)
```

Bridging real API differences between library versions (an adapter for a library) is not in v1; the
author picks versions that are compatible for what the mod uses, or restricts targets.

The same check also enforces the class-file limit: a shaded library jar whose classes are newer than
the target's Java baseline fails ([lowering-and-adapters.md](lowering-and-adapters.md) §7).

### 3.3 Optional libraries

When an optional library is absent, mod code that reaches it must be dead on that target. The API
provides `Blanket.hasLibrary("foo")`, folded to a constant per target exactly like `supports`
([lowering-and-adapters.md](lowering-and-adapters.md) §4.7). Reachable references to an absent library
are a build error naming the calling method.

## 4. Modes

### 4.1 `shade`

1. Download the resolved jar.
2. Drop `module-info.class`, signature files (`META-INF/*.SF`, `*.RSA`, `*.DSA`, `*.EC`) and
   `META-INF/maven/**`.
3. Multi-release jars: for each class keep the highest `META-INF/versions/N/` variant with N ≤ the
   target's Java baseline, place it at the base path, remove `META-INF/versions/**` and the
   `Multi-Release` manifest attribute.
4. Relocate (§6).
5. Shrink at class level: keep classes reachable from the mod's references, from `keep`, and from
   `META-INF/services` implementations whose interface is reachable.
6. Copy license files (§7).

### 4.2 `dependency`

The library is itself a mod the player installs. Nothing is embedded. The emitter writes a
dependency into the metadata from the rule's `modId` and `range` (Maven range syntax, e.g. `[2.1,3)`):

| Emitter | Written as |
|---|---|
| `fabric` | `depends: { "<modId>": ">=2.1 <3" }` (range converted to Fabric's version predicate syntax) |
| `neoforge` | `[[dependencies.<modid>]] modId = "<modId>"`, `type = "required"`, `versionRange = "[2.1,3)"`, `side = "CLIENT"` |
| `forge-legacy` | `@Mod(dependencies = "required-after:<modId>@[2.1,3)")` and `requiredMods` in `mcmod.info` |

The forms are **ASSUMED** until the M5 emitter tests (spike S-05). A dependency mod's API may be used
only where it has no Minecraft types in the members the mod references: those would need mapping into
each target's names, which is the job of bindings, not of this file. A referenced member whose
descriptor contains `net/minecraft/` is a build error with that explanation.

### 4.3 `provided`

Minecraft or the loader ships the library (V R-MAP-05: Gson, Guava, log4j-api, fastutil, commons-lang3,
JOML from 1.19.3 are in Mojang's library lists). Nothing is embedded, nothing is declared; the jar is
downloaded only to run the compatibility check. `"version": "minecraft"` reads the version from the
target's version JSON; loader-shipped libraries (for example MixinExtras in Fabric Loader 0.20.1,
V R-LDR-10) are given explicitly.

## 5. Lockfile `blanket.lock`

```json
{
  "schema": 1,
  "blanket": "1.0.0",
  "targets": {
    "fabric:1.21.1": {
      "libraries": {
        "foo": { "mode": "shade", "version": "4.4",
                 "url": "https://repo.example.com/releases/com/example/foo/4.4/foo-4.4.jar",
                 "sha256": "9f0c…" }
      },
      "inputs": {
        "client.jar":      { "url": "https://piston-data.mojang.com/…/client.jar", "sha256": "…" },
        "client_mappings": { "url": "https://piston-data.mojang.com/…/client.txt", "sha256": "…" },
        "intermediary":    { "url": "https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1-v2.jar", "sha256": "…" },
        "loader":          { "url": "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar", "sha256": "…" }
      }
    }
  }
}
```

- Written with sorted keys so diffs are reviewable. Committed with the mod's source.
- `inputs` pins every Minecraft and loader artifact the generator read, so a rebuild reads exactly the
  same game and loader data (the loader artifact is read for native event classes and entry
  interfaces, [lowering-and-adapters.md](lowering-and-adapters.md) §6).
- Updated only by `blanket lock` / `blanketLock` (all targets) or `blanket lock --target <t>`. A
  `generate` that finds a hash mismatch fails: shaded code ships inside the player's jar, so a changed
  upstream artifact must be a reviewed change.
- Mapping file hashes are recorded, never their content (conditions in
  [mappings-and-versions.md](mappings-and-versions.md) §8).

## 6. Relocation rules

- Prefix: `<base>.blanket.libs.<name>.` (`<base>` is the mod's base package). Two Blanket mods that
  shade the same library at different versions never collide.
- Relocated: every class in the library's own packages (the packages of classes in its jar), in the
  library itself and in the mod's references to it. Class references only: constant pool class
  entries, descriptors, signatures, annotations, `Handle`s, `MethodType`s.
- Resources inside relocated package directories are moved with their package.
- `META-INF/services/<interface>` files are renamed to the relocated interface name and their
  contents rewritten to relocated class names.
- String constants are **not** rewritten by default (Shadow's default rewrites them,
  `skipStringConstants = false`, which breaks log messages and identifiers; R-PA-13). With
  `rewriteClassNameStrings: true`, only strings exactly equal to a relocated class name (dotted or
  slashed) or to a relocated package prefix followed by `.` are rewritten — the case of libraries that
  call `Class.forName` on their own classes.
- Never relocated: `java.*`, `javax.*`, `jdk.*`, `sun.*`, packages in `relocationExclusions`, and
  packages that belong to Minecraft, the loader, or a `provided` library.
- `relocationExclusions` exists for libraries that must keep their names (for example a library whose
  classes are looked up by name from outside the mod). Excluded packages can collide with another mod
  shading the same library; the generator warns once per excluded package.
- `relocate: false` skips relocation entirely (warning: collision risk).

Libraries that relocation cannot fix (heavy reflection with computed names, bytecode generation of
their own classes, Kotlin reflection) should use `dependency` mode or stay out; the compatibility
check cannot detect those cases, so this is documented and listed as risk R-13.

## 7. License handling

For each shaded library on each target:
1. Collect `META-INF/LICENSE*`, `META-INF/NOTICE*`, `LICENSE*`, `NOTICE*`, `COPYING*` from the jar and
   the license names in its POM.
2. Copy them to `META-INF/licenses/<name>/` in the output jar.
3. If none is found and no `license` override exists, fail: `library 'foo' has no license file; add
   "license": {"spdx": ..., "file": ...}`. Shipping code without its license is a legal defect, not a
   warning.
4. Append a line to `META-INF/licenses/THIRD-PARTY.txt`: name, version, SPDX id (from the override or
   POM), source URL.

Blanket's own embedded code (runtime, adapters) and the default font are added the same way: Blanket's
license and Inter's OFL notice appear in `THIRD-PARTY.txt` when used.

**Blanket's license (D-038).** Blanket is GPL-3.0-only with an attribution term and a permission to link
with Minecraft and mod loaders. Every generated jar contains Blanket code, so the generator:
1. always writes `META-INF/licenses/blanket/LICENSE` (GPL-3.0 text plus the additional terms) and the
   line `Built with Blanket by e-the-bee — GPL-3.0-only — <repository URL>` into `THIRD-PARTY.txt`;
2. fails when the mod's `license` is missing, `ARR`, `All-Rights-Reserved` or `LicenseRef-Proprietary`;
3. warns when the mod's SPDX id is not on the GPL-3.0-compatible list (GPL-3.0-only/-or-later,
   AGPL-3.0, LGPL-2.1/3.0, MIT, BSD-2/3-Clause, Apache-2.0, MPL-2.0, Zlib, CC0-1.0, Unlicense), saying
   that the jar as distributed is GPL-3.0 and its source must be offered.
Shaded libraries with GPL-incompatible licenses (for example EPL-1.0) produce the same warning.

The generator does not judge license compatibility (for example GPL code shaded into an MIT mod). It
prints all shaded licenses in the support report so the author sees them.
