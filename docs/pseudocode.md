# Pseudocode

Every pipeline stage and the runtime parts, written out as pseudocode before any real code, to find
the problems a design hides. Each problem found is marked **P-nn** inline and listed in §10 with where
it was fixed. Notation: Java-like, `→` for "returns", `!` for "build error", `//` for notes. Names match
the other documents.

## 1. Driver

```
generate(modJar, config, targets, overlays):
    scan  = cachedByHash(modJar, () -> SCAN(modJar, config))                    // §2, once
    errs  = []
    plans = parallel for t in targets:
        try   RESOLVE(scan, t, overlays[t])                                       // §3
        catch BuildErrors e: errs += e
    if errs: fail(errs sorted by target, then member)                             // all targets reported together
    parallel for (t, plan) in plans:
        classes = LOWER(scan, plan)                                               // §4
        classes += WIRE(plan)                                                     // §5, blanket-emit
        classes  = SHRINK(classes, plan)                                          // §6
        EMIT(classes, plan, config)                                               // §7, blanket-emit
    writeReport(plans)
```

## 2. Scan (once)

```
SCAN(modJar, config) → Scan:
    s = new Scan()
    for cls in modJar.classes:                         // ClassNode, full code
        for insn in cls.allInstructions + cls.signatures + cls.annotations + cls.ldcTypes:
            if owner(insn) startsWith "com/blanket/api/":
                s.apiMembers += key(owner, name, desc)
                s.apiTypes   += typesIn(desc)
            if insn is GETSTATIC com/blanket/api/Events.X: s.events += X
            if insn is INVOKESTATIC Blanket.supports:
                prev = previous real insn
                prev is GETSTATIC Feature.F ? s.featureSites += (cls, insn, F)
                                            : !"supports() needs a literal Feature"    (class, method, line)
            if insn is INVOKESTATIC Blanket.hasLibrary: same, needs LDC String
            if insn is INVOKESTATIC Blanket.overlay:                                   // P-01
                prev is LDC Type T ? s.overlaySites += (cls, insn, T)
                                   : !"overlay() needs a class literal"
            if insn is INVOKESTATIC Texture.resource / Font.resource:
                prev is LDC String path ? s.resources += path : !"resource path must be a constant"
            if insn references a declared library's class: s.libMembers += ...
            checkForbidden(insn)                         // lowering §3: reflection on API types, typeSwitch, ...
    s.bakedAssets = bake(s.resources, config.fonts)      // PNG → RGBA, TTF → SDF atlas
    return s
```

**P-01.** `Blanket.overlay(Class)` must be recognised and folded exactly like `supports`; it needs a
class literal. Added to the scanner rules and to the API (`api-surface.md` §3.11).

## 3. Resolve (per target)

```
RESOLVE(scan, t, overlay) → Plan:
    era  = targets.loaders[t.loader].firstMatch(t.mc)     ?: !"no era for <t>"
    ver  = targets.versions[t.mc]
    data = downloads(t, era, ver)        // client jar, ProGuard?, intermediary?, LF?, joined.tsrg?, loader jar
    idx  = ClassIndex(data.clientJar, data.loaderJar, jdkCtSym(ver.java), modJar, overlay?.jar)

    // 3a. which owned/runtime classes are reachable, BEFORE resolving glue        P-03
    backendClasses = era.render == "gl"  ? {Gl impl for ver.lwjgl}
                   : era.render == "gpu" ? {gpu executor}
                   : both
    closure = classReach(roots = scan.modClasses + overlay?.classes,
                         over  = ownedApi + runtime + backendClasses)
    used = scan.apiMembers + glueRefsIn(closure) + scan.events.recipes(era)

    // 3b. pick a rule per used member
    for m in used:
        rule = bindings.rules(m).firstMatch(t)                  ?: err UNBOUND(m, t)
        if rule.requires F and !features[F](t): rule = UNSUPPORTED(F)
        plan.rules[m] = rule
    plan.adapters = adapterClosure(plan.rules)                  // adds adapter refs to `wanted`, iterate to fixpoint

    // 3c. resolve every reference in one streaming pass per mapping file
    wanted = refsIn(plan.rules) + refsIn(plan.adapters) + hookRefs(plan.events)
    names  = NameRoutes(era.runtime, data).resolveAll(wanted)   // below
    for r in wanted:
        n = names[r]                                    ?: err UNRESOLVED(r, step)
        idx.exists(n)                                   ?: err UNRESOLVED(r, "absent from jar")
    shapeCheck(plan.rules, names)                       // SHAPE
    enumOrderCheck(scan.apiTypes, names)                // ORDER
    verifyHooks(plan.events, idx)                       // C7 at generate time
    plan.features = { F: features[F](t) }
    plan.overlayImpls = { T: overlay.provides[T] or null for T in scan.overlaySites }
    throw collected errors, else return plan
```

**P-03.** The architecture said glue references join the used set "when the runtime classes that make
them are reachable", but reachability was only computed in SHRINK, after RESOLVE. Without it, a mod that
never draws would still resolve GL glue and could fail on a target where that glue is unbound. Fix:
RESOLVE computes the class-level closure first (3a), and SHRINK reuses it plus the wired classes
(§6). One reachability computation, two uses.

### 3.1 Name routes

```
NameRoutes.resolve(ref):                         // ref = (ns, class, member?)
    obf = toTargetObf(ref)
    return toRuntime(obf)

toTargetObf(ref):
    switch ref.ns:
      mojang@A:      o = proguard[A].obfOf(ref); i = intermediary[A].idOf(o); return toTargetObf(intermediary:i)
      intermediary:  if t.mc in 1.14..1.21.11: return intermediary[t].obfOf(i)
                     if t.mc >= 26.1:          return official(require(proguard[1.21.11].nameOf(intermediary[1.21.11].obfOf(i))))
                     else unresolvable
      lf:            t.mc <= 1.13.2 ? lf[t].obfOf(id) : unresolvable
      official:      t.mc >= 26.1 ? ref : unresolvable
      loader:        return LOADER(ref.owner literal, descriptor classes resolved as mojang@anchor)   // P-09

toRuntime(obf):
    switch era.runtime:
      intermediary: intermediary[t].idOf(obf)
      lf:           lf[t].idOf(obf)
      mojang:       proguard[t].nameOf(obf)
      official:     obf
      obf:          obf                                   // legacy Forge; FML remaps at load
      srg:          tsrg[t].classOf(obf) / tsrg[t].memberOf(obf)
      srgmoj:       class: proguard[t].nameOf(obf); member: tsrg[t].memberOf(obf)
    // descriptors are translated class by class through the same function           P-04
```

**P-04.** TSRG member rows carry descriptors in obfuscated class names; ProGuard rows carry Mojang
names. Every descriptor is therefore translated class by class through `toRuntime`, never copied from
a mapping row. This also covers `srgmoj`, where classes and members come from two different files.

**P-05.** Forge targets 1.14–1.20.4 need Fabric's intermediary file for that version even though the
jar is for Forge, because bindings go through intermediary ids. `downloads()` includes it for every
target whose route touches `intermediary`; the lockfile pins it. Forge 1.13.2 has no intermediary and
uses the `lf:` rules (LF covers 1.13.2).

**P-09.** `loader:` references were "used as is", but a loader method's descriptor can name Minecraft
classes (`RenderLevelStageEvent#getPoseStack()` returns `PoseStack`, which is `MatrixStack` with an MCP
class name on 1.16.5 and `class_4587` nowhere, since Forge jars are not intermediary). Fix: the owner and
member name of a `loader:` reference are literal; Minecraft classes inside its descriptor are written in
Mojang anchor names and resolved like any class (`binding-format.md` §2.4 updated).

## 4. Lower (per target)

```
LOWER(scan, plan) → classes:
    remap = typeMap(plan)            // alias API type → runtime class; com/blanket/{api owned,runtime,adapters} → <base>/blanket/...
    out = []
    for cls in scan.modClasses + plan.overlay?.classes + reachable owned/runtime classes:     // P-20
        node = copy(cls)
        for method in node.methods:
            for insn in method:
                if apiMember(insn):
                    rule = plan.rules[key(insn)]
                    switch rule.kind:
                      invoke/get/put/const: replace(insn, single(rule, names))     // opcode from idx access flags
                      chain:                replace(insn, steps(rule))
                      adapter:              replace(insn, INVOKESTATIC relocated(adapter))
                      new:                  replace(insn, INVOKESTATIC bridge(node, rule))
                      unsupported:          mark(insn)                                 // judged after folding
                if featureSite(insn):  replace(prev+insn, ICONST(plan.features[F]))
                if overlaySite(insn):                                                  // P-01, P-07
                    impl = plan.overlayImpls[T]
                    replace(prev+insn, impl ? [NEW impl, DUP, INVOKESPECIAL impl.<init>()V] : [ACONST_NULL])
            rewriteHandles(method)          // invokedynamic → bridge when not a single matching invoke
            foldBranches(method); constPropLocals(method); removeDead(method)
            for insn still marked unsupported and reachable: err UNSUPPORTED(member, F, t, cls.method)
        node = ClassRemapper(node, remap)
        checkDuplicateSignatures(node)
        bytes = write(node, COMPUTE_FRAMES with idx.commonSuper)
        checkClassVersion(bytes, plan.javaMax)
        out += bytes
    out += remapAndRelocate(plan.adapters)
    return out
```

**P-07.** Folding `Blanket.overlay` to `new impl()` requires `impl` to have a public no-argument
constructor and to implement `T`; both are checked against the class index (the overlay jar is one of
its sources), so a wrong `provide(...)` line is a build error, not a `ClassCastException` in game.

**P-20.** The first overlay design said "Blanket does not lower overlays". But an overlay that calls
the mod (or implements an interface the mod declares) is compiled against the mod jar and
`blanket-api`, so its bytecode can contain API types and members. Making the author compile against
the *lowered* per-target mod jar would need two generate passes. Fix: overlay classes go through SCAN
and LOWER exactly like mod classes. Their own Minecraft references are already in runtime names and are
not touched (lowering only rewrites `com/blanket/` references). `build-and-gradle.md` §2.1 updated.

## 5. Wire (per target, `blanket-emit`)

```
WIRE(plan) → classes:
    e = emitterFor(plan.era)                  // fabric | neoforge | forge-legacy | forge-modern
    out = [ e.entryClass(plan) ]              // init sequence, architecture §3.4
    for ev in plan.events:
        recipe = plan.recipe(ev)
        out += recipe.kind == MIXIN  ? mixinClass(ev, recipe.hook, names)        // require = 1, static call to Dispatch
             : recipe.kind == NATIVE ? e.listener(ev, recipe.eventClass)         // @SubscribeEvent / addListener / EventBus 7
             : coremod(ev, recipe)                                               // legacy Forge only, D-014
    if any MIXIN: out += mixinConfig(plan)
    out += e.keyRegistration(plan)            // only if KeyBinding.register is used
    return out

forgeModern.entryClass(plan):
    @Mod(modid) class Entry {
        Entry(<ctor params per era>) {
            if (FMLEnvironment.dist != CLIENT) return          // P-10
            Runtime.init(modid); registerListeners(); mod = new <entrypoint>(); mod.init(); closeKeyWindow()
        }
    }
```

**P-10.** Forge 1.13.2–1.20.4 has no `@Mod(dist=...)` and early versions no `clientSideOnly`, so a
client-only mod installed on a dedicated server would load and crash on client classes. Every
`forge-modern` and `neoforge` entry returns early unless `FMLEnvironment.dist == CLIENT`
(`loader-emitters.md` §2.4), and the listener class is registered only after that check.

**P-11.** On Forge before 1.15.2 there is no Mixin at all; on Forge 1.15.2–1.20.4 it is bundled but
needs the `MixinConfigs` manifest entry. Since every v1 recipe on these eras is native
(`events-and-hooks.md` §4.9), WIRE emits no Mixin for them; if a future event needs one, the recipe
validator rejects `MIXIN` on `forge-srg` below 1.15.2 at bindings-build time (C7).

## 6. Shrink (per target)

```
SHRINK(classes, plan):
    roots = wired classes + mod classes + overlay classes
    keep  = plan.closure (from RESOLVE 3a) ∪ reach(roots over runtime+adapters+shaded libs)
    return classes ∩ keep
```

Reuses the RESOLVE closure (P-03). Overlay classes are roots and never removed.

## 7. Emit (per target, `blanket-emit`)

```
EMIT(classes, plan, config):
    checkLicense(config.license)                            // D-038: fail ARR/missing, warn non-GPL-compatible
    jar = SortedJar(fixedTime = 1980-02-01)
    jar += classes
    jar += plan.era.emitter.metadata(config, plan)          // fabric.mod.json merges overlay fabricEntrypoints
    jar += lang(config.lang, plan.era.langFormat) + icon + bakedAssets
    jar += overlay?.resources (minus metadata files)        // path clash with anything above → !error
    jar += licenses(shaded libs) + blanketLicense() + "Built with Blanket by e-the-bee" line
    assert no "com/blanket/" reference; every class ≤ plan.javaMax
    write("<modid>-<version>+<mc>-<loader>.jar")
```

## 8. Runtime (in game, relocated)

### 8.1 Event dispatch

```
Dispatch.fireHudRender(partialTick):            // called by Mixin callback or native listener
    if listeners(HUD).empty: return
    frame = Frame.begin2D(glue.guiWidth(), glue.guiHeight(), glue.guiScale())
    payload.set(frame.canvas, partialTick)          // reused object
    for l in listeners(HUD): try l.on(payload) catch Throwable t: logThrottled(t)
    frame.end()                                     // immediate eras: execute now; deferred eras: queue for replay
```

### 8.2 Frame execution and backend choice

```
Frame.end():
    if backendChoice == BOTH: exec = glue.glActive() ? glExec : gpuExec      // 26.2+, per frame
    else exec = only backend
    exec.run(commands)                       // gl: inside the state guard; gpu: own render pass
    commands.clear()
```

**P-14.** 26.3 replaced GLFW with SDL3 (R-MAP-04), but GL entry points are still reached through LWJGL's
`GL*` classes as long as the game ships the LWJGL OpenGL module. That is **ASSUMED** for 26.3+ and is
checked by the class index like every external reference (a missing `org/lwjgl/opengl/GL11` fails the
build for that target and moves it to `gpu`-only).

### 8.3 World panel

```
Draw3D.panel(p, painter):
    M = translate(p.centre - camera) * facingRotation(p.facing, p.rotation, camera) * scale(1 / p.ppb)
    sub = Canvas2D(width = p.w * p.ppb, height = p.h * p.ppb, transform = M, clipMode = SHADER)
    painter.paint(sub)
    cmds += [ DEPTH_TEST on, DEPTH_WRITE off ] + sub.commands
    cmds += [ COLOR_MASK off, DEPTH_WRITE on, quad(p) , COLOR_MASK on ]   // one depth write for the whole panel
    p.lastFrame = (M, cameraPos, cameraLook)                              // for cursor()

WorldPanel.cursor():
    ray = (lastFrame.cameraPos, lastFrame.cameraLook)
    hit = intersect(ray, plane(lastFrame.M)); if none or hit.distance > reach: return null
    tgt = Client.get().crosshairTarget()
    if tgt != null and tgt.kind() == BLOCK and dist(cameraPos, tgt.location()) < hit.distance: return null   // P-17
    (u, v) = toPanelUnits(hit); inside ? PanelCursor(u, v, hit.distance) : null
```

**P-17.** A block between the camera and the panel must hide the cursor. The crosshair target only
reaches the player's interaction range, so beyond it the check uses only the panel ray. This is
acceptable for v1 (panels further than reach still report a cursor through walls) and is documented on
`cursor()`.

**P-18.** The "colour mask off, depth write on" quad needs a pipeline with a colour write mask on the
`gpu` backend. Mojang's `RenderPipeline` is **ASSUMED** to expose one; folded into spike S-10.

**P-21.** Clicking while looking at a panel also attacked or used the block behind it, because v1 had
no way to cancel game input. Fixed by input routing (D-043, §8.5): an interactive panel under the
crosshair takes the click before the game.

### 8.5 Input routing

```
// called by the era's recipe; returns true when the game may see the input
Dispatch.fireInput(kind, key, button, pressed, scroll, ch):
    if screenOpen(): return true                               // screens route their own input
    if !pressed and swallowed.remove(id(kind, key, button)):   // release of a swallowed press   P-26
        return false
    ev = payload.set(kind, key, button, pressed, scroll, ch)
    captured = captures.anyMatch(ev)
    for l in listeners(INPUT): try l.on(ev) catch t: logThrottled(t)
    takenByPanel = !ev.isConsumed() and panels.route(ev)        // interactive panel under cursor / focused
    blocked = captured or ev.isConsumed() or takenByPanel
    if blocked and pressed: swallowed.add(id(kind, key, button))
    if blocked and noMixinEra: suppressBindingsAtNextTickStart(ev)   // Forge before 1.15.2
    return !blocked
```

**P-26.** Cancelling a press but not its release leaves the game with a release it never saw a press
for (harmless) or, worse, cancelling only the release leaves a key binding stuck down. The dispatcher
remembers swallowed presses and swallows their releases too; a capture that ends while a key is held
does not release-swallow, so the game sees the release and nothing sticks.

### 8.4 Widgets in a screen

```
ScreenHost.mouseClicked(x, y, b):
    for w in reverse(screen.widgets) if w.visible && w.enabled && w.contains(x, y):
        focus(w); if w.mouseClicked(x, y, b): return true
    return screen.mouseClicked(x, y, b)

ScreenHost.render(...):
    canvas = Frame.begin2D(...); screen.render(canvas, mx, my, pt)
    for w in screen.widgets if w.visible: w.render(canvas, mx, my)
    Frame.end()
```

**P-16.** Sliders and scroll lists need drag events, which the first `Screen` design did not forward.
`mouseDragged` added to `Screen` and to every era's `ScreenHost` (C6 checks the override exists).

### 8.6 Packets

```
onWorldJoin():                                           // wired only if Packets.* is used
    ch = glue.connectionChannel()
    ch.pipeline().addBefore("packet_handler", "blanket:" + modid, handler)

handler.channelRead(ctx, msg):                           // network thread
    if dispatchPackets(IN, msg): ctx.fireChannelRead(msg)        // false = cancelled
handler.write(ctx, msg, promise):
    if dispatchPackets(OUT, msg): ctx.write(msg, promise) else promise.setSuccess()   // P-28

dispatchPackets(dir, msg):
    for (type, listeners) in PacketClassifier.match(dir, msg):   // generated: instanceof per used type
        view = type.view(msg)                                     // alias: the packet itself; owned: adapter
        ctl  = control.reset()
        for l in listeners: try l.on(view, ctl) catch t: logThrottled(t)
        if ctl.isCancelled(): return false
    return true
```

**P-27.** Packet handlers run on Netty's thread, but every other API member is client-thread only.
Running listeners on the client thread would make cancelling impossible (the decision must be made
before the packet moves on). Fix: listeners run on the network thread with a narrow contract
(`api-surface.md` §1 rule 7) and `Client.runOnClientThread` for everything else.

**P-28.** A cancelled outbound packet must still complete its write promise, or code waiting on it
(disconnect handling, flush listeners) hangs. The handler calls `promise.setSuccess()` when cancelling.

## 9. Build, release and matrix tooling

```
updateTargets():                                   // maintainer task, result committed
    fabric   = fabricMeta.gameVersions(stable) ∩ intermediaryExists ∪ {26.x releases}
    lfabric  = legacyFabricMeta.gameVersions(stable) ∩ [1.8.9, 1.13.2]
    forge    = forgeMaven.versions → group by mc → pick recommended else latest      // P-23
    neoforge = neoforgeMaven.versions ∩ {1.20.1, 1.21.1, 1.21.11, newest 26.x}
    for (loader, mc) in all: era = loaders[loader].firstMatch(mc) ?: skip with warning
    write targets.json { versions[mc].java from Mojang JSON, loaderBuild[loader][mc] }
```

**P-23.** "Every Forge release" needs a concrete loader build per Minecraft version (for the
`loaderVersion` range in `mods.toml`, for provisioning test instances, and for the class index). The
matrix generator records the recommended build (or latest, if none is recommended) per version;
`blanket.lock` pins it for a mod build.

**P-19.** Fork pull requests cannot read the deploy key that fetches the private `blanket-emit`
module, so they run L1, L2 and L3 only; L4 runs after merge. Acceptable until 1.0 (D-035).

**P-25.** Your Q2 answer ("full functionality, a couple of bugs") and the old M9 gate ("two
consecutive full-matrix runs green") conflict. M9's gate is now: tier 1 green twice in a row, tier 2
at least 95% green, every failing target listed as a known issue in the release notes and the support
report (risk R-31).

## 10. Problems found

Numbers P-02, P-06, P-08, P-12, P-13, P-15, P-22 and P-24 are unused (numbering gaps); every P-nn in
the text above is in this table.

| Id | Problem | Fixed in |
|---|---|---|
| P-01 | `Blanket.overlay` needs a constant to fold | `api-surface.md` §3.11, §2 here |
| P-03 | Glue was resolved before reachability was known | §3a here; `architecture.md` §3.2 note |
| P-04 | TSRG/ProGuard descriptors are in different namespaces | §3.1 here |
| P-05 | Forge 1.14–1.20.4 targets need Fabric intermediary data | §3.1 here; downloads per route |
| P-07 | Wrong overlay `provide` would fail in game | §4 here (index check) |
| P-09 | `loader:` descriptors name Minecraft classes | `binding-format.md` §2.4 |
| P-10 | Client-only mod loaded on a dedicated server (Forge 1.13.2–1.20.4) | `loader-emitters.md` §2.4, §5 here |
| P-11 | No Mixin on early modern Forge | `events-and-hooks.md` §4.9 (native only), C7 rule |
| P-14 | GL through LWJGL on SDL3 releases unproven | class-index check; ASSUMED |
| P-16 | Widgets need drag events | `api-surface.md` §3.10, `lowering-and-adapters.md` §5.3 |
| P-17 | Panel cursor through walls beyond reach | documented limit |
| P-18 | Depth-only quad on the `gpu` backend | spike S-10 |
| P-19 | Fork PRs cannot build the private emitter | CI policy, D-035 |
| P-20 | Overlays can contain API references | `build-and-gradle.md` §2.1 (overlays are lowered) |
| P-21 | Panel clicks also hit the world | input routing, D-043, §8.5 |
| P-26 | Half-cancelled press/release pairs leave keys stuck | §8.5 (swallow matching release) |
| P-27 | Packet listeners cannot run on the client thread and still cancel | network-thread contract, `runOnClientThread` |
| P-28 | Cancelled outbound packets left write promises pending | §8.6 |
| P-23 | "Every Forge release" needs a pinned build per version | `updateTargets`, lockfile |
| P-25 | 1.0 gate contradicted "ship with a couple of bugs" | `milestones.md` M9 |
