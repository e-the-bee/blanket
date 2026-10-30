# API surface (v1)

This is the complete v1 API that mod authors compile against: artifact `com.blanket:blanket-api`,
Java 8 bytecode, package `com.blanket.api` (package name carried from v1, see D-004 in
[decisions.md](decisions.md)). Nothing outside this list exists in v1.

## 1. Rules

1. **A use case per addition.** The members below cite the v1 demand survey
   (`docs/design/capability-seed-survey.md` in v1, cited as *S§section row*) or a concrete need of
   another listed member. New additions need only a use case stated in the pull request (D-045); the
   path for adding anything is [contributing.md](contributing.md). Survey rows not yet added are
   listed in §6.
2. **No Minecraft types, loader types or version checks** in any signature (settled decision 2).
   Text is `String`, identifiers are `"namespace:path"` strings.
3. **Every member has a binding** for every target in the 1.0 matrix (D-034), or is guarded by a
   `Feature` ([binding-format.md](binding-format.md) §6). The binding file is named in the tables.
4. **Representation is per type**: `alias` (the generator rewrites the API type to a real Minecraft
   class; no wrapper object) or `owned` (a class in `blanket-runtime`, relocated into the mod's jar).
   The rule used to choose, applied in §2: alias when one Minecraft class exists on every target that
   can represent every API value without conversion; otherwise owned. A type may switch to owned on a
   range in its binding file (`representation` in [binding-format.md](binding-format.md) §4) if a
   target breaks the alias rule; none is known to on the 1.0 matrix; C1/C2 check every era in M7.
5. **Stub contract.** In `blanket-api`, alias types are `final` classes with a private constructor
   and methods whose bodies throw `AssertionError("blanket stub")`. Authors cannot subclass them or
   create them except through the listed static factories (no public constructors), which keeps every
   use lowerable ([lowering-and-adapters.md](lowering-and-adapters.md) §3). Owned types are real code.
6. **Nullability.** Members are non-null unless marked *nullable*. A nullable result is `null` on
   every target in the same situation (for example no world loaded).
7. **Threading.** Every API member is client (render) thread only, with one exception: packet
   listeners (§3.16) run on the network thread and may only read the packet view, cancel it, and call
   `Client.runOnClientThread`. Calling other members from another thread is undefined; the generator
   cannot detect it, so the rule is documented here and in the javadoc, and the `--testkit` build
   checks it at runtime.

## 2. Type list and representation

| Type | Kind | Representation | Real class on targets (Mojang name; legacy name for ≤1.12.2) | Binding file |
|---|---|---|---|---|
| `ClientMod` | interface the mod implements | owned | n/a | `types/ClientMod.json` |
| `Client` | handle | alias | `net.minecraft.client.Minecraft` | `types/Client.json` |
| `Player` | handle | alias | `net.minecraft.client.player.LocalPlayer` (1.8.9: `EntityPlayerSP`) | `types/Player.json` |
| `World` | handle | alias | `net.minecraft.client.multiplayer.ClientLevel` (1.8.9: `WorldClient`) | `types/World.json` |
| `BlockState` | value | alias | `net.minecraft.world.level.block.state.BlockState` (1.8.9: `IBlockState`, an interface) | `types/BlockState.json` |
| `ItemStack` | value | alias (with empty-stack normalization on ≤1.10.2) | `net.minecraft.world.item.ItemStack` | `types/ItemStack.json` |
| `BlockPos` | value | alias | `net.minecraft.core.BlockPos` (1.8.9: `net.minecraft.util.BlockPos`) | `types/BlockPos.json` |
| `Vec3` | value | alias | `net.minecraft.world.phys.Vec3` (1.8.9: `net.minecraft.util.Vec3`) | `types/Vec3.json` |
| `Box` | value | alias | `net.minecraft.world.phys.AABB` (1.8.9: `AxisAlignedBB`) | `types/Box.json` |
| `Direction` | enum | alias | `net.minecraft.core.Direction` (1.8.9: `EnumFacing`) | `types/Direction.json` |
| `KeyBinding` | handle | alias | `net.minecraft.client.KeyMapping` (1.8.9: `KeyBinding`) | `types/KeyBinding.json` |
| `BlockUpdate` (package `net`) | packet view | alias | `net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket` (1.8.9: `S23PacketBlockChange`) **A** | `types/BlockUpdate.json` |
| `ChatMessage`, `CustomPayload`, `AnyPacket` (package `net`) | packet views | owned + adapters | n/a (chat splits into system and player chat packets in 1.19; custom payloads change shape in 1.20.2 and 1.20.5) | `types/ChatMessage.json`, `types/CustomPayload.json` |
| `Packets`, `PacketType`, `PacketTypes`, `PacketListener`, `PacketControl` (package `net`) | packet plumbing | owned | n/a | `events/Packet.json` |
| `Target` | value | owned | n/a (built from `HitResult` / `MovingObjectPosition` by an adapter) | `types/Client.json` |
| `TargetKind` | enum | owned | n/a | — |
| `Key` | value | owned | n/a (key codes translated per target) | `types/Key.json` |
| `Input` | static | owned facade, members bound | n/a | `types/Input.json` |
| `Screen` | abstract class the mod extends | owned + generated host | host extends `net.minecraft.client.gui.screens.Screen` | `types/Screen.json` |
| `ScreenKind` | enum | owned | n/a | `events/ScreenOpen.json` |
| `Blanket` | static | owned, `supports` folded at build time | n/a | `types/Blanket.json` |
| `Feature` | enum | owned (constants only) | n/a | `features.json` |
| `Log` | static | owned, calls log4j-api | `org.apache.logging.log4j.Logger` (present in every target, R-MAP-05) | none (not Minecraft) |
| `Events`, `EventSource<E>`, `Listener<E>`, `Subscription` | event plumbing | owned | n/a | `events/*.json` |
| Event payloads `ClientTick`, `HudRender`, `WorldRender`, `ScreenOpen`, `WorldJoin`, `WorldLeave` | values | owned | n/a | `events/*.json` |
| `Canvas2D`, `Draw3D`, `Texture`, `Font`, `Mesh`, `MeshBuilder`, `Color`, `BlendMode`, `DepthMode`, `TextAlign`, `Primitive` (package `com.blanket.api.render`) | rendering | owned (backed by the `blanket-runtime` GL backend) | n/a | none; the backend's Minecraft access is in `glue/*.json` |

Why each alias holds: `Minecraft`, `LocalPlayer`/`EntityPlayerSP`, `ClientLevel`/`WorldClient`,
`BlockState`/`IBlockState`, `BlockPos`, `Vec3`, `AABB`, `Direction`/`EnumFacing`, `KeyMapping`/`KeyBinding`
and `ItemStack` exist on every target from 1.8.9 to 26.x ([mappings-and-versions.md](mappings-and-versions.md)
§6), and each API value maps to exactly one instance with no conversion. `ItemStack` needs one
fix-up: before 1.11 an empty slot is `null`, not an empty stack (§3.6).

Why each owned type is owned: `Target`
wraps `HitResult` (1.13+, a class hierarchy) and `MovingObjectPosition` (≤1.12.2, one class with a type
enum), which have different shapes; `Key` codes come from LWJGL 2 on ≤1.12.2, GLFW on 1.13 to 26.2,
and SDL on 26.3+ (R-MAP-04); `Screen` is subclassed by the mod, and an alias type cannot be subclassed
([lowering-and-adapters.md](lowering-and-adapters.md) §5.3); the rendering types are Blanket's own
(settled decision 4).

## 3. Members

Every row: signature, then demand citation, then the binding entry key in the binding file.

### 3.1 `ClientMod` (owned interface)

| Member | Demand | Notes |
|---|---|---|
| `void init()` | every mod needs an entry point (S§6 `client.minecraft`, 16/16 mods) | The generated loader entry class calls it once, on the client, after the loader's client init point ([loader-emitters.md](loader-emitters.md)). The mod class is named in the Gradle block (`mod.entrypoint`) and needs a public no-argument constructor. |

### 3.2 `Client` (alias → `Minecraft`)

| Member | Demand | Binding key |
|---|---|---|
| `static Client get()` | S§6 `client.minecraft` (16/16) | `get()` |
| `Player player()` *nullable: no world* | S§1 `player.localPlayer` (4), `player.entity` (8) | `player()` |
| `World world()` *nullable: no world* | S§2 `world.clientLevel` (6), `world.level` (7) | `world()` |
| `int guiWidth()`, `int guiHeight()` | needed by `HudRender` users to place elements; S§3 `render.gui` (3) | `guiWidth()`, `guiHeight()` |
| `double guiScale()` | as above | `guiScale()` |
| `void openScreen(Screen screen)` | S§3 `render.screen` (10, highest count in the survey) | `openScreen(Screen)` |
| `void closeScreen()` | S§3 `render.screen` | `closeScreen()` |
| `boolean isScreenOpen()` | S§3 `render.screen`; seed `hasScreenOpen` | `isScreenOpen()` |
| `Target crosshairTarget()` *nullable: no world* | S§1 `player.hitResult` (2, MEDIUM) | `crosshairTarget()` |
| `void showMessage(String text)` | S§6 `client.component` (8, HIGH); local chat feedback is the minimal use | `showMessage(String)` |
| `static void Client.runOnClientThread(Runnable task)` (callable from any thread; runs at the next client tick, or immediately when already on it) | packet listeners (§3.16) | `runOnClientThread(Runnable)` |
| `String translate(String key, Object... args)` | S§6 `client.i18n` (3, MEDIUM) | `translate(String,Object[])` |

### 3.3 `Player` (alias → `LocalPlayer`)

| Member | Demand | Binding key |
|---|---|---|
| `double x()`, `double y()`, `double z()` | S§1 `player.entity` (8) | `x()`, `y()`, `z()` |
| `Vec3 position()` | S§2 `world.vec3` (4) | `position()` |
| `float yaw()`, `float pitch()` | S§1 `player.entity`; seed §3.1 rotation | `yaw()`, `pitch()` |
| `boolean onGround()` | seed §3.1 (S§5 move-packet onGround flag) | `onGround()` |
| `float health()`, `float maxHealth()` | S§1 `player.entity`; seed §3.1 | `health()`, `maxHealth()` |
| `int foodLevel()`, `float saturation()` | S§1 `player.food` (2, MEDIUM; AppleSkin core use) | `foodLevel()`, `saturation()` |
| `ItemStack mainHandItem()` | S§1 `player.hand` (2), `player.inventory` (5) | `mainHandItem()` |
| `ItemStack offHandItem()` (needs `Feature.OFFHAND`) | S§1 `player.hand` (2; Wurst7, Meteor use `InteractionHand`) | `offHandItem()` |

The two hands are separate members, not a `Hand` argument, so the off-hand feature guard applies to a
member the generator can see (D-027).
| `int selectedSlot()` | S§1 `player.inventory` (5) | `selectedSlot()` |
| `ItemStack inventoryItem(int slot)` (0–35, main inventory; out of range throws `IndexOutOfBoundsException` on every target) | S§1 `player.inventory` (5) | `inventoryItem(int)` |
| `Box boundingBox()` | S§2 `world.aabb` (3; Wurst7 entity boxes) | `boundingBox()` |

### 3.4 `World` (alias → `ClientLevel`)

| Member | Demand | Binding key |
|---|---|---|
| `BlockState blockState(BlockPos pos)` | S§2 `world.blockState` (8) | `blockState(BlockPos)` |
| `long gameTime()` | v1 shipped `IWorld.getTime()`; S§2 `world.level` (7) | `gameTime()` |
| `String dimension()` (`"minecraft:overworld"` etc. on every target; 1.8.9's numeric ids are translated) | S§2 `world.level` (7) | `dimension()` |

### 3.5 `BlockState` (alias)

| Member | Demand | Binding key |
|---|---|---|
| `String blockId()` (`"minecraft:stone"`) | S§2 `world.block` (7), `world.registry` (4) | `blockId()` |
| `boolean isAir()` | S§2 `world.blockState` (8) | `isAir()` |
| `String propertyValue(String name)` *nullable: property absent* (`"north"`, `"true"`, `"3"`) | S§2 `world.blockState` (8; Litematica, Continuity read properties) | `propertyValue(String)` |

On 1.8.9, block states exist (`IBlockState`), but some blocks still carry part of their state in
metadata. `propertyValue` reports what `IBlockState.getProperties()` reports; a binding note records
this ([binding-format.md](binding-format.md) §8, example 9).

### 3.6 `ItemStack` (alias)

| Member | Demand | Binding key |
|---|---|---|
| `boolean isEmpty()` | S§6 `client.itemStack` (7) | `isEmpty()` |
| `int count()` | S§6 `client.itemStack` | `count()` |
| `String itemId()` (`"minecraft:air"` when empty) | S§6 `client.itemStack`; S§2 `world.registry` | `itemId()` |
| `String displayName()` | S§6 `client.itemStack`, `client.component` | `displayName()` |

**Empty normalization (≤1.10.2).** Before `ItemStack.EMPTY` existed, Minecraft uses `null` for an empty
slot. API methods never return `null` for `ItemStack`, so on those targets every binding that returns
an `ItemStack` goes through an adapter that substitutes a per-jar empty sentinel, and every `ItemStack`
member is lowered to an adapter that treats the sentinel and `null` as empty. Mod code that compares a
stack with `null` is a bug on every target; the generator warns when it sees `IFNULL`/`IFNONNULL` on a
value typed `ItemStack` ([lowering-and-adapters.md](lowering-and-adapters.md) §4.4).

### 3.7 Value types `BlockPos`, `Vec3`, `Box`, `Direction` (alias)

| Member | Demand | Binding key |
|---|---|---|
| `static BlockPos BlockPos.of(int x, int y, int z)`; `int x()`, `y()`, `z()`; `BlockPos offset(Direction d)`; `BlockPos offset(int dx, int dy, int dz)` | S§2 `world.blockPos` (8) | same names |
| `static Vec3 Vec3.of(double x, double y, double z)`; `double x()`, `y()`, `z()`; `Vec3 add(Vec3)`; `Vec3 subtract(Vec3)`; `Vec3 scale(double)`; `double length()`; `double distanceTo(Vec3)` | S§2 `world.vec3` (4) | same names |
| `static Box Box.of(double minX, double minY, double minZ, double maxX, double maxY, double maxZ)`; `double minX()` … `maxZ()`; `Box inflate(double)`; `Vec3 center()` | S§2 `world.aabb` (3) | same names |
| `Direction` constants `DOWN, UP, NORTH, SOUTH, WEST, EAST`; `Direction opposite()`; `values()`, `ordinal()`, `name()` (inherited enum behaviour) | S§2 `world.direction` (6) | constants by name; `opposite()` |

`Direction` is an API `enum` aliased to Minecraft's enum. This works because `name()` returns the
constructor's string literal (`"NORTH"`), which obfuscation does not touch, and `switch` on the enum
compiles to `values()`/`ordinal()` ([lowering-and-adapters.md](lowering-and-adapters.md) §4.6). The
API declares the six constants in Minecraft's order; the generator checks the target enum's order and
fails the build if it differs.

### 3.8 `Target`, `TargetKind` (owned)

| Member | Demand |
|---|---|
| `enum TargetKind { MISS, BLOCK, ENTITY }` | S§1 `player.hitResult` (2) |
| `Target`: `TargetKind kind()`, `BlockPos blockPos()` *nullable unless BLOCK*, `Direction side()` *nullable unless BLOCK*, `Vec3 location()` | S§1 `player.hitResult` (Wurst7 `BlockHitResult`, Jade raycast) |

### 3.9 `KeyBinding` (alias), `Key`, `Input` (owned)

| Member | Demand | Binding key |
|---|---|---|
| `static KeyBinding KeyBinding.register(String id, Key defaultKey, String category)` (only inside `ClientMod.init()`; later calls throw `IllegalStateException` on every target) | S§6 `client.keyMapping` (3, MEDIUM) | `register(String,Key,String)` (a registration recipe, [events-and-hooks.md](events-and-hooks.md) §4.7) |
| `boolean consumePress()` (true once per press) | S§6 `client.keyMapping` | `consumePress()` |
| `boolean isDown()` | S§6 `client.input` (4, MEDIUM) | `isDown()` |
| `Key`: constants `A`…`Z`, `NUM_0`…`NUM_9`, `F1`…`F12`, `ESCAPE`, `ENTER`, `SPACE`, `TAB`, `BACKSPACE`, `LEFT`, `RIGHT`, `UP`, `DOWN`, `LEFT_SHIFT`, `RIGHT_SHIFT`, `LEFT_CONTROL`, `RIGHT_CONTROL`, `LEFT_ALT`, `RIGHT_ALT`, `NONE`; `String name()` | S§6 `client.input` | translation table per target in `types/Key.json` |
| `static boolean Input.isKeyDown(Key key)` | S§6 `client.input` (4) | `isKeyDown(Key)` |

`id` becomes the translation key `key.<modid>.<id>`; `category` becomes `key.categories.<modid>.<category>`.
Display names for both come from the Gradle block, and the emitter writes them as a language file
([build-and-gradle.md](build-and-gradle.md) §3).

### 3.10 `Screen` (owned abstract class the mod extends)

| Member | Demand |
|---|---|
| `protected Screen(String title)` | S§3 `render.screen` (10) |
| `protected void init()` (called on open and on resize) | S§3 `render.screen` |
| `public abstract void render(Canvas2D canvas, int mouseX, int mouseY, float partialTick)` | S§3 `render.screen`, `render.guiGraphics` (7) |
| `public boolean mouseClicked(double x, double y, int button)`, `public boolean mouseReleased(double x, double y, int button)`, `public boolean mouseScrolled(double x, double y, double amount)` | S§3 `render.screen`; S§6 `client.input` |
| `public boolean keyPressed(Key key)`, `public boolean charTyped(char c)` | S§6 `client.input` |
| `public void onClose()` | S§3 `render.screen` |
| `public boolean pausesGame()` (default `true`) | S§3 `render.screen` |
| `protected final int width()`, `protected final int height()` (GUI-scaled) | S§3 `render.screen` |
| `protected final void close()` | S§3 `render.screen` |
| `public boolean mouseDragged(double x, double y, int button, double dx, double dy)` | sliders and scroll lists (§3.14) |
| `protected final <W extends Widget> W add(W widget)`, `protected final void remove(Widget widget)` | widgets (§3.14, D-039) |

Input reaches widgets first (in reverse add order, so the topmost wins), then the mod's own
`mouseClicked`/`keyPressed`/`charTyped` if no widget consumed it. Widgets are drawn after the mod's
`render`. One widget has keyboard focus; `TAB` moves it.

### 3.11 `Blanket`, `Feature`, `Log` (owned)

| Member | Demand |
|---|---|
| `static boolean Blanket.supports(Feature f)` (folded to a constant per target; [binding-format.md](binding-format.md) §6) | `player.hand` off-hand gap on 1.8.9 (seed §2.1 note) |
| `static boolean Blanket.hasLibrary(String name)` (folded per target; [libraries.md](libraries.md) §3.3) | settled decision 5: optional libraries per target |
| `enum Feature { OFFHAND, CUSTOM_PAYLOAD_SEND }` | the only API feature absent on a 1.0 target (1.8.9, before the off-hand existed in 1.9) |
| `static void Log.info(String)`, `Log.warn(String)`, `Log.error(String, Throwable)` (logger named after the mod id) | every mod logs; Minecraft's log4j-api is present on every target |
| `static <T> T Blanket.overlay(Class<T> service)` *nullable* (argument must be a class literal; folded per target to `new <impl>()` or `null`) | per-target escape hatch (D-036, [build-and-gradle.md](build-and-gradle.md) §2.1) |

### 3.12 Events

| Member | Payload members | Demand | Recipe file |
|---|---|---|---|
| `Events.CLIENT_TICK_START`, `Events.CLIENT_TICK_END` : `EventSource<ClientTick>` | none (`ClientTick` is an empty marker) | seed `event_client_tick` (S§4, S§9: `Minecraft` is the most-mixined class) | `events/ClientTick.json` |
| `Events.HUD_RENDER` : `EventSource<HudRender>` | `Canvas2D canvas()`, `float partialTick()` | seed `event_hud_render` (S§3 `render.gui`, S§9) | `events/HudRender.json` |
| `Events.WORLD_RENDER` : `EventSource<WorldRender>` | `Draw3D draw()`, `float partialTick()`, `Vec3 cameraPosition()` | seed `event_world_render` (S§9 `LevelRenderer`, `GameRenderer`) | `events/WorldRender.json` |
| `Events.SCREEN_OPEN` : `EventSource<ScreenOpen>` | `ScreenKind kind()` (`NONE` when the screen closes), `boolean isModScreen()` | seed `event_screen_change` (S§9 `Screen` mixins, S§3 `render.screen`) | `events/ScreenOpen.json` |
| `Events.WORLD_JOIN`, `Events.WORLD_LEAVE` : `EventSource<WorldJoin>`/`<WorldLeave>` | none | S§5 `packet.login` (Wurst7, Iris: join detection) | `events/WorldJoin.json`, `events/WorldLeave.json` |
| `Events.INPUT` : `EventSource<InputEvent>` | see §3.15 | S§6 `client.input` (4); spatial GUIs (D-040, D-043) | `events/Input.json` |

`enum ScreenKind { NONE, TITLE, PAUSE, CHAT, INVENTORY, CONTAINER, OTHER }` — the screens the
surveyed mods target (S§7 GUI/Screen divergence: `GuiScreen`, `GuiContainer`; S§9 `AbstractContainerScreen`).

Plumbing: `interface Listener<E> { void on(E event); }`, `interface EventSource<E> { Subscription register(Listener<E> l); }`,
`interface Subscription { void cancel(); }`. Listeners run in registration order, on the client
thread. An exception thrown by a listener is logged with the mod id and event name, and the remaining
listeners still run (one broken handler must not break the game loop on any target).

Only events the mod references are wired: the scanner records `GETSTATIC Events.X` and the generator
emits recipes for exactly those ([architecture.md](architecture.md) §3.1).

### 3.13 Rendering

`Canvas2D`, `Draw3D`, `WorldPanel`, `PanelCursor`, `Facing`, `Texture`, `Font`, `Mesh`, `MeshBuilder`,
`Color`, `BlendMode`, `DepthMode`, `TextAlign`, `Primitive` are specified member by member in
[rendering-api.md](rendering-api.md) §2. Demand: S§3
`render.guiGraphics` (7), `render.font` (5), `render.bufferBuilder` (4), `render.poseStack` (5),
`render.levelRenderer` (4); settled decision 4.

### 3.14 Widgets (owned, package `com.blanket.api.ui`, D-039)

All widgets are owned Java built on `Canvas2D`; they contain no Minecraft knowledge except the clipboard
glue `TextField` uses. Default look: flat, Blanket's own palette, `Font.defaultFont()`; `Style` changes
colours, padding and font.

| Type | Members |
|---|---|
| `abstract class Widget` | `bounds(float x, float y, float w, float h)`, `float x()/y()/width()/height()`, `visible(boolean)`, `enabled(boolean)`, `boolean isHovered()`, `style(Style s)`; protected hooks `render(Canvas2D c, float mouseX, float mouseY)`, `mouseClicked/Released/Dragged/Scrolled`, `keyPressed(Key)`, `charTyped(char)`, `focusChanged(boolean)` (a mod may subclass `Widget` for its own controls) |
| `Button` | `Button(String label, Runnable onPress)`; `label(String)` |
| `Toggle` | `Toggle(String label, boolean value, Consumer<Boolean> onChange)`; `boolean value()`, `value(boolean)` |
| `Slider` | `Slider(String label, double min, double max, double value, DoubleConsumer onChange)`; `step(double)`, `format(DoubleFunction<String>)`, `double value()`, `value(double)` |
| `TextField` | `TextField(int maxLength, Consumer<String> onChange)`; `String text()`, `text(String)`, `placeholder(String)`, `filter(Predicate<Character>)`; caret, selection, `CTRL+A/C/V/X` with the system clipboard (glue `GlueInput.clipboard()/setClipboard(String)`) |
| `ScrollList<T>` | `ScrollList(List<T> items, float rowHeight, RowPainter<T> painter, Consumer<T> onSelect)`; `items(List<T>)`, `T selected()`, `select(T)`; `interface RowPainter<T> { void paint(Canvas2D c, T item, float x, float y, float w, float h, boolean hovered, boolean selected); }` |
| `Style` | `Style.defaults()`, `background(int argb)`, `foreground(int argb)`, `accent(int argb)`, `padding(float)`, `font(Font)` |

Widgets live in a `Screen` (§3.10) or in an interactive `WorldPanel` (`panel.add(widget)`,
[rendering-api.md](rendering-api.md) §2.2), which receives clicks, scrolls and, while focused, keys
through the input routing of §3.15.

### 3.15 Input routing (owned, D-043)

In the world (no screen open), every input travels in this order and stops at the first stage that
takes it:

```
physical input ─► 1 captures (Input.capture rules, newest first) ──► mod only
               ─► 2 INPUT listeners (registration order; consume() stops it here)
               ─► 3 interactive WorldPanel under the crosshair (clicks, scroll; keys while focused)
               ─► 4 the game (key bindings, attack/use, hotbar scroll, chat key, ...)
```

Inside a screen, input goes to the screen as before (§3.10); `INPUT` does not fire. A captured or
consumed *press* also swallows its matching *release*, so the game never sees half a click.

| Member | Notes |
|---|---|
| `InputEvent`: `InputKind kind()`, `Key key()` (KEY), `int mouseButton()` (MOUSE_BUTTON: 0 left, 1 right, 2 middle), `boolean pressed()` (false on release), `double scroll()` (SCROLL), `char character()` (CHAR), `void consume()`, `boolean isConsumed()` | every listener sees the event even after another consumed it (`isConsumed()` says so); the game does not |
| `enum InputKind { KEY, MOUSE_BUTTON, SCROLL, CHAR }` | |
| `static Subscription Input.capture(InputFilter filter)` | while not cancelled, matching input goes to the mod's listeners and panels only, never to the game; several captures combine |
| `InputFilter`: `static InputFilter all()`, `kinds(InputKind...)`, `keys(Key...)`, `mouseButtons(int...)`, `InputFilter and(InputFilter other)`, `InputFilter except(InputFilter other)` | example: `Input.capture(InputFilter.kinds(MOUSE_BUTTON, SCROLL))` while a world menu is open |
| `static boolean Input.isCaptured(InputKind kind)` | |

Limits: input is physical (a key or button), not a game action, so capturing `Key.W` does not stop
movement if the player rebound "forward"; capturing `InputFilter.all()` does. Two Blanket mods that
both capture see input in Mixin order; the first to consume wins.

### 3.16 Packets (package `com.blanket.api.net`, D-044)

| Member | Notes |
|---|---|
| `static <V> Subscription Packets.onReceive(PacketType<V> type, PacketListener<V> l)`, `static <V> Subscription Packets.onSend(PacketType<V> type, PacketListener<V> l)` | the `type` argument must be a `PacketTypes` constant (the scanner records it, so only used packet types are resolved and classified, settled decision 3) |
| `interface PacketListener<V> { void on(V packet, PacketControl control); }`; `PacketControl`: `void cancel()`, `boolean isCancelled()` | a cancelled received packet is never handled by the game; a cancelled sent packet never leaves |
| `PacketTypes.BLOCK_UPDATE` : `PacketType<BlockUpdate>` (receive) | `BlockUpdate`: `BlockPos pos()`, `BlockState state()` |
| `PacketTypes.CHAT` : `PacketType<ChatMessage>` (receive) | `ChatMessage`: `String text()` (plain text), `boolean isSystem()`, `boolean isActionBar()` |
| `PacketTypes.CUSTOM_PAYLOAD` : `PacketType<CustomPayload>` (receive and send) | `CustomPayload`: `String channel()` (`"namespace:path"`), `byte[] data()` (a copy) |
| `PacketTypes.ANY` : `PacketType<AnyPacket>` (receive and send) | `AnyPacket`: `String debugName()` (the runtime class's simple name; differs per target, for logging only) |
| `static void Packets.sendCustomPayload(String channel, byte[] data)` | requires `Feature.CUSTOM_PAYLOAD_SEND` (spike S-22: 1.20.5+ may need a registered codec) |

Listeners run on the network thread (rule 7). A view is valid only during the callback; copy what you
need, and use `Client.runOnClientThread` for anything else. Packets in the login and configuration
phases are not seen; listening starts when the play phase starts.

## 4. Coverage rule

Every member above maps to a binding entry in the named file, or is owned code that calls only bound
members. The consistency check in the build of `blanket-bindings` enforces this: for every public
member of `blanket-api` it looks for a binding entry or an `owned` marker, and fails on a gap
([testing-strategy.md](testing-strategy.md) §2, check C1).

## 5. What the API deliberately is not

- No `Entity` type or entity iteration. Seed derivation §6 excluded generic entity iteration for lack
  of a demanding row; it returns with the first mod that needs it.
- No raw packet access: packets are seen only through typed views (§3.16). Each view hides its
  per-era shape changes behind bindings and adapters; a new packet type is added like any type
  ([contributing.md](contributing.md) §3.5).
- No `Text`/`Component` type: strings only. Styled text is backlog item 4.
- No configuration, networking or loader features (they belong to the per-target escape hatch,
  `Blanket.overlay`, D-036).

## 6. Backlog (eligible but not in v1)

| # | Survey row | Demand | Why not v1 |
|---|---|---|---|
| 1 | S§5 packets beyond the §3.16 catalog (entity velocity, chunk data, ...) | HIGH/MEDIUM | Added per packet type through [contributing.md](contributing.md) §3.5; no raw buffer API. |
| 2 | S§2 `world.blockEntity` | MEDIUM (4) | Useful only with block-entity data access (NBT, deferred). |
| 3 | S§6 `client.nbt` | MEDIUM (3) | 1.20.5 item components change what NBT means for items. |
| 4 | S§6 `client.component`, `client.chatFormatting` | HIGH | Styled text needs an owned `Text` type; v1 uses strings. |
| 5 | S§6 `client.options` | MEDIUM (3) | No first-release use case; options fields change per version. |
| 6 | S§6 `client.resourceManager` | MEDIUM (3) | Mods read their own jar resources with plain Java in v1. |
| 7 | S§2 `world.registry` (iteration) | MEDIUM (4) | v1 exposes ids as strings; iteration waits for demand. |
| 8 | (moved into v1: widgets, §3.14, D-039) | — | — |
| 11 | (moved into v1: input routing, §3.15, D-043) | — | — |
| 9 | S§1 `player.effects` | LOW | LOW rows wait for a request. |
| 10 | S§6 `client.math` | HIGH (6) | Excluded despite demand, as in v1 (seed §6): pure math, not version-sensitive; use JOML or the JDK. |
