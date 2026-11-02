# Blanket

> **Status: in progress.** No release has been published.

Blanket is a build-time generator for Minecraft client mods. You write a mod once, in plain Java, against the Blanket API, and compile it once. At build time you choose targets (loader and Minecraft version, for example `fabric:1.21.1`, `neoforge:1.21.1`, `legacy-fabric:1.8.9`, `forge:1.20.1`, `fabric:26.1.2`). Blanket generates a native mod jar for each target in that loader's own format.

Players install nothing extra. There is no Blanket at runtime beyond a small relocated runtime inside each jar.

## Planned scope (release 1.0)

- Every Fabric and Forge release from 1.8.9 to the newest 26.x.
- NeoForge 1.20.1, 1.21.1, 1.21.11 and the newest 26.x.
- Client-side only.
- Blanket's own 2D and 3D rendering API.

## How it works

The generator runs in stages: scan the API the mod uses, resolve bindings and mapping files for each target, lower API calls to Minecraft members or adapters, wire entry points and Mixins, shrink unused code, then emit a reproducible jar with metadata.

Built mods are frozen. A new Minecraft version needs a rebuild.

## Documents

Design index: [PLAN.md](PLAN.md). Milestones: [docs/milestones.md](docs/milestones.md). Architecture: [docs/architecture.md](docs/architecture.md). API surface: [docs/api-surface.md](docs/api-surface.md).

## Licence

GPL-3.0-only, with the additional terms in `docs/decisions.md` (D-038). Group `io.github.e-the-bee`.
