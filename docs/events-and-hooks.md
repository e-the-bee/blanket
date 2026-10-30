# Events and hooks

How each v1 API event ([api-surface.md](api-surface.md) §3.12) becomes code on each loader-era: either
a listener on the loader's native event, or a generated Mixin injecting into vanilla code. Facts
marked *verified* cite [research-log.md](research-log.md) (R-EVT-*); anything else is labelled
**ASSUMED** and has a spike in [risks.md](risks.md).

## 1. Principles

1. **Native event first where the loader is the authority over that code path.** On Forge and
   NeoForge, loader patches wrap vanilla rendering and GUI code; injecting into vanilla next to them
   can miss or double-fire. Example (verified, R-EVT-07): Forge 1.8.9's `GuiIngameForge.renderGameOverlay(float)`
   overrides vanilla `GuiIngame.renderGameOverlay(float)` with the same signature, so a Mixin on the vanilla
   method may never run under Forge.
2. **Generated Mixin on Fabric and Legacy Fabric.** Fabric Loader bundles Mixin; no Fabric API
   dependency is allowed (decision D-008). Fabric API's own mixins for each version branch are the
   reference for *where* to inject, because they are maintained against every release (R-EVT-10).
3. **Draw where the GPU is actually drawing.** From 1.21.6, GUI code records into a `GuiRenderState`
   and draws later; from 1.21.9, world rendering is split into submit and render phases (verified,
   R-EVT-12, R-EVT-13). Hooks for `HUD_RENDER`, `WORLD_RENDER` and mod `Screen` rendering are chosen so
   that Blanket's own GL calls run when Minecraft is drawing, not while it is recording (§4.3, §4.4).
4. **One semantics on every target.** Where loaders disagree (for example Legacy Fabric API's "client
   tick" fires every frame, verified R-EVT-08), Blanket picks its own hook so `CLIENT_TICK_*` means
   "once per game tick (20 per second)" everywhere.
5. **Wire only what is used.** The scanner records which `Events.X` fields the mod reads; only those
   recipes are emitted. A mod that never subscribes to `WORLD_RENDER` gets no world-render Mixin.

## 2. Runtime side

The embedded runtime contains, per event, a static dispatcher (`<base>.blanket.runtime.events.Dispatch`)
with `fireClientTickStart()`, `fireHudRender(float partialTick)`, and so on. Generated code (Mixin
callback or native listener) does nothing except extract the payload values the recipe names and call
the dispatcher. The dispatcher:

- builds the payload object (reused per frame, not allocated per call);
- calls listeners in registration order;
- catches `Throwable` per listener, logs `"<modid>: listener for <EVENT> failed"` with the stack trace
  once per listener per 60 seconds, and continues with the next listener;
- for render events, opens and closes the render backend's frame (§4.3, §4.4) around the listeners.

The bus is ~100 lines of Java 8 in `blanket-runtime`, replacing v1's vendored Orbit
([reuse-from-v1.md](reuse-from-v1.md)).

## 3. Generated Mixin rules

- Mixin classes are emitted as bytecode by the generator, already in the target's runtime names, so
  no refmap is needed (modern Loom does the same: it remaps Mixin targets in place and does not run the
  annotation processor, R-PA-06).
- Every injector has `require = 1` and the config sets `"injectors": { "defaultRequire": 1 }` (kept from
  v1's mixin config). A hook that cannot attach fails at game start with Mixin's error, which names the
  target method; the generated mixin class is named `<base>.blanket.generated.mixin.<Event>$<HookId>` so
  the error also names the Blanket event.
- Callbacks are `static` calls into the dispatcher. Mixins never hold state.
- Injectors are `@Inject`. Recipes that can stop vanilla code (`INPUT`, §4.10) use
  `cancellable = true` and call `ci.cancel()` when the dispatcher returns "consumed". The one exception
  is legacy Fabric's LWJGL 2 input loop, which needs a `@Redirect` (§4.10).
- `at` values come from `hooks.json` ([binding-format.md](binding-format.md) §7): `HEAD`, `TAIL`,
  `RETURN`, or an `INVOKE` of a named method with `shift` and `ordinal`. Raw offsets are not expressible
  (v1 rule kept: they break silently).
- Before writing a Mixin, the generator verifies against the class index that the target method
  exists and, for `INVOKE` points, that the method body contains exactly the required number of matching
  call instructions. This turns most "fails at game start" cases into build errors.

## 4. Recipes per event and loader-era

Loader-eras used below are defined in [loader-emitters.md](loader-emitters.md) §1. Vanilla names are
Mojang names (1.14.4+), Legacy Yarn names (≤1.13.2) with LF ids.

### 4.1 `CLIENT_TICK_START`, `CLIENT_TICK_END`

| Loader-era | Recipe | Facts |
|---|---|---|
| Fabric 1.14–1.21.11, 26.x | Mixin `Minecraft#tick()V`, `HEAD` (start) / `RETURN` (end) | Fabric API lifecycle mixins on 1.16, 1.20.1, 1.21.1, 1.21.4, 1.21.6, 1.21.11, 26.1, 26.4 inject `tick` HEAD/RETURN (verified R-EVT-10) |
| Legacy Fabric 1.8.9–1.12.2 | Mixin `MinecraftClient#tick()V` = `lf:class_1600#method_2954`, `HEAD`/`RETURN` | Legacy Fabric API hooks `runGameLoop` (per frame) instead (verified R-EVT-08); Blanket deliberately differs to keep tick semantics. `method_2954` is the same id in 1.8.9 and 1.12.2 (verified R-MAP-13). |
| NeoForge 1.20.2–1.20.4 | native `TickEvent$ClientTickEvent` with phase filter on `NeoForge.EVENT_BUS` | verified for 1.20.4 (R-EVT-04) |
| NeoForge 1.20.5–1.21.11, 26.x | native `net.neoforged.neoforge.client.event.ClientTickEvent$Pre` / `$Post` on `NeoForge.EVENT_BUS` | verified for 1.21.1 and 26.3.x (R-EVT-04); the switch point between 1.20.4 and 1.21.1 is **ASSUMED** to be 1.20.5 (S-17) |
| Forge 1.20.6–1.21.11, 26.x (M7) | native `TickEvent$ClientTickEvent$Post` through its static `BUS` from 1.21.6 (EventBus 7); `IEventBus.addListener` before | verified for 1.21.11 (R-EVT-06); 1.20.6–1.21.5 form **ASSUMED** (S-11) |
| Forge 1.8.9–1.12.2 | native `net.minecraftforge.fml.common.gameevent.TickEvent$ClientTickEvent`, filter `phase == START` / `END`, on `MinecraftForge.EVENT_BUS` | verified; the FML bus *is* `MinecraftForge.EVENT_BUS` there (R-EVT-01) |

### 4.2 `WORLD_JOIN`, `WORLD_LEAVE`

Not a hook. The runtime compares `Client.world()` with its previous value at `CLIENT_TICK_START` and
fires `WORLD_LEAVE` (old non-null) then `WORLD_JOIN` (new non-null). Reasons: it is identical on every
target; it always runs on the client thread (Forge 1.8.9's `FMLNetworkEvent.ClientConnectedToServerEvent`
is posted from the network dispatcher, R-EVT-03, and its thread was not verified; this design does not depend on it); and it fires when the world object exists,
which is when a mod can use it. Cost: up to one tick (50 ms) of latency. Recorded as D-015.

### 4.3 `HUD_RENDER`

Semantics on every target: fires once per frame after the vanilla HUD, **only while no screen is
open**, with a `Canvas2D` in GUI-scaled pixels. (Vanilla draws the HUD under open screens on some
versions and blurs it on others; not firing while a screen is open gives one behaviour everywhere.
Mods draw inside their own screens through `Screen.render`.)

| Loader-era | Where listeners run | Where Blanket's GL commands execute |
|---|---|---|
| Legacy Fabric 1.8.9–1.12.2 | Mixin `InGameHud#render(F)V` (`lf:class_371#method_9420`), `TAIL` | immediately (fixed-function context, R-GFX-02) |
| Forge 1.8.9–1.12.2 | native `RenderGameOverlayEvent$Post` with `type == ElementType.ALL` (verified R-EVT-02) | immediately |
| Fabric 1.14–1.19.4 (M7) | Mixin `Gui#render(PoseStack, float)` at `INVOKE` of the colour reset after the player list, as Fabric API does on 1.16 | verified for 1.16.5 (R-EVT-10); 1.14–1.15.2 and 1.17–1.19.4 **ASSUMED** same shape (S-17) |
| Fabric 1.20.1–1.21.5 | Mixin `Gui#render(GuiGraphics, …)`, `TAIL` (Fabric API uses TAIL from 1.20.1, verified R-EVT-10; signature per era in binding example 11) | immediately, after `GuiGraphics.flush()` so vanilla's batched quads are drawn first (glue binding `GlueGui#flush`) |
| NeoForge 1.20.2–1.21.5 | native `RenderGuiEvent$Post` (verified for 1.20.4 and 1.21.1, R-EVT-04) | as Fabric |
| Forge 1.20.6–1.21.11, 26.x (M7) | native `AddGuiOverlayLayersEvent` → `ForgeLayeredDraw` layer on 1.21.6+ (verified for 1.21.11, R-EVT-06); `RenderGuiEvent$Post` before (**ASSUMED**, S-11) | immediate before 1.21.6, deferred after |
| Fabric 1.21.6–1.21.11, 26.x | Mixin at the HUD extraction point (`Gui#render` TAIL on 1.21.6–1.21.11; `Gui#extractRenderState` on 26.1; `Hud#extractRenderState` on 26.2+, verified R-EVT-10/11) | **deferred**: the `Canvas2D` records commands; they are replayed by a second Mixin right after `GuiRenderer#render` returns inside `GameRenderer#render` (**ASSUMED** call site, R-EVT-14, spike S-08) |
| NeoForge 1.21.6–1.21.11, 26.x | native `RenderGuiEvent$Post` (fires during extraction on 26.3.x, verified R-EVT-04) | deferred, replayed in native `RenderFrameEvent$Post` ("after the current frame is rendered via GameRenderer#render", verified R-EVT-05) |

Why record and replay rather than picture-in-picture: PiP renderers (Fabric `SpecialGuiElementRegistry`
1.21.6–1.21.11 and `PictureInPictureRendererRegistry` 26.1+, NeoForge `RegisterPictureInPictureRenderersEvent`,
verified R-EVT-15) give correct layering with vanilla GUI elements, but their API changed twice in four
releases and they are a Fabric API feature on Fabric (not allowed, D-008). Record and replay needs only
two hook points. The cost: HUD content drawn by a mod is on top of all vanilla GUI, including toasts and
the chat overlay. Logged as risk R-08 with PiP as the fallback.

Among the core six ([mappings-and-versions.md](mappings-and-versions.md) §7) only `fabric:26.1.2` is
deferred; its replay point is retired in spike S-08 during M0. The 1.0 matrix adds every deferred
release from 1.21.6 on, each checked by C7 and L4 (M7).

### 4.4 `WORLD_RENDER`

Semantics: fires once per frame after opaque and translucent world geometry, before the hand and
the GUI, with a `Draw3D` whose origin is the camera (camera-relative coordinates) and the frame's
partial tick.

**Depth contract (D-040).** When the event fires, the bound framebuffer's depth buffer contains terrain
*and* entities (players, mobs, armor stands, item frames) for this frame. That is what lets world-space
drawing be hidden by, and hide, blocks and entities. Every recipe below must meet it; the M6 gate and
spike S-20 check it with an armor stand in front of a panel. On eras with the 1.21.9+ submit/render
split, the hook must sit after entity *rendering*, not after entity *submission*.

| Loader-era | Recipe | Facts |
|---|---|---|
| Legacy Fabric 1.8.9–1.12.2 | Mixin `GameRenderer#renderWorld(IFJ)V` (`lf:class_524#method_9776`) at `INVOKE_STRING` `Profiler#swap(String)` with `ldc = "hand"` | this is exactly where Forge's patch calls `dispatchRenderLast` (verified R-EVT-09) |
| Forge 1.8.9–1.12.2 | native `RenderWorldLastEvent` | verified R-EVT-02 |
| Fabric 1.16.5–1.21.1 | Mixin `LevelRenderer#renderLevel`, at `INVOKE renderChunkDebugInfo(…)` (Fabric API's `LAST` point) | verified for 1.16.5, 1.20.1, 1.21.1 (R-EVT-10); 1.14–1.15.2 not verified and not planned |
| Fabric 1.21.4–1.21.11 | Mixin on the main-pass lambda (`intermediary:method_62214`), `INVOKE:LAST BufferSource#endBatch()V` (1.21.11) / `RETURN` (1.21.4) | verified R-EVT-10 |
| Fabric 26.x | Mixin `official:LevelRenderer#lambda$addMainPass$0`, `RETURN` (Fabric API's `END_MAIN`, whose javadoc says drawing events support OpenGL calls) | verified R-EVT-10, R-EVT-13. Targeting a javac lambda name is fragile; covered by check C7 per version |
| NeoForge 1.20.2–1.21.11 | native `RenderLevelStageEvent`, `stage == AFTER_LEVEL` (gives model-view and projection matrices) | verified for 1.20.4 and 1.21.1 (R-EVT-04) |
| Forge 1.20.6–1.21.1 / 1.21.4+ (M7) | native `RenderLevelStageEvent` up to 1.21.1; from 1.21.4 only `AddFramePassEvent` (a frame-graph pass), which needs its own recipe kind | verified R-EVT-06; recipe design in M7 (S-11) |
| NeoForge 26.x | native `RenderLevelStageEvent$AfterLevel` | verified R-EVT-04 |

Matrices and camera position come from glue bindings per era ([rendering-api.md](rendering-api.md)
§4.2): from the native event where it carries them, otherwise from `RenderSystem`/`Camera` accessors
read inside the hook.

### 4.5 `SCREEN_OPEN`

| Loader-era | Recipe | Facts |
|---|---|---|
| Fabric 1.14–26.1 | Mixin `Minecraft#setScreen(Screen)V`, `HEAD`; payload `{"arg": 1}` | verified R-EVT-10 |
| Fabric 26.2+ | Mixin `official:net.minecraft.client.gui.Gui#setScreen(...)`, `HEAD` | `setScreen` moved to `Gui` (verified R-EVT-11) |
| Legacy Fabric 1.8.9–1.12.2 | Mixin `MinecraftClient#setScreen` (`lf:class_1600#method_2928`), `HEAD` | verified R-MAP-13 |
| NeoForge | native `ScreenEvent$Opening` (`getNewScreen()`) | verified R-EVT-04 |
| Forge 1.20.6+ (M7) | native `ScreenEvent$Opening` (static `BUS` from 1.21.6) | verified for 1.21.11 (R-EVT-06) |
| Forge 1.8.9–1.12.2 | native `GuiOpenEvent` (`gui` field on 1.8.9, `getGui()` on 1.12.2) | verified R-EVT-02 |

An adapter classifies the screen object into `ScreenKind` with `instanceof` checks against the era's
title, pause, chat, inventory and container screen classes; `isModScreen()` is `instanceof` the era's
`ScreenHost`. `null` gives `ScreenKind.NONE`. Cancelling or replacing the screen is not in v1.

### 4.6 Mod screens (`Screen.render`)

Not an event, but it has the same timing problem. `ScreenHost.render` (adapter,
[lowering-and-adapters.md](lowering-and-adapters.md) §5.3) calls the mod's `render(Canvas2D, …)`. Up to
1.21.5 the canvas draws immediately; from 1.21.6 it records and is replayed at the same point as the HUD
(§4.3), which is correct because an open screen is the topmost GUI layer.

### 4.7 Key binding registration (`KeyBinding.register`)

Registration must happen before the game builds its key list. `register` may only be called inside
`ClientMod.init()`; the runtime queues the bindings and a per-loader recipe adds them:

| Loader-era | Recipe | Status |
|---|---|---|
| Fabric 1.14–26.x, Legacy Fabric | Mixin on the options class (`Options` / `GameOptions`) at the point where `keyMappings` is assigned, appending the queued bindings to the array | **ASSUMED** (Fabric API's `KeyBindingRegistryImpl` does this; target method per era to verify, spike S-07) |
| NeoForge | native `RegisterKeyMappingsEvent` on the mod bus | **ASSUMED** (S-07) |
| Forge 1.8.9–1.12.2 | call `net.minecraftforge.fml.client.registry.ClientRegistry.registerKeyBinding(KeyBinding)` from the generated entry class | **ASSUMED** (S-07) |

On every target, `init()` runs before the options are loaded for the Fabric recipe to work. Where the
entry point runs later than options creation, the recipe must instead also reload the binding's saved
key from `options.txt`; S-07 settles which case each loader-era is in.

### 4.9 Forge 1.13.2–1.20.4 and NeoForge 1.20.1 (SRG eras, D-033)

All native events; no Mixin on these eras (Forge before 1.15.2 bundles none). Every row is
**ASSUMED** from the Forge event history and is checked per era by spike S-18 and check C7 (a native
event class missing from the loader jar fails the build).

| Event | 1.13.2 – 1.16.5 | 1.17 – 1.18.2 | 1.19 – 1.20.4, NeoForge 1.20.1 |
|---|---|---|---|
| `CLIENT_TICK_*` | `TickEvent.ClientTickEvent`, phase `START`/`END` | same | same |
| `HUD_RENDER` | `RenderGameOverlayEvent.Post`, element `ALL` | same (`ALL` until 1.18.2) | `RenderGuiEvent.Post` |
| `WORLD_RENDER` | `RenderWorldLastEvent` | `RenderLevelLastEvent` (1.18) | `RenderLevelStageEvent`, stage `AFTER_TRANSLUCENT_BLOCKS` (chosen for the depth contract) |
| `SCREEN_OPEN` | `GuiOpenEvent` | `ScreenOpenEvent` | `ScreenEvent.Opening` |
| `WORLD_JOIN`/`LEAVE` | `ClientPlayerNetworkEvent.LoggedInEvent`/`LoggedOutEvent` | same | `ClientPlayerNetworkEvent.LoggingIn`/`LoggingOut` |
| Key registration | `ClientRegistry.registerKeyBinding` in client setup | same | `RegisterKeyMappingsEvent` (mod bus) |

### 4.10 `INPUT` (D-043)

Semantics: [api-surface.md](api-surface.md) §3.15. The recipe hands each raw input to
`Dispatch.fireInput(...)`, which runs captures, listeners and panels and returns whether the game may
see it. Every row is **ASSUMED** and checked by spike S-21; check C7 verifies each hook per version.

| Loader-era | Keys / characters | Mouse buttons / scroll |
|---|---|---|
| Fabric 1.14–1.21.11, 26.x; Legacy Fabric 1.13.2 | Mixin `KeyboardHandler#keyPress` and `#charTyped`, `HEAD`, cancellable, only when `minecraft.screen == null` (26.x: the methods take event records) | Mixin `MouseHandler#onPress` and `#onScroll`, `HEAD`, cancellable, same condition |
| Legacy Fabric 1.8.9–1.12.2 | `@Redirect` of `Keyboard.next()` in `MinecraftClient#tick`: returns the next event the dispatcher lets through, skipping consumed ones | same for `Mouse.next()` |
| NeoForge 1.20.2+, 26.x | Mixin as Fabric (no cancelable native key event) | native `InputEvent.MouseButton.Pre`, `InputEvent.MouseScrollingEvent` (cancelable) |
| Forge 1.15.2–1.20.4, NeoForge 1.20.1, Forge 1.20.6+ | Mixin as Fabric (Mixin bundled; `MixinConfigs`, S-11) | native `InputEvent.RawMouseEvent`, `InputEvent.MouseScrollEvent` (cancelable; renamed per era) |
| Forge 1.13.2–1.15.1 (no Mixin) | **key-binding suppression**: at `ClientTickEvent` START, every key binding whose key is captured or was consumed since the last tick is set not-down and its queued clicks are drained, before the game reads them | native cancelable mouse events as above |
| Forge 1.8.9–1.12.2 (no Mixin) | key-binding suppression, as above, at `ClientTickEvent` START | native `MouseEvent` (cancelable) |

Key-binding suppression blocks game *actions* bound to a key, not the raw key: vanilla code that
reads the keyboard directly (F3 combinations, the chat key on some versions) still sees it. It is the
documented behaviour on those eras and appears in the support report.

### 4.11 Packets (D-044)

One recipe kind, `pipeline`, for every loader-era, with no Mixin:

1. At `WORLD_JOIN` (play phase started), glue `GlueNet.channel()` returns the connection's Netty
   `Channel` (`Connection#channel` / `NetworkManager#channel`, bound per era).
2. The runtime adds a `ChannelDuplexHandler` named `blanket:<modid>` before the `packet_handler`
   handler (`addBefore`, thread-safe in Netty). `channelRead` sees decoded packet objects; `write`
   sees packet objects before encoding.
3. For each packet, a generated `PacketClassifier` checks `instanceof` against the runtime classes of
   the packet types the mod uses (from each type's `packet` block, [binding-format.md](binding-format.md)
   §4.6), builds or reuses the view, and calls the listeners. Cancel = do not pass the packet on.
4. At `WORLD_LEAVE` the handler removes itself if the channel is still open.

Netty classes are not obfuscated (`loader:` namespace). **ASSUMED** and checked by spike S-22: the
`packet_handler` handler name on every era, the integrated server's local channel having the same
handler layout, and the play phase starting before the first world packets on 1.20.2+ (configuration
phase).

## 5. Native listener code generation

| Loader-era | Generated form |
|---|---|
| Forge 1.8.9–1.12.2 | a class with public `@SubscribeEvent` methods, registered with `MinecraftForge.EVENT_BUS.register(new Listener())`; the bus has no `addListener` there (verified R-EVT-01) |
| NeoForge (all) | `NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, Event.class, consumer)` (explicit-class overload; verified R-EVT-06), consumer is a generated static method reference |
| Forge 1.21.6+ (EventBus 7, in 1.0 from M7) | `Event.BUS.addListener(consumer)` (verified R-EVT-06) |

The explicit-class overloads are used everywhere so registration never depends on generic type
inference from a lambda.

## 6. Failure reporting

| Failure | When | What the user sees |
|---|---|---|
| Hook target missing, `INVOKE` count wrong | `generate` | build error naming event, hook id, target, method |
| Native event class missing in the loader artifact | `generate` | build error naming event and loader version |
| Mixin cannot apply (a coremod or another mod changed the method) | game start | Mixin's `InjectionError` / `InvalidInjectionException`; message contains the generated class name `<Event>$<HookId>`, and Blanket's generated config sets `"required": true` so the game stops instead of running without the hook |
| Listener throws | runtime | one log line per listener per 60 s, game continues |

## 7. Threading

All v1 events fire on the client (render) thread on every target: tick and render hooks run on it by
construction, screen changes are called on it by vanilla, and world join/leave are synthesized at tick
start (§4.2). Listeners must not block; the API documents this. Nothing in the runtime is thread-safe
beyond what the client thread needs.

## 8. Adversarial cases

| Case | Resolution |
|---|---|
| Event with no hook on legacy Forge (for example a future "item tooltip" event) | Forge 1.8.9 has no bundled Mixin (R-PA-09). Options: embed a Mixin bootstrap (conflicts when several mods embed different versions, R-PA-09), depend on MixinBooter (a runtime dependency, against settled decision 1's spirit), or generate a Forge coremod (`IFMLLoadingPlugin` + `IClassTransformer` using LaunchWrapper's ASM). Decision D-014: generated coremod, HEAD/RETURN/INVOKE only, `require = 1` semantics implemented by counting matches and throwing at class load. Not needed by any v1 event. |
| Two Blanket mods hook the same method | Each mod has its own relocated mixin class; Mixin applies both. No shared state. |
| Another mod cancels the vanilla method at HEAD (Fabric) | Blanket's `TAIL`/`RETURN` injections do not run; same behaviour as Fabric API. Documented. |
| A Forge/NeoForge native event is cancelled by another mod before Blanket's listener | Blanket registers with `receiveCanceled = false` on NeoForge; the API events are not cancellable, so a cancelled vanilla action correctly produces no Blanket event. |
| Vulkan backend active (26.2+ with Vulkan chosen, 26.4 default) | No GL context: the `gl` backend checks the active backend at frame start and skips drawing with one warning; the `gpu` backend is planned for those targets ([rendering-api.md](rendering-api.md) §5). Event listeners still run. |
