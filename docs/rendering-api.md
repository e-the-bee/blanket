# Rendering API

Blanket's own 2D and 3D drawing API (settled decision 4) and the backend that implements it next to
Minecraft's renderer. Facts cite [research-log.md](research-log.md) (R-GFX-*); unverified claims are
**ASSUMED** with a spike in [risks.md](risks.md).

## 1. Scope

- v1 renders **inside Minecraft only** (D-040): a `Canvas2D` exists during `HUD_RENDER` and
  `Screen.render`, a `Draw3D` during `WORLD_RENDER`, and a panel `Canvas2D` inside `Draw3D.panel`.
  Standalone use (a window without Minecraft) is only a test harness.
- World-space drawing is a first-class use: panels, floating text and shapes that blocks, players and
  entities hide when they are in front, and that hide what is behind them (§2.2, depth contract).
- No custom shaders in v1. Fixed materials: solid colour, textured (with tint), text, lines.
- Every coordinate type is `double` in world space and `float` in GUI space; no Minecraft or JOML type
  appears in the API.

## 2. API (package `com.blanket.api.render`)

### 2.1 `Canvas2D` — GUI-scaled pixels, origin top-left, y down

| Member | Notes |
|---|---|
| `float width()`, `float height()` | GUI-scaled size of the drawing area (screen, or HUD) |
| `void fillRect(float x, float y, float w, float h, int argb)` | |
| `void fillRoundedRect(float x, float y, float w, float h, float radius, int argb)` | anti-aliased edges via the shader |
| `void fillGradient(float x, float y, float w, float h, int argbTop, int argbBottom)` | vertical |
| `void strokeRect(float x, float y, float w, float h, float thickness, int argb)` | |
| `void line(float x1, float y1, float x2, float y2, float thickness, int argb)` | drawn as a quad, not `glLineWidth` (core profiles cap line width at 1) |
| `void image(Texture t, float x, float y, float w, float h)` | |
| `void image(Texture t, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argbTint)` | |
| `void text(String s, float x, float y, int argb)`, `void text(Font f, String s, float x, float y, int argb, TextAlign align, boolean shadow)` | baseline at `y + font.ascent()` |
| `float textWidth(String s)`, `float textWidth(Font f, String s)` | |
| `void pushClip(float x, float y, float w, float h)`, `void popClip()` | intersects with the enclosing clip; scissor in framebuffer pixels |
| `void push()`, `void pop()`, `void translate(float x, float y)`, `void scale(float sx, float sy)`, `void rotate(float degrees)` | transform stack; clips are axis-aligned in the transformed space only for translate/scale, rotation disables clip transform (documented) |
| `void pushOpacity(float a)`, `void popOpacity()` | multiplies alpha of everything inside |

### 2.2 `Draw3D` — world coordinates, camera handled internally

| Member | Notes |
|---|---|
| `Vec3 cameraPosition()` | world position of the camera this frame |
| `void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, float widthPx)` | screen-space width, drawn as camera-facing quads |
| `void boxOutline(Box b, int argb, float widthPx)`, `void boxFill(Box b, int argb)` | `Box` is the aliased API value type |
| `void quad(double x1, double y1, double z1, … , double x4, double y4, double z4, int argb)` | |
| `void mesh(Mesh m, double x, double y, double z)` | |
| `void text(String s, double x, double y, double z, float scale, int argb)` | camera-facing billboard |
| `void text(String s, double x, double y, double z, float scale, int argb, Facing facing, int backgroundArgb)` | `Facing.BILLBOARD` (faces the camera), `BILLBOARD_Y` (turns around the vertical axis only, like name tags), `FIXED` (uses the current rotation of the transform stack); background `0` for none |
| `void panel(WorldPanel p, Painter painter)` | draws `painter.paint(Canvas2D)` onto the panel's rectangle in the world; `interface Painter { void paint(Canvas2D c); }` |
| `void depth(DepthMode mode)` | `TEST` (default: hidden behind blocks and entities that are in front) or `ALWAYS` (through walls) |
| `void blend(BlendMode mode)` | `ALPHA` (default), `ADDITIVE`, `OPAQUE` |
| `void push()`, `void pop()`, `void translate(double x, double y, double z)`, `void rotate(float degrees, float ax, float ay, float az)`, `void scale(float s)` | |

World coordinates are converted to camera-relative `float` on the CPU in `double` precision before
upload, so positions far from the origin do not jitter.

**`WorldPanel`** (owned value, built once and reused):

| Member | Notes |
|---|---|
| `static WorldPanel at(double x, double y, double z)` | centre of the panel |
| `size(float widthBlocks, float heightBlocks)`, `resolution(int pixelsPerBlock)` | the panel `Canvas2D` is `widthBlocks × pixelsPerBlock` by `heightBlocks × pixelsPerBlock` units |
| `facing(Facing f)`, `rotation(float yaw, float pitch)` | `FIXED` uses `rotation`; `BILLBOARD`/`BILLBOARD_Y` ignore it |
| `depth(DepthMode m)`, `doubleSided(boolean)` | default `TEST`, single-sided |
| `PanelCursor cursor()` *nullable* | where the crosshair ray (from the last frame's camera, within `reach`, default 6 blocks) hits the panel, in panel units; `null` when not looked at or when a block is closer. `PanelCursor`: `float x()`, `float y()`, `double distance()` |
| `reach(double blocks)` | |
| `interactive(boolean)`, `<W extends Widget> W add(W widget)`, `focus()`, `boolean isFocused()` | an interactive panel takes stage 3 of input routing ([api-surface.md](api-surface.md) §3.15): a click or scroll while `cursor()` is non-null goes to its widgets at the cursor position and never reaches the game; keys go to it while focused (a click on it focuses it, `ESCAPE` or looking away unfocuses it). Widgets added to a panel are drawn after the painter |

How a panel draws: the painter's commands are transformed into the panel's plane (no off-screen texture),
so text stays sharp at any distance (SDF). Clipping inside a panel is done in the fragment shader with a
clip rectangle in panel space, because scissor rectangles cannot follow a tilted plane. Everything in
one panel lies in one plane, so it would z-fight with itself; the executor draws a panel's content with
depth test on and depth writes off, in painter order, then writes the panel's depth once with an
invisible quad (colour mask off). The panel is therefore hidden by what is in front of it and hides what
is drawn behind it later.

**Depth contract.** "Hidden behind a block, player or entity" is ordinary depth testing against the
game's depth buffer. It holds only if `WORLD_RENDER` runs after terrain *and* entities have written depth
into the main target on every era; [events-and-hooks.md](events-and-hooks.md) §4.4 states it per era and
spike S-20 checks it. Two known limits: with Fabulous graphics (1.16+), translucent blocks (water,
stained glass) live in their own target, so a panel behind water is drawn over the water instead of
tinted by it; shader packs (Iris, OptiFine) replace the pipeline and are risk R-09.

### 2.3 Resources

| Type | Members |
|---|---|
| `Texture` | `static Texture resource(String path)` (a PNG inside the mod's resources; decoded **at build time** into raw RGBA, so no PNG decoder runs in the game); `static Texture fromPixels(int width, int height, int[] argb)`; `static Texture minecraft(String id)` (a texture the game has loaded, e.g. `"minecraft:textures/gui/icons.png"`; glue per era, §4); `int width()`, `int height()`; `void update(int[] argb)`; `void close()` |
| `Font` | `static Font defaultFont()`; `static Font resource(String ttfPath, float sizePx)`; `float lineHeight()`, `float ascent()`, `float width(String s)` |
| `Mesh` / `MeshBuilder` | `static MeshBuilder Mesh.builder(Primitive p)` with `Primitive { TRIANGLES, LINES }`; `MeshBuilder vertex(float x, float y, float z, int argb)`, `vertex(x, y, z, argb, u, v)`, `Mesh build()` (uploads once); `Mesh.close()` |
| `Color` | `static int argb(int a, int r, int g, int b)`, `static int rgb(int r, int g, int b)`, `static int withAlpha(int argb, int a)` |
| enums | `BlendMode`, `DepthMode`, `TextAlign { LEFT, CENTER, RIGHT }`, `Primitive`, `Facing { BILLBOARD, BILLBOARD_Y, FIXED }` |

Fonts are baked **at build time**: the generator rasterizes each font used (the default font and every
`Font.resource` path it finds as a constant) into a signed-distance-field atlas with Java 2D, for the
glyph set configured in Gradle (`blanket.fonts.glyphs`, default Latin-1 plus general punctuation).
Characters outside the set draw as a box. Reasons: LWJGL 2 (1.8.9, 1.12.2) has no `stb_truetype`
(V R-GFX-07); Minecraft moved its own glyph code from stb to FreeType in 1.20.5 and removed `Font` draw
methods in 26.2 (V R-GFX-07), so neither is a stable dependency. The default font is Inter (SIL OFL
1.1), shipped in `blanket-runtime`; its license is copied into every output jar that uses it
([libraries.md](libraries.md) §7). Limited glyph coverage is risk R-11.

Resource lifetime: textures, fonts and meshes live until `close()` or until the game closes. They must
be created on the client thread inside an event or `init()`.

## 3. The `gl` backend (v1)

### 3.1 Structure

```
API calls ──► command list (per frame, per event) ──► batcher ──► GL executor ──► GL via LWJGL
```

Every draw call appends a command to a frame-local list (vertex data in a reusable direct buffer). At
the end of the event (immediate eras) or at the replay point (deferred eras,
[events-and-hooks.md](events-and-hooks.md) §4.3), the executor runs the list inside the state guard.
Recording always, even where drawing could be immediate, means one code path for both GUI models.

### 3.2 GL contexts and shader dialects

| Minecraft | Context (V R-GFX-02) | Blanket dialect |
|---|---|---|
| 1.8.9 – 1.12.2 | LWJGL 2 `Display`, legacy compatibility, fixed function | GLSL 1.20, generic vertex attributes, VBOs (GL 2.1 core features only) |
| 1.13 – 1.16.5 | GLFW, compatibility (1.16.5 asks GL 2.0, profile ANY) | GLSL 1.20 |
| 1.17 – 1.21.8 | GL 3.2 core, forward-compatible | **GLSL 1.50** (`#version 150`, `in`/`out`) |
| 1.21.9 – 26.x (GL backend) | GL 3.3 core required (V R-GFX-03) | GLSL 1.50 |

PLAN.md proposed GLSL 330 for modern targets; 330 needs GL 3.3, but 1.17–1.21.8 create a 3.2 context,
so GLSL 150 is the correct floor and also works on 3.3 (decision D-016). Both dialects come from one
template set (a handful of shaders: solid, textured, SDF text, line quads) with `#ifdef`-style
substitution done when `blanket-runtime` is built.

### 3.3 GL function access

LWJGL 2 and 3 both put GL entry points in `org.lwjgl.opengl.GL11`…`GL30` with mostly identical
signatures, but not all (buffer-object and query functions differ). The backend calls GL only through
an internal interface `Gl` with two implementations, `Lwjgl2Gl` and `Lwjgl3Gl`, compiled against the
respective LWJGL jars. `targets.json` era data selects one; the shrink pass drops the other. Every GL
method either implementation references is checked in the target's LWJGL jar by the class index, like
any external reference.

### 3.4 State guard

Minecraft caches GL state in `GlStateManager` (blend, depth, cull, polygon offset, colour logic,
scissor, active texture unit, bound textures, read/draw framebuffer in 26.1; V R-GFX-04). If raw GL
changes state and does not restore it, the cache is wrong and later vanilla drawing breaks. The guard,
at the start of each executed command list, saves with `glGet*`, and restores at the end:

- current program; vertex array binding (core) or enabled vertex attribute arrays (compatibility);
  `GL_ARRAY_BUFFER` binding;
- active texture unit and the 2D texture bound on unit 0 and on the unit made active;
- blend enable, blend func (separate RGB/alpha), blend equation;
- depth test enable, depth func, depth mask; cull face enable; polygon offset fill enable;
- scissor enable and box; viewport; colour mask;
- read and draw framebuffer bindings.

Restoring the real state is enough to keep the cache valid, because the cache mirrors real state.
In compatibility contexts the guard additionally sets the fixed-function current colour back to white
(1.8.9 code relies on it, **ASSUMED**, spike S-06). The guard costs ~25 `glGet` calls per list, a few
microseconds; the budget is checked in M4.

### 3.5 Matrices and framebuffer

- 2D: orthographic projection from the GUI-scaled size; the executor scales by the GUI scale to
  framebuffer pixels for scissor.
- 3D: projection and view-rotation matrices from glue (§4.2), camera position subtracted on the CPU,
  model transform from the `Draw3D` stack. The projection must match the game's exactly so depth
  testing against the world works; glue reads it rather than recomputing it.
- Reversed depth: 26.2+ reverses the depth buffer (V R-GFX-05). Glue exposes `depthReversed()`; the
  executor flips `glDepthFunc` and the projection's depth mapping when true. 26.2+ is not in the first
  release; the exact convention is spike S-09.
- Target framebuffer: Minecraft draws into its main render target (an FBO) and blits it to the screen
  in modern versions (V R-GFX-06). Glue gives the FBO id to bind; on 1.8.9 it may be 0 when the
  framebuffer option is off (**ASSUMED**, S-06).

### 3.6 Runtime check of the active graphics backend

On 26.2 and 26.3, the player may choose Vulkan; 26.4 snapshots default to it (V R-GFX-05). There is no
GL context then. On those targets glue exposes `glActive()`; the executor checks it once per frame and,
when false, discards the command list and logs one warning per session:
`"<modid>: drawing disabled — Minecraft is using the Vulkan backend; rebuild with a Blanket version
that has the gpu backend for <target>"`. This is a property of the player's settings, not a version
check in mod code. From 1.0 these targets use the `gpu` backend when `glActive()` is false (§5), so the warning only
appears on a build made with a 0.x Blanket.

## 4. Per-target glue

Glue types live in `com.blanket.runtime.glue` and are bound in `glue/*.json` exactly like API types
([binding-format.md](binding-format.md) §4.5). v1 glue:

| Glue member | 1.8.9 | 1.21.1 | 26.1.2 |
|---|---|---|---|
| `GlueFrame.guiWidth/guiHeight/guiScale` | adapter over `ScaledResolution`-equivalent (Legacy Yarn `Window` class) **A** | `Window#getGuiScaledWidth/Height`, `getGuiScale()` (double) **A** | same, `getGuiScale()` is `int` from 1.21.6 (V R-GFX-06) |
| `GlueFrame.framebufferWidth/Height` | `MinecraftClient.width/height` **A** | `Window#getWidth/getHeight` **A** | V R-GFX-06 |
| `GlueFrame.mainFramebuffer()` | none: draw into the bound framebuffer (V R-EXP-02) | `Minecraft#getMainRenderTarget().frameBufferId` (V R-EXP-02) | `colorTexture()`/`depthTexture()`: `getMainRenderTarget().getColorTexture()`/`getDepthTexture()` → `GlTexture#glId()`, attached to a Blanket FBO (V R-EXP-03, D-049) |
| `GlueWorld.projection(float[16])`, `viewRotation(float[16])` | `glGetFloatv(GL_PROJECTION_MATRIX / GL_MODELVIEW_MATRIX)` inside the hook (legacy context, V R-GFX-02, R-EXP-06) | from the `RenderLevelStageEvent` (NeoForge) or the `renderLevel` arguments (Fabric) (V R-EXP-06) | `Camera#getViewRotationProjectionMatrix` / `getViewRotationMatrix` (V R-GFX-08) |
| `GlueWorld.cameraPosition()` | adapter over the render-view entity's interpolated position **A** | `Camera#getPosition` **A** | `Camera#position()` (V R-GFX-08) |
| `GlueGui.flush()` | no-op | `GuiGraphics#flush()` **A** | not used (deferred replay) |
| `GlueTexture.minecraftTextureId(String)` | `TextureManager` bind + `glGetInteger` **A** | `TextureManager#getTexture(...).getId()` **A** | via `GpuTexture` **A** |
| `GlueFrame.depthReversed()`, `glActive()` | `const false`, `const true` | `const false`, `const true` | `const false`, `const true` |

V = verified; **A** = ASSUMED. All **A** cells are resolved in M0/M4 by the bindings validator plus the in-game smoke test (spike
S-06).

## 5. The `gpu` backend (M8)

Needed for 26.2+ players on Vulkan and mandatory once Mojang removes OpenGL, which it has announced
it will do once Vulkan is stable (V R-GFX-05). Design:

- Same command list and batcher. The executor becomes an adapter set per era that records Blanket's
  geometry into Mojang's GPU abstraction: `GpuDevice` buffers and textures, a `RenderPipeline` per
  Blanket material, `CommandEncoder#createRenderPass` on the main target (1.21.5+, V R-GFX-04), later
  `com.mojang.renderpearl` (26.3, V R-GFX-04).
- Shaders: Mojang compiles GLSL with ShaderC for both backends from 26.3 (V R-GFX-04), so Blanket's
  GLSL 150 templates are the starting point; the exact shader format a `RenderPipeline` accepts is
  spike S-10.
- Selection is per target in `targets.json` (`render: "gl" | "gpu" | "both"`). On 26.2+ the jar
  contains both executors and picks one per frame from `glActive()`, because the player chooses the
  graphics backend in the options. For 1.21.5–26.1 `gl` stays the only backend until `gpu` has passed
  the same golden tests.
- Text and textures use Blanket's atlases uploaded as `GpuTexture`s, so no dependency on Minecraft's
  font pipeline.

`mc-native` from PLAN.md is this backend; it is renamed `gpu` because it targets Mojang's GPU
abstraction, not Minecraft's high-level draw helpers (D-017).

## 6. Test strategy (rendering)

1. **Unit:** command list and batcher (vertex counts, clip stack, transform stack) without GL.
2. **Golden images, headless:** the executor draws fixed scenes into an offscreen FBO in a hidden GLFW
   window under Xvfb with Mesa llvmpipe (GLFW hidden windows as offscreen contexts, Mesa
   `LIBGL_ALWAYS_SOFTWARE`/`GALLIUM_DRIVER=llvmpipe`, V R-GFX-09). Two contexts: GL 2.1 compatibility
   (GLSL 120) and GL 3.2 core (GLSL 150). Output compared with stored PNGs, per-channel tolerance 2/255
   and at most 0.1% differing pixels. OSMesa is not used (removed in Mesa 25.1, V R-GFX-09). The LWJGL 2
   `Gl` implementation is tested under Xvfb with LWJGL 2's `Display` (**ASSUMED** to work headless,
   S-13).
3. **State-guard test:** set every guarded state to a non-default value, run a command list, assert
   every value is back.
4. **In game:** the M0/M4 smoke mods draw a known HUD rectangle and a box at a fixed world position;
   the harness takes a screenshot and checks pixel colours at known coordinates
   ([testing-strategy.md](testing-strategy.md) §4).
