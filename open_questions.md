# Open questions

**Status: all answered (2026-10-29).** Each answer is recorded as a decision in
[docs/decisions.md](docs/decisions.md) D-032–D-042 and folded into the documents; the "Resolved" line
under each answer names the decision.

These are the only things the plan needs from you. Each has a recommended default that the documents
in `docs/` already assume; if you accept a default, nothing changes. Questions are ordered by how much
of the plan depends on the answer.

## Answer these first

### Q1 — Who is Blanket for, and what is the first real mod?

**Question.** Is Blanket mainly a tool for your own mods, or a public tool for other mod authors? And
which concrete mod will be built with it first?

**Why it matters.** The first real mod decides which backlog items join the API first
(`api-surface.md` §6, milestone M6) and which targets matter most. A public tool also raises the bar
for documentation, error messages, the support policy (Q3) and licensing (Q9).

**Options.**
- *Own mods first, public later.* Faster; API grows only with your mods; docs can stay terse until
  release 1.0.
- *Public tool from the start.* More author-facing docs, examples and stability promises before 1.0;
  API growth driven by outside requests.

**Recommended default.** Public tool, built and proven on your own mods first. First mod: a HUD/overlay
client mod (an info HUD with key toggles and a world highlight), because the v1 API already covers it
end to end.

**Revisit if different:** `api-surface.md`, `milestones.md` (M5/M6), `testing-strategy.md` (samples).

Answer: Public tool, but probably wont gain traction for a while so it will be similar to own mods first

Resolved: D-032.

### Q2 — May Blanket read SRG mappings to support Forge 1.13–1.20.5 and NeoForge 1.20.1?

**Question.** Settled decision 8 says "no MCP/TSRG". Those loader-eras need SRG member names in the
jar, which exist only in MCPConfig/TSRG data. Do you want to keep them out, or allow reading the
obfuscated-to-SRG table (never MCP's human names) at build time for those targets?

**Why it matters.** Forge 1.20.1 is one of the most-used modded versions; Forge 1.16.5 and 1.12-era
modern Forge too. Today the plan excludes them (`loader-emitters.md` §1, `decisions.md` D-010, risk
R-22). Legacy Forge 1.8.9/1.12.2 is unaffected: it works with notch names (verified, D-012).

**Options.**
- *Keep decision 8 (exclude).* No MCP-derived data anywhere; Forge reachable only on 1.8.9–1.12.2 and
  1.20.6+.
- *Allow SRG tables only.* Adds an `srg` runtime namespace and an MCPConfig download per version; one
  more mapping source with its own license terms to review; opens Forge 1.17–1.20.4 and NeoForge 1.20.1.

**Recommended default.** Keep decision 8. Reconsider after release 1.0 if users ask for Forge 1.20.1.

**Revisit if different:** `loader-emitters.md`, `mappings-and-versions.md` §1/§7, `binding-format.md`
§2.3/§3, `risks.md` R-22, `decisions.md` D-010.

Answer: v1.0 should release with full functionality for all versions, even with a couple of bugs. versions before that (v0.1 - 0.9) should include code but leave out some very functional peice(s) from git to make it unusable till v1.0

Resolved: D-033 (SRG tables), D-035 (0.x withholds the emitters).

### Q3 — Which targets does release 1.0 promise, and how fast are new Minecraft releases supported?

**Question.** Confirm the first-release targets, and choose a support commitment for new releases.

**Why it matters.** Minecraft shipped three releases in 2026 so far (26.1, 26.2, 26.3); each needs
binding updates and a Blanket release (settled decision 7). The matrix sets CI cost and maintenance
load (`testing-strategy.md` §5, risk R-19).

**Options.**
- *Matrix:* the five recommended (`legacy-fabric:1.8.9`, `forge:1.8.9`, `fabric:1.21.1`,
  `neoforge:1.21.1`, `fabric:26.1.2`), or fewer (drop `forge:1.8.9`), or more (add the newest 26.x,
  which needs the `gpu` backend for Vulkan players).
- *Support policy:* "latest release within N weeks" plus a fixed set of long-lived versions (1.8.9,
  1.21.1); or best effort.

**Recommended default.** The five targets for 1.0; afterwards the newest release within four weeks of
its release, plus 1.8.9 and 1.21.1 kept indefinitely; older 26.x releases dropped when a newer one is
supported.

**Revisit if different:** `mappings-and-versions.md` §7, `milestones.md`, `testing-strategy.md` §5,
`decisions.md` D-009.

Answer: all versions for fabric and forge, neoforge / other can be selective

Resolved: D-034.

### Q4 — Should v1 have a per-target escape hatch for loader-specific code?

**Question.** Should an author be able to add hand-written, loader- or version-specific classes to one
target's jar (for example a config screen integration or networking that the API does not cover)?

**Why it matters.** Without it, anything outside the API is impossible; with it, mod source again
contains loader code (in a separate source set), which bends settled decision 2 and needs
compilation against that target's real classes.

**Options.**
- *No escape hatch in v1.* Everything goes through the API; missing features wait for M6.
- *Per-target overlay source sets* (`src/fabric1_21_1/java`), compiled against that target's real
  game and loader, merged into that target's jar only, unable to reference Blanket internals.

**Recommended default.** Not in v1; design the overlay after M5 if a real mod needs it.

**Revisit if different:** `architecture.md` §3, `build-and-gradle.md` §2/§5, `binding-format.md`
(intro), `milestones.md` M6.

Answer: yes, but make it so they have to build with yarn, forge, etc if they want that, not built into blanket v1, v2 should

Resolved: D-036.

### Q5 — Java only, and at which source level?

**Question.** Is v1 Java-only (no Kotlin), and is it acceptable that a mod targeting 1.8.9 or 1.16.5
must be written in Java 8 source?

**Why it matters.** The plan compiles the mod once at the lowest target's Java level (D-013), because
bytecode downgrading (JvmDowngrader) is LGPL-licensed and would shade stubs into every jar. Kotlin
adds its standard library to every jar and its reflection does not survive relocation (`libraries.md`
§6).

**Options.**
- *Java only, lowest-target source level* (plan as written).
- *Java with downgrading:* write modern Java, downgrade per target; requires accepting JvmDowngrader's
  license in every output.
- *Kotlin support:* add Kotlin as a `dependency` on a Kotlin language mod per loader, or shade the
  stdlib.

**Recommended default.** Java only, lowest-target source level.

**Revisit if different:** `lowering-and-adapters.md` §7, `build-and-gradle.md` §2, `decisions.md`
D-013, `risks.md` R-14.

Answer: recommended default

Resolved: D-037.

## Other questions

### Q6 — Does v1 need GUI widgets?

**Question.** Should v1 include buttons, text fields and lists for mod screens, or only drawing and
raw input (as planned)?

**Why it matters.** Screens are the highest-demand survey row (10 mods), but a widget toolkit is a
sizeable owned subsystem on top of `Canvas2D`.

**Options.** Raw drawing and input only (plan); a small widget set (button, toggle, slider, text field)
built on `Canvas2D` in M6.

**Recommended default.** Raw drawing in v1; widgets as the first M6 item if Q1's first mod needs a
settings screen.

**Revisit if different:** `api-surface.md` §3.10/§6, `rendering-api.md`, `milestones.md`.

Answer: raw drawing in 0.1 - 0.9, v1 should have

Resolved: D-039.

### Q7 — Is the rendering API for use inside Minecraft only?

**Question.** "2D and 3D applications": should the rendering API also be usable standalone (an own
window, no Minecraft), or only inside the game?

**Why it matters.** Standalone use needs window and input management, a published runtime artifact
and API stability outside mods; the plan uses standalone only as a test harness.

**Options.** Inside Minecraft only (plan); also standalone (adds a windowing module and a product
surface).

**Recommended default.** Inside Minecraft only.

**Revisit if different:** `rendering-api.md` §1/§6, `architecture.md` §2.

Answer: only inside game, but its useful for effects that require drawing in front or behind a player or object or block etc. or spacial guis or floating text that you can walk behind (concept for a mod im making)

Resolved: D-040.

### Q8 — How do authors run and debug during development?

**Question.** Is running the generated production jar in a provisioned clean instance (with a debugger
attached) enough, or do you also want an IDE run configuration?

**Why it matters.** The plan deliberately has no Loom-style dev environment (`build-and-gradle.md` §4,
D-025). An IDE run configuration is a convenience on top, not a different design.

**Options.** `blanketRun<Target>` only (plan); plus generated IntelliJ/VS Code run configurations that
call it with the debugger attached.

**Recommended default.** `blanketRun<Target>` in M5, generated IDE run configurations as a small M5
addition.

**Revisit if different:** `build-and-gradle.md` §4, `decisions.md` D-025.

Answer: recommended

Resolved: D-041.

### Q9 — Name, package, Maven group and license

**Question.** Keep the name "Blanket" and package `com.blanket` (carried from v1)? Which Maven group
(you need to own its namespace)? Which license for Blanket's own code, given that its runtime and
adapters are embedded into every mod built with it?

**Why it matters.** Blanket's code ends up in other people's jars, so its license constrains every
mod author. A copyleft license would bind all of them.

**Options.** MIT or Apache-2.0 (permissive, no burden on mod authors); LGPL (a burden on every jar);
group `io.github.<your-handle>` (no domain needed) or a domain you own.

**Recommended default.** Keep the name and package; MIT for everything (v1 was MIT); group
`io.github.<your-handle>`.

**Revisit if different:** `decisions.md` D-004, `libraries.md` §7, `build-and-gradle.md` §2.

Answer: io.github.e-the-bee. I want to make sure my code is protected from being stolen and claimed to be made by someone else by building off of my work and stripping my name, or stealing my code. I want it to also not be used for closed source projects so i can enforce people like hackers using my code to make cheats more accessable. 

Resolved: D-038 (GPL-3.0-only + attribution term + Minecraft linking permission).

### Q10 — Is building era stub jars compatible with decision 3?

**Question.** Settled decision 3 says Blanket never models "all of Minecraft". Adapter authors need a
signature-only stub of a whole game version to compile against; the plan builds these only on Blanket
maintainers' machines and CI, never during a mod's build and never published (D-006). Is that within
the decision's intent?

**Why it matters.** If not, adapters must be written against unreadable intermediary ids or compiled
against stubs generated per adapter, which makes adapter work much slower.

**Options.** Yes, maintainer-only stub jars are fine (plan); no, generate stubs only for referenced
classes (two-pass authoring).

**Recommended default.** Yes.

**Revisit if different:** `lowering-and-adapters.md` §5.1, `decisions.md` D-006.

Answer: yes

Resolved: D-042.
