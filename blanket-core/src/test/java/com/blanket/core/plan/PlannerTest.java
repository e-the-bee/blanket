// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.validate.TargetData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner on an invented unobfuscated game ({@code net/example/}, official names, so no mapping file is read) with
 * an invented loader ({@code net/loaderfx/}): one Mixin target ({@code fabric:26.1.2}) and one native target
 * ({@code neoforge:26.1.2}). Glue and {@code Dispatch} are the real runtime stubs.
 */
class PlannerTest implements Opcodes {
    private static final String FRAME = "com/blanket/runtime/glue/GlueFrame";
    private static final String WORLD = "com/blanket/runtime/glue/GlueWorld";
    private static final String GUI = "com/blanket/runtime/glue/GlueGui";

    @TempDir
    Path tmp;

    Path mod;
    Path client;
    Path loader;

    @BeforeEach
    void setUp() throws IOException {
        client = jar("client.jar", Map.of(
                "net/example/Game", game(),
                "net/example/Mob", simple("net/example/Mob", "java/lang/Object", ACC_PUBLIC, m -> method(m, ACC_PUBLIC, "getX", "()D")),
                "net/example/Hero", simple("net/example/Hero", "net/example/Mob", ACC_PUBLIC, m -> {}),
                "net/example/Clock", simple("net/example/Clock", "java/lang/Object", ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
                        m -> m.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "partial", "(Z)F", null, null).visitEnd()),
                "net/example/Painter", simple("net/example/Painter", "java/lang/Object", ACC_PUBLIC, m -> method(m, ACC_PUBLIC, "paint", "()V")),
                "net/example/Matrix", simple("net/example/Matrix", "java/lang/Object", ACC_PUBLIC, m -> {
                    method(m, ACC_PUBLIC, "<init>", "()V");
                    method(m, ACC_PUBLIC, "get", "([F)[F");
                }),
                "net/example/Pos", simple("net/example/Pos", "java/lang/Object", ACC_PUBLIC, m -> method(m, ACC_PUBLIC, "<init>", "(III)V")),
                "net/example/Pad", simple("net/example/Pad", "java/lang/Object", ACC_PUBLIC, m -> method(m, ACC_PUBLIC | ACC_STATIC, "down", "(I)Z")),
                "net/example/Heading", simple("net/example/Heading", "java/lang/Enum", ACC_PUBLIC | ACC_FINAL | ACC_ENUM, m -> {
                    m.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, "NORTH", "Lnet/example/Heading;", null, null).visitEnd();
                    m.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, "SOUTH", "Lnet/example/Heading;", null, null).visitEnd();
                }),
                "net/example/Aabb", simple("net/example/Aabb", "java/lang/Object", ACC_PUBLIC, m -> {
                    for (String a : List.of("getMinX", "getMinY", "getMinZ", "getMaxX", "getMaxY")) method(m, ACC_PUBLIC, a, "()D");
                })));
        loader = jar("loader.jar", Map.of(
                "net/loaderfx/TickEvent", simple("net/loaderfx/TickEvent", "java/lang/Object", ACC_PUBLIC,
                        m -> m.visitField(ACC_PUBLIC, "phase", "Lnet/loaderfx/Phase;", null, null).visitEnd()),
                "net/loaderfx/Phase", simple("net/loaderfx/Phase", "java/lang/Object", ACC_PUBLIC,
                        m -> m.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "END", "Lnet/loaderfx/Phase;", null, null).visitEnd()),
                "net/loaderfx/HudEvent", simple("net/loaderfx/HudEvent", "java/lang/Object", ACC_PUBLIC, m -> method(m, ACC_PUBLIC, "partialTick", "()F")),
                "net/loaderfx/WorldEvent", simple("net/loaderfx/WorldEvent", "java/lang/Object", ACC_PUBLIC, m -> method(m, ACC_PUBLIC, "partialTick", "()F"))));
        mod = jar("mod.jar", Map.of("mod/Main", modClass()));
    }

    // ---------------------------------------------------------------------------------------- fixture game and mod

    private static byte[] game() {
        return simple("net/example/Game", "java/lang/Object", ACC_PUBLIC, w -> {
            w.visitField(ACC_PUBLIC, "hero", "Lnet/example/Hero;", null, null).visitEnd();
            method(w, ACC_PUBLIC | ACC_STATIC, "instance", "()Lnet/example/Game;");
            method(w, ACC_PUBLIC, "tick", "()V");
            method(w, ACC_PUBLIC, "render", "(Lnet/example/Painter;Lnet/example/Clock;)V");
            method(w, ACC_PUBLIC, "renderWorld", "(Lnet/example/Matrix;)V");
            MethodVisitor frame = w.visitMethod(ACC_PUBLIC, "frame", "()V", null, null);   // one call of Painter.paint
            frame.visitCode();
            frame.visitInsn(ACONST_NULL);
            frame.visitMethodInsn(INVOKEVIRTUAL, "net/example/Painter", "paint", "()V", false);
            frame.visitInsn(RETURN);
            frame.visitMaxs(0, 0);
            frame.visitEnd();
        });
    }

    /** {@code mod/Main implements ClientMod}: Client.get().player().x() and the three events. */
    private static byte[] modClass() {
        return simple("mod/Main", "java/lang/Object", ACC_PUBLIC, w -> {
            MethodVisitor m = w.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
            m.visitCode();
            m.visitMethodInsn(INVOKESTATIC, "com/blanket/api/Client", "get", "()Lcom/blanket/api/Client;", false);
            m.visitMethodInsn(INVOKEVIRTUAL, "com/blanket/api/Client", "player", "()Lcom/blanket/api/Player;", false);
            m.visitMethodInsn(INVOKEVIRTUAL, "com/blanket/api/Player", "x", "()D", false);
            m.visitInsn(POP2);
            for (String e : List.of("CLIENT_TICK_END", "HUD_RENDER", "WORLD_RENDER")) {
                m.visitFieldInsn(GETSTATIC, "com/blanket/api/Events", e, "Lcom/blanket/api/EventSource;");
                m.visitInsn(POP);
            }
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }, "com/blanket/api/ClientMod");
    }

    private static byte[] simple(String name, String sup, int access, java.util.function.Consumer<ClassWriter> body, String... interfaces) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V17, access, name, null, sup, interfaces.length == 0 ? null : interfaces);
        body.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }

    /** A method whose body throws nothing useful: only its signature matters. */
    private static void method(ClassWriter w, int access, String name, String desc) {
        MethodVisitor m = w.visitMethod(access, name, desc, null, null);
        m.visitCode();
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ATHROW);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private Path jar(String name, Map<String, byte[]> classes) throws IOException {
        Path jar = tmp.resolve(name);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return jar;
    }

    private TargetData data() {
        return new TargetData() {
            @Override
            public Resolver.MappingFiles mappingFiles() {
                return key -> { throw new IOException("the fixture game is unobfuscated: no mapping file " + key); };
            }

            @Override
            public List<Path> classpath(TargetSpec target) {
                return List.of(client, loader);
            }

            @Override
            public boolean hasLoaderJar(TargetSpec target) {
                return true;
            }

            @Override
            public Optional<Path> mojangMappings(String mc) {
                return Optional.empty();
            }

            @Override
            public Optional<String> mcpLicense() {
                return Optional.empty();
            }
        };
    }

    // ---------------------------------------------------------------------------------------- fixture bindings

    static final String TARGETS = """
            { "schema": 1, "anchor": "26.1.2",
              "gamePackages": ["net/example/"], "obfuscatedClass": "[a-z][a-z0-9]{0,3}(\\\\$.*)?",
              "loaders": {
                "fabric": [ { "when": { "mc": "26.1.." }, "runtime": "official", "emitter": "fabric" } ],
                "neoforge": [ { "when": { "mc": "26.1.." }, "runtime": "official", "emitter": "neoforge" } ] },
              "versions": { "26.1.2": { "java": 25, "mappings": { "official": true }, "adapters": "unobf" } },
              "supported": [ "fabric:26.1.2", "neoforge:26.1.2" ] }""";

    static final String CLIENT = """
            { "schema": 1, "api": "com.blanket.api.Client",
              "representation": [ { "when": {}, "alias": "official:net.example.Game" } ],
              "members": {
                "get": [ { "when": {}, "lower": { "invoke": "official:net.example.Game#instance()Lnet/example/Game;" } } ],
                "player": [ { "when": {}, "lower": { "get": "official:net.example.Game#hero:Lnet/example/Hero;" } } ] } }""";

    static String player(String xRef) {
        return """
                { "schema": 1, "api": "com.blanket.api.Player",
                  "representation": [ { "when": {}, "alias": "official:net.example.Hero" } ],
                  "members": { "x": [ { "when": {}, "lower": { "invoke": "%s" } } ] } }""".formatted(xRef);
    }

    static final String GLUE_FRAME = """
            { "schema": 1, "api": "com.blanket.runtime.glue.GlueFrame", "members": {
                "core": [ { "when": {}, "lower": { "const": true } } ],
                "fbo": [ { "when": {}, "lower": { "const": -1 } } ],
                "colorTex": [ { "when": {}, "lower": { "unsupported": "not in the fixture" } } ],
                "partialTick": [ { "when": {}, "lower": { "chain": [
                  { "cast": "official:net.example.Clock" }, { "const": true }, { "invoke": "official:net.example.Clock#partial(Z)F" } ] } } ] } }""";

    static final String GLUE_GUI = """
            { "schema": 1, "api": "com.blanket.runtime.glue.GlueGui", "members": {
                "flush": [ { "when": {}, "lower": { "chain": [
                  { "cast": "official:net.example.Painter" }, { "invoke": "official:net.example.Painter#paint()V" } ] } } ] } }""";

    static final String GLUE_WORLD = """
            { "schema": 1, "api": "com.blanket.runtime.glue.GlueWorld", "members": {
                "matrix": [ { "when": {}, "lower": { "chain": [
                  { "cast": "official:net.example.Matrix" }, { "newarray": "F", "length": 16 }, { "invoke": "official:net.example.Matrix#get([F)[F" } ] } } ],
                "newMatrix": [ { "when": {}, "lower": { "new": "official:net.example.Matrix#<init>()V" } } ],
                "camX": [ { "when": {}, "lower": { "invoke": "official:net.example.Mob#getX()D" } } ] } }""";

    static final String HOOKS = """
            { "schema": 1, "hooks": {
              "tick.end": [ { "when": { "loader": "fabric" }, "method": "official:net.example.Game#tick()V", "at": "RETURN" } ],
              "gui.record": [ { "when": {}, "method": "official:net.example.Game#render(Lnet/example/Painter;Lnet/example/Clock;)V", "at": "TAIL" } ],
              "gui.replay": [ { "when": {}, "method": "official:net.example.Game#frame()V",
                                "at": { "invoke": "official:net.example.Painter#paint()V", "shift": "AFTER" } } ],
              "world.render": [ { "when": {}, "method": "official:net.example.Game#renderWorld(Lnet/example/Matrix;)V", "at": "TAIL" } ] } }""";

    static final String TICK = """
            { "schema": 1, "event": "CLIENT_TICK_END", "recipes": [
              { "when": { "loader": "fabric" }, "mixin": { "hook": "tick.end", "dispatch": "tickEnd" } },
              { "when": { "loader": "neoforge" }, "native": { "event": "loader:net.loaderfx.TickEvent", "bus": "game", "dispatch": "tickEnd",
                "filter": { "loader:net.loaderfx.TickEvent#phase:Lnet/loaderfx/Phase;": "loader:net.loaderfx.Phase#END:Lnet/loaderfx/Phase;" } } } ] }""";

    static final String HUD = """
            { "schema": 1, "event": "HUD_RENDER", "recipes": [
              { "when": { "loader": "fabric" }, "mixin": [
                { "hook": "gui.record", "dispatch": "hudRecord",
                  "before": [ { "glue": "GlueGui#flush(Ljava/lang/Object;)V", "args": [ { "arg": 1 } ] } ],
                  "payload": { "partialTick": { "glue": "GlueFrame#partialTick", "args": [ { "arg": 2 } ] } } },
                { "hook": "gui.replay", "dispatch": "hudReplay" } ] },
              { "when": { "loader": "neoforge" }, "native": { "event": "loader:net.loaderfx.HudEvent", "bus": "game", "dispatch": "hud",
                "payload": { "partialTick": { "getter": "loader:net.loaderfx.HudEvent#partialTick()F" } } } } ] }""";

    static final String WORLD_EVENT = """
            { "schema": 1, "event": "WORLD_RENDER", "recipes": [
              { "when": { "loader": "fabric" }, "mixin": { "hook": "world.render", "dispatch": "world", "payload": {
                "partialTick": { "const": 0 },
                "projection": { "glue": "GlueWorld#matrix", "args": [ { "arg": 1 } ] },
                "view": { "glue": "GlueWorld#matrix", "args": [ { "glue": "GlueWorld#newMatrix" } ] },
                "camX": { "const": 0 }, "camY": { "const": 0 }, "camZ": { "const": 0 } } } },
              { "when": { "loader": "neoforge" }, "native": { "event": "loader:net.loaderfx.WorldEvent", "bus": "game", "dispatch": "world", "payload": {
                "partialTick": { "getter": "loader:net.loaderfx.WorldEvent#partialTick()F" },
                "projection": { "const": null }, "view": { "const": null },
                "camX": { "const": 0 }, "camY": { "const": 0 }, "camZ": { "const": 0 } } } } ] }""";

    private Map<String, String> files() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("targets.json", TARGETS);
        f.put("types/Client.json", CLIENT);
        f.put("types/Player.json", player("official:net.example.Mob#getX()D"));
        f.put("glue/GlueFrame.json", GLUE_FRAME);
        f.put("glue/GlueGui.json", GLUE_GUI);
        f.put("glue/GlueWorld.json", GLUE_WORLD);
        f.put("hooks.json", HOOKS);
        f.put("events/ClientTick.json", TICK);
        f.put("events/HudRender.json", HUD);
        f.put("events/WorldRender.json", WORLD_EVENT);
        return f;
    }

    private PlanSource source(Map<String, String> files) throws IOException {
        return source(files, mod, List.of(), PlannerTest.class.getClassLoader());
    }

    /** Binary files written into the bindings folder beside the JSON files, such as built adapters under {@code build/}. */
    private final Map<String, byte[]> builtFiles = new LinkedHashMap<>();

    private PlanSource source(Map<String, String> files, Path modJar, List<Path> embed, ClassLoader api) throws IOException {
        return source(files, modJar, embed, api, false);
    }

    private PlanSource source(Map<String, String> files, Path modJar, List<Path> embed, ClassLoader api, boolean testkit) throws IOException {
        Path dir = Files.createTempDirectory(tmp, "bindings");
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        for (Map.Entry<String, byte[]> e : builtFiles.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
        return Planner.plan(modJar, dir, embed, data(), api, testkit);
    }

    private Plan plan(String id) throws IOException {
        PlanSource s = source(files());
        for (TargetSpec t : s.targets()) if (t.id().equals(id)) return s.plan(t);
        throw new AssertionError("no target " + id);
    }

    private static Glue glue(Plan p, String name) {
        for (Glue g : p.glue()) if (g.name().equals(name)) return g;
        throw new AssertionError("no glue " + name + " in " + p.glue());
    }

    private static List<Hook> hooks(Plan p, String event) {
        return p.hooks().stream().filter(h -> h.event().equals(event)).toList();
    }

    private static Op invokeStatic(String owner, String name, String desc) {
        return Op.invoke(INVOKESTATIC, owner, name, desc, false);
    }

    // ---------------------------------------------------------------------------------------- tests

    @Test
    void targetFactsComeFromTheErasTheEntryAndTheCoreGlue() throws IOException {
        Plan p = plan("fabric:26.1.2");
        assertEquals("fabric-off", p.era());
        assertEquals("mod/Main", p.entry());
        assertEquals("com/blanket/runtime/Lwjgl3Gl", p.glImpl());
        assertTrue(p.core());
        assertEquals(Map.of("com/blanket/api/Client", "net/example/Game", "com/blanket/api/Player", "net/example/Hero"), p.types());
        assertEquals("neoforge-off", plan("neoforge:26.1.2").era());
    }

    @Test
    void membersGetStaticVirtualAndFieldOpcodesFromTheJar() throws IOException {
        Plan p = plan("fabric:26.1.2");
        assertEquals(Map.of(
                "com/blanket/api/Client.get()Lcom/blanket/api/Client;", new Lowered.Inline(List.of(invokeStatic("net/example/Game", "instance", "()Lnet/example/Game;"))),
                "com/blanket/api/Client.player()Lcom/blanket/api/Player;", new Lowered.Inline(List.of(Op.field(GETFIELD, "net/example/Game", "hero", "Lnet/example/Hero;"))),
                "com/blanket/api/Player.x()D", new Lowered.Inline(List.of(Op.invoke(INVOKEVIRTUAL, "net/example/Mob", "getX", "()D", false)))), p.members());
    }

    @Test
    void classVersionMaxFollowsTheJavaLevelAndTheContractFieldsAreEmpty() throws IOException {
        PlanSource s = source(files());
        TargetSpec t = s.targets().stream().filter(x -> x.id().equals("fabric:26.1.2")).findFirst().orElseThrow();
        Plan p = s.plan(t);
        assertEquals(69, p.classVersionMax());
        assertEquals(Map.of(), p.features(), "no features.json, no features");
        assertEquals(Map.of(), p.adapters(), "no adapter rule, no adapter classes");
        try (var h = s.hierarchy(t)) {
            assertEquals(Optional.of(new com.blanket.core.lower.Hierarchy.Facts("net/example/Mob", List.of(), false)), h.get("net/example/Hero"));
            assertEquals(Optional.empty(), h.get("mod/Main"), "mod classes are the lower stage's");
        }
    }

    @Test
    void glueCallsAnInterfaceMethodWithInvokeInterfaceAndCastsObjectParameters() throws IOException {
        Plan p = plan("fabric:26.1.2");
        Glue partial = glue(p, FRAME + ".partialTick");
        assertEquals("F", partial.retDesc());
        assertEquals(List.of(Op.load(ALOAD, 0), Op.cast("net/example/Clock"), Op.constant(true),
                Op.invoke(INVOKEINTERFACE, "net/example/Clock", "partial", "(Z)F", true)), partial.steps());
        // A direct lowering on an Object parameter: the generator's cast per argument (binding-format.md 4.5).
        assertEquals(List.of(Op.load(ALOAD, 0), Op.cast("net/example/Mob"), Op.invoke(INVOKEVIRTUAL, "net/example/Mob", "getX", "()D", false)),
                glue(p, WORLD + ".camX").steps());
        assertEquals(new Glue(FRAME + ".fbo", "I", List.of(Op.constant(-1))), glue(p, FRAME + ".fbo"));
        assertTrue(p.glue().stream().noneMatch(g -> g.name().equals(FRAME + ".colorTex")), "an unsupported glue member keeps its stub");
    }

    @Test
    void chainWithConstAndNewarrayAndNew() throws IOException {
        Plan p = plan("fabric:26.1.2");
        assertEquals(new Glue(WORLD + ".matrix", "[F", List.of(Op.load(ALOAD, 0), Op.cast("net/example/Matrix"), Op.newArray("F", 16),
                Op.invoke(INVOKEVIRTUAL, "net/example/Matrix", "get", "([F)[F", false))), glue(p, WORLD + ".matrix"));
        assertEquals(new Glue(WORLD + ".newMatrix", "Ljava/lang/Object;", List.of(Op.newObject("net/example/Matrix"))), glue(p, WORLD + ".newMatrix"));

        Hook world = hooks(p, "WORLD_RENDER").get(0);
        assertEquals("(F[F[FDDD)V", world.dispatchDesc());
        assertEquals(List.of(
                List.of(Op.constant(0f)),
                List.of(Op.load(ALOAD, 1), invokeStatic(WORLD, "matrix", "(Ljava/lang/Object;)[F")),
                List.of(invokeStatic(WORLD, "newMatrix", "()Ljava/lang/Object;"), invokeStatic(WORLD, "matrix", "(Ljava/lang/Object;)[F")),
                List.of(Op.constant(0d)), List.of(Op.constant(0d)), List.of(Op.constant(0d))), world.payloadByDispatchParam());
    }

    @Test
    void mixinArrayGivesOneHookPerInjectionWithBeforeAndAtFromHooksJson() throws IOException {
        List<Hook> hud = hooks(plan("fabric:26.1.2"), "HUD_RENDER");
        assertEquals(2, hud.size());
        Hook record = hud.get(0);
        assertTrue(record.mixin());
        assertEquals("hudRecord", record.dispatch());
        assertEquals("(F)V", record.dispatchDesc());
        assertEquals("net/example/Game", record.mixinTarget());
        assertEquals("render(Lnet/example/Painter;Lnet/example/Clock;)V", record.mixinMethod());
        assertEquals(new At("TAIL", null, -1, null, null), record.at());
        assertEquals("(Lnet/example/Painter;Lnet/example/Clock;)V", record.handlerDesc());
        assertEquals(List.of(List.of(Op.load(ALOAD, 1), invokeStatic(GUI, "flush", "(Ljava/lang/Object;)V"))), record.before());
        assertEquals(List.of(List.of(Op.load(ALOAD, 2), invokeStatic(FRAME, "partialTick", "(Ljava/lang/Object;)F"))), record.payloadByDispatchParam());

        Hook replay = hud.get(1);
        assertEquals("hudReplay", replay.dispatch());
        assertEquals("frame()V", replay.mixinMethod());
        assertEquals(new At("INVOKE", "Lnet/example/Painter;paint()V", -1, null, "AFTER"), replay.at());
        assertEquals("()V", replay.handlerDesc());
        assertTrue(replay.before().isEmpty() && replay.payloadByDispatchParam().isEmpty());
    }

    @Test
    void nativeListenersGetFiltersAndGetterPayloads() throws IOException {
        Plan p = plan("neoforge:26.1.2");
        Hook tick = hooks(p, "CLIENT_TICK_END").get(0);
        assertFalse(tick.mixin());
        assertNull(tick.mixinTarget());
        assertEquals("net/loaderfx/TickEvent", tick.nativeEvent());
        assertEquals("game", tick.bus());
        assertEquals(List.of(new Filter(List.of(Op.load(ALOAD, 1), Op.field(GETFIELD, "net/loaderfx/TickEvent", "phase", "Lnet/loaderfx/Phase;")),
                Op.field(GETSTATIC, "net/loaderfx/Phase", "END", "Lnet/loaderfx/Phase;"))), tick.filters());
        assertEquals(List.of(), tick.payloadByDispatchParam());

        Hook hud = hooks(p, "HUD_RENDER").get(0);
        assertEquals(List.of(List.of(Op.load(ALOAD, 1), Op.invoke(INVOKEVIRTUAL, "net/loaderfx/HudEvent", "partialTick", "()F", false))),
                hud.payloadByDispatchParam());
        Hook world = hooks(p, "WORLD_RENDER").get(0);
        assertEquals(List.of(Op.constant(null)), world.payloadByDispatchParam().get(1));
    }

    @Test
    void aReferenceMissingFromTheJarFailsWithTheDiagnosticLine() throws IOException {
        Map<String, String> f = files();
        f.put("types/Player.json", player("official:net.example.Mob#getY()D"));
        PlanSource s = source(f);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.plan(s.targets().get(0)));
        assertTrue(e.getMessage().startsWith("UNRESOLVED fabric:26.1.2 Player.x ref official:net.example.Mob#getY()D"), e.getMessage());
        assertTrue(e.getMessage().contains("; next step: "), e.getMessage());
    }

    // ---------------------------------------------------------------------------------------- M2 RESOLVE: every lowering kind

    private static final String TARGETS_26 = "fabric:26.1.2";

    /** A mod class whose {@code init} runs {@code body}; it implements ClientMod so the plan has its entry. */
    private Path modJar(String name, java.util.function.Consumer<MethodVisitor> body) throws IOException {
        byte[] cls = simple("mod/Main", "java/lang/Object", ACC_PUBLIC, w -> {
            MethodVisitor m = w.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
            m.visitCode();
            body.accept(m);
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }, "com/blanket/api/ClientMod");
        return jar(name, Map.of("mod/Main", cls));
    }

    /** Classes only the API loader of a test has, such as an API enum ({@code ACC_ENUM}). */
    private static ClassLoader apiClasses(Map<String, byte[]> extra) {
        return new ClassLoader(PlannerTest.class.getClassLoader()) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                byte[] b = extra.get(name);
                return b != null ? new java.io.ByteArrayInputStream(b) : super.getResourceAsStream(name);
            }
        };
    }

    private Plan plan(Map<String, String> files, Path modJar, List<Path> embed, ClassLoader api, String id) throws IOException {
        PlanSource s = source(files, modJar, embed, api);
        for (TargetSpec t : s.targets()) if (t.id().equals(id)) return s.plan(t);
        throw new AssertionError("no target " + id);
    }

    private static String apiType(String name, String alias, String members) {
        return """
                { "schema": 1, "api": "com.blanket.api.%s",
                  "representation": [ { "when": {}, "alias": "%s" } ],
                  "members": { %s } }""".formatted(name, alias, members);
    }

    private static String rule(String member, String lower) {
        return "\"" + member + "\": [ { \"when\": {}, \"lower\": " + lower + " } ]";
    }

    @Test
    void newWithArgumentsBecomesConstruct() throws IOException {
        Map<String, String> f = files();
        f.put("types/Cell.json", apiType("Cell", "official:net.example.Pos",
                rule("of", "{ \"new\": \"official:net.example.Pos#<init>(III)V\" }")));
        Path m = modJar("cell.jar", v -> {
            for (int i = 0; i < 3; i++) v.visitInsn(ICONST_1);
            v.visitMethodInsn(INVOKESTATIC, "com/blanket/api/Cell", "of", "(III)Lcom/blanket/api/Cell;", false);
            v.visitInsn(POP);
        });
        Plan p = plan(f, m, List.of(), PlannerTest.class.getClassLoader(), TARGETS_26);
        assertEquals(Map.of("com/blanket/api/Cell.of(III)Lcom/blanket/api/Cell;", new Lowered.Construct("net/example/Pos", "(III)V")), p.members());
        assertEquals(Map.of("com/blanket/api/Cell", "net/example/Pos"), p.types());
    }

    @Test
    void adapterRuleBecomesAdapter() throws IOException {
        Map<String, String> f = files();
        f.put("types/Player.json", apiType("Player", "official:net.example.Hero", rule("x", "{ \"adapter\": \"unobf:com.blanket.adapters.Boxes#x\" }")));
        f.put("types/Cell.json", apiType("Cell", "official:net.example.Pos", rule("of", "{ \"adapter\": \"unobf:com.blanket.adapters.Cells#of\" }")));
        Path m = modJar("adapter.jar", v -> {
            v.visitInsn(ACONST_NULL);
            v.visitMethodInsn(INVOKEVIRTUAL, "com/blanket/api/Player", "x", "()D", false);
            v.visitInsn(POP2);
            for (int i = 0; i < 3; i++) v.visitInsn(ICONST_1);
            v.visitMethodInsn(INVOKESTATIC, "com/blanket/api/Cell", "of", "(III)Lcom/blanket/api/Cell;", false);
            v.visitInsn(POP);
        });
        // the built set: Boxes.x reads getX through Hero (declared by Mob), Cells.of constructs a Pos
        builtFiles.put("build/adapters/unobf/adapter-refs.json", """
                { "schema": 1, "set": "unobf", "namespace": "official",
                  "declaring": { "net/example/Hero#getX()D": "net/example/Mob" }, "overrides": {} }""".getBytes(StandardCharsets.UTF_8));
        builtFiles.put("build/adapters/unobf/com/blanket/adapters/unobf/Boxes.class",
                simple("com/blanket/adapters/unobf/Boxes", "java/lang/Object", ACC_PUBLIC | ACC_FINAL, w -> {
                    MethodVisitor x = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "x", "(Lnet/example/Hero;)D", null, null);
                    x.visitCode();
                    x.visitVarInsn(ALOAD, 0);
                    x.visitMethodInsn(INVOKEVIRTUAL, "net/example/Hero", "getX", "()D", false);
                    x.visitInsn(DRETURN);
                    x.visitMaxs(0, 0);
                    x.visitEnd();
                }));
        builtFiles.put("build/adapters/unobf/com/blanket/adapters/unobf/Cells.class",
                simple("com/blanket/adapters/unobf/Cells", "java/lang/Object", ACC_PUBLIC | ACC_FINAL, w -> {
                    MethodVisitor of = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "of", "(III)Lnet/example/Pos;", null, null);
                    of.visitCode();
                    of.visitTypeInsn(NEW, "net/example/Pos");
                    of.visitInsn(DUP);
                    of.visitVarInsn(ILOAD, 0);
                    of.visitVarInsn(ILOAD, 1);
                    of.visitVarInsn(ILOAD, 2);
                    of.visitMethodInsn(INVOKESPECIAL, "net/example/Pos", "<init>", "(III)V", false);
                    of.visitInsn(ARETURN);
                    of.visitMaxs(0, 0);
                    of.visitEnd();
                }));
        Plan p = plan(f, m, List.of(), PlannerTest.class.getClassLoader(), TARGETS_26);
        assertEquals(java.util.Set.of("com/blanket/adapters/unobf/Boxes", "com/blanket/adapters/unobf/Cells"), p.adapters().keySet());
        assertEquals(Map.of(
                "com/blanket/api/Player.x()D", new Lowered.Adapter("com/blanket/adapters/unobf/Boxes", "x", "(Lcom/blanket/api/Player;)D"),
                "com/blanket/api/Cell.of(III)Lcom/blanket/api/Cell;", new Lowered.Adapter("com/blanket/adapters/unobf/Cells", "of", "(III)Lcom/blanket/api/Cell;")), p.members());

        f.put("types/Player.json", apiType("Player", "official:net.example.Hero", rule("x", "{ \"adapter\": \"legacy:com.blanket.adapters.Boxes#x\" }")));
        PlanSource s = source(f, m, List.of(), PlannerTest.class.getClassLoader());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.plan(s.targets().get(0)));
        assertTrue(e.getMessage().startsWith("UNRESOLVED fabric:26.1.2 Player.x ref legacy:com.blanket.adapters.Boxes#x"), e.getMessage());
        assertTrue(e.getMessage().contains("adapter set legacy is not the set of 26.1.2, unobf"), e.getMessage());
    }

    @Test
    void glueMemberBoundToAnAdapterCallsItWithTheStubsOwnParametersAndReachesTheClosure() throws IOException {
        Map<String, String> f = files();
        f.put("glue/GlueGui.json", """
                { "schema": 1, "api": "com.blanket.runtime.glue.GlueGui", "members": {
                    "flush": [ { "when": {}, "lower": { "adapter": "unobf:com.blanket.adapters.Hooks#flush" } } ] } }""");
        builtFiles.put("build/adapters/unobf/adapter-refs.json", """
                { "schema": 1, "set": "unobf", "namespace": "official", "declaring": {}, "overrides": {} }""".getBytes(StandardCharsets.UTF_8));
        // flush(Object): the adapter does the instanceof itself, so the stub's Object parameter is passed as it is
        builtFiles.put("build/adapters/unobf/com/blanket/adapters/unobf/Hooks.class",
                simple("com/blanket/adapters/unobf/Hooks", "java/lang/Object", ACC_PUBLIC | ACC_FINAL, w -> {
                    MethodVisitor x = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "flush", "(Ljava/lang/Object;)V", null, null);
                    x.visitCode();
                    x.visitVarInsn(ALOAD, 0);
                    x.visitTypeInsn(INSTANCEOF, "net/example/Painter");
                    x.visitInsn(POP);
                    x.visitInsn(RETURN);
                    x.visitMaxs(0, 0);
                    x.visitEnd();
                }));
        Plan p = plan(f, mod, List.of(), PlannerTest.class.getClassLoader(), TARGETS_26);
        Glue flush = glue(p, GUI + ".flush");
        assertEquals(new Glue(GUI + ".flush", "V", List.of(Op.load(ALOAD, 0),
                invokeStatic("com/blanket/adapters/unobf/Hooks", "flush", "(Ljava/lang/Object;)V"))), flush);
        assertEquals(java.util.Set.of("com/blanket/adapters/unobf/Hooks"), p.adapters().keySet(), "the glue adapter's class is in the closure");
        assertEquals(new Lowered.Adapter("com/blanket/adapters/unobf/Hooks", "flush", "(Ljava/lang/Object;)V"), p.adapterCalls().get(GUI + ".flush"));
        assertFalse(p.members().containsKey(GUI + ".flush"), "glue stays out of the members: those owners are never shipped");

        f.put("glue/GlueGui.json", """
                { "schema": 1, "api": "com.blanket.runtime.glue.GlueGui", "members": {
                    "flush": [ { "when": {}, "lower": { "adapter": "legacy:com.blanket.adapters.Hooks#flush" } } ] } }""");
        PlanSource s = source(f, mod, List.of(), PlannerTest.class.getClassLoader());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.plan(s.targets().get(0)));
        assertTrue(e.getMessage().contains("adapter set legacy is not the set of 26.1.2, unobf"), e.getMessage());
    }

    static final String FEATURES = """
            { "schema": 1, "features": {
              "LATER": { "available": [ { "when": { "mc": "27.." } } ] },
              "ANYWHERE": { "available": [ { "when": {} } ] },
              "FABRIC_ONLY": { "available": [ { "when": { "loader": "fabric" } } ] } } }""";

    private Map<String, String> filesWithFeatures() {
        Map<String, String> f = files();
        f.put("features.json", FEATURES);
        return f;
    }

    @Test
    void requiresAbsentFeatureIsUnsupported() throws IOException {
        Map<String, String> f = filesWithFeatures();
        // x needs a feature neither target has and names a method the jar lacks: no plan failure, FOLD judges the uses
        f.put("types/Player.json", """
                { "schema": 1, "api": "com.blanket.api.Player",
                  "representation": [ { "when": {}, "alias": "official:net.example.Hero" } ],
                  "members": {
                    "x": [ { "when": {}, "requires": "LATER", "lower": { "invoke": "official:net.example.Mob#getY()D" } } ],
                    "y": [ { "when": {}, "lower": { "unsupported": "no y in the fixture" } } ],
                    "z": [ { "when": {}, "requires": "ANYWHERE", "lower": { "invoke": "official:net.example.Mob#getX()D" } } ] } }""");
        Path m = modJar("requires.jar", v -> {
            for (String axis : List.of("x", "y", "z")) {
                v.visitInsn(ACONST_NULL);
                v.visitMethodInsn(INVOKEVIRTUAL, "com/blanket/api/Player", axis, "()D", false);
                v.visitInsn(POP2);
            }
        });
        Plan p = plan(f, m, List.of(), PlannerTest.class.getClassLoader(), TARGETS_26);
        assertEquals(Map.of(
                "com/blanket/api/Player.x()D", new Lowered.Unsupported("Player.x requires the feature LATER, which fabric:26.1.2 does not have", "LATER"),
                "com/blanket/api/Player.y()D", new Lowered.Unsupported("no y in the fixture", null),
                "com/blanket/api/Player.z()D", new Lowered.Inline(List.of(Op.invoke(INVOKEVIRTUAL, "net/example/Mob", "getX", "()D", false)))), p.members());
    }

    @Test
    void featuresPerTarget() throws IOException {
        Plan fabric = plan(filesWithFeatures(), mod, List.of(), PlannerTest.class.getClassLoader(), TARGETS_26);
        Plan neo = plan(filesWithFeatures(), mod, List.of(), PlannerTest.class.getClassLoader(), "neoforge:26.1.2");
        assertEquals(Map.of("LATER", false, "ANYWHERE", true, "FABRIC_ONLY", true), fabric.features());
        assertEquals(Map.of("LATER", false, "ANYWHERE", true, "FABRIC_ONLY", false), neo.features());
    }

    @Test
    void classVersionMaxFromJava() throws IOException {
        Map<Integer, Integer> expected = Map.of(8, 52, 16, 60, 17, 61, 21, 65, 25, 69);
        for (Map.Entry<Integer, Integer> e : expected.entrySet()) {
            Map<String, String> f = files();
            f.put("targets.json", TARGETS.replace("\"java\": 25", "\"java\": " + e.getKey()));
            assertEquals(e.getValue(), plan(f, mod, List.of(), PlannerTest.class.getClassLoader(), TARGETS_26).classVersionMax(), "java " + e.getKey());
        }
    }

    @Test
    void enumConstantFieldKey() throws IOException {
        Map<String, String> f = files();
        f.put("types/Facing.json", apiType("Facing", "official:net.example.Heading",
                rule("NORTH", "{ \"get\": \"official:net.example.Heading#NORTH:Lnet/example/Heading;\" }") + ", "
                        + rule("SOUTH", "{ \"get\": \"official:net.example.Heading#SOUTH:Lnet/example/Heading;\" }")));
        Map<String, byte[]> apiOnly = Map.of("com/blanket/api/Facing.class",
                simple("com/blanket/api/Facing", "java/lang/Enum", ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_ENUM, w -> { }));
        Path m = modJar("enum.jar", v -> {
            String facing = "com/blanket/api/Facing", self = "Lcom/blanket/api/Facing;";
            v.visitFieldInsn(GETSTATIC, facing, "NORTH", self);
            v.visitMethodInsn(INVOKEVIRTUAL, facing, "ordinal", "()I", false);
            v.visitInsn(POP);
            v.visitFieldInsn(GETSTATIC, facing, "SOUTH", self);
            v.visitMethodInsn(INVOKEVIRTUAL, facing, "name", "()Ljava/lang/String;", false);
            v.visitInsn(POP);
            v.visitMethodInsn(INVOKESTATIC, facing, "values", "()[" + self, false);
            v.visitInsn(POP);
            v.visitLdcInsn("NORTH");
            v.visitMethodInsn(INVOKESTATIC, facing, "valueOf", "(Ljava/lang/String;)" + self, false);
            v.visitInsn(POP);
            // Object members of an alias class pass through as well
            v.visitInsn(ACONST_NULL);
            v.visitMethodInsn(INVOKEVIRTUAL, "com/blanket/api/Player", "toString", "()Ljava/lang/String;", false);
            v.visitInsn(POP);
        });
        Plan p = plan(f, m, List.of(), apiClasses(apiOnly), TARGETS_26);
        assertEquals(Map.of(
                "com/blanket/api/Facing.NORTH:Lcom/blanket/api/Facing;", new Lowered.Inline(List.of(Op.field(GETSTATIC, "net/example/Heading", "NORTH", "Lnet/example/Heading;"))),
                "com/blanket/api/Facing.SOUTH:Lcom/blanket/api/Facing;", new Lowered.Inline(List.of(Op.field(GETSTATIC, "net/example/Heading", "SOUTH", "Lnet/example/Heading;")))), p.members());
        assertEquals(Map.of("com/blanket/api/Facing", "net/example/Heading", "com/blanket/api/Player", "net/example/Hero"), p.types());

        // an enum call that is no pass-through member and has no rule still fails
        Path bad = modJar("enum-bad.jar", v -> {
            v.visitFieldInsn(GETSTATIC, "com/blanket/api/Facing", "NORTH", "Lcom/blanket/api/Facing;");
            v.visitMethodInsn(INVOKEVIRTUAL, "com/blanket/api/Facing", "opposite", "()Lcom/blanket/api/Facing;", false);
            v.visitInsn(POP);
        });
        PlanSource s = source(f, bad, List.of(), apiClasses(apiOnly));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.plan(s.targets().get(0)));
        assertTrue(e.getMessage().startsWith("UNBOUND fabric:26.1.2 Facing.opposite"), e.getMessage());
    }

    @Test
    void facadeMembersLowered() throws IOException {
        Map<String, String> f = files();
        f.put("types/Pads.json", """
                { "schema": 1, "api": "com.blanket.api.Pads",
                  "members": { "down": [ { "when": {}, "lower": { "invoke": "official:net.example.Pad#down(I)Z" } } ] } }""");
        Path m = modJar("facade.jar", v -> {
            v.visitInsn(ICONST_1);
            v.visitMethodInsn(INVOKESTATIC, "com/blanket/api/Pads", "down", "(I)Z", false);
            v.visitInsn(POP);
        });
        Plan p = plan(f, m, List.of(), PlannerTest.class.getClassLoader(), TARGETS_26);
        assertEquals(Map.of("com/blanket/api/Pads.down(I)Z", new Lowered.Inline(List.of(invokeStatic("net/example/Pad", "down", "(I)Z")))), p.members());
        assertEquals(Map.of(), p.types(), "a facade has no representation");
    }

    @Test
    void aUsedMemberWithoutARuleStillFails() throws IOException {
        Path m = modJar("norule.jar", v -> {
            v.visitInsn(ACONST_NULL);
            v.visitMethodInsn(INVOKEVIRTUAL, "com/blanket/api/Player", "z", "()D", false);
            v.visitInsn(POP2);
        });
        PlanSource s = source(files(), m, List.of(), PlannerTest.class.getClassLoader());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.plan(s.targets().get(0)));
        assertTrue(e.getMessage().startsWith("UNBOUND fabric:26.1.2 Player.z"), e.getMessage());
    }

    /** A class whose {@code run} makes the given calls: {opcode, owner, name, desc}. */
    private static byte[] caller(String name, Object[]... calls) {
        return simple(name, "java/lang/Object", ACC_PUBLIC, w -> {
            MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
            m.visitCode();
            for (Object[] c : calls) {
                if ((int) c[0] == INVOKEVIRTUAL) m.visitInsn(ACONST_NULL);
                m.visitMethodInsn((int) c[0], (String) c[1], (String) c[2], (String) c[3], false);
                m.visitInsn(c[3].toString().endsWith(")V") ? NOP : ((String) c[3]).endsWith(")D") ? POP2 : POP);
            }
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        });
    }

    private static Object[] box(String method) {
        return new Object[] {INVOKEVIRTUAL, "com/blanket/api/Hitbox", method, "()D"};
    }

    @Test
    void runtimeCallsJoinUsedSet() throws IOException {
        Map<String, String> f = files();
        String axes = String.join(", ", List.of("getMinX", "getMinY", "getMinZ", "getMaxX", "getMaxY").stream()
                .map(a -> rule(a, "{ \"invoke\": \"official:net.example.Aabb#" + a + "()D\" }")).toList());
        f.put("types/Hitbox.json", apiType("Hitbox", "official:net.example.Aabb", axes));
        f.put("types/Extra.json", apiType("Extra", "official:net.example.Mob", ""));
        f.put("types/Other.json", apiType("Other", "official:net.example.Hero", ""));
        Path embed = jar("embed.jar", Map.of(
                // reached from the root Dispatch, directly and through Helper
                "com/blanket/runtime/Dispatch", caller("com/blanket/runtime/Dispatch", box("getMinX"),
                        new Object[] {INVOKESTATIC, "com/blanket/runtime/Helper", "run", "()V"},
                        new Object[] {INVOKESTATIC, "com/blanket/api/Shown", "make", "()V"}),
                "com/blanket/runtime/Helper", caller("com/blanket/runtime/Helper", box("getMinY")),
                // the chosen GL backend is a root; the other backend and a class nothing names are not reached
                "com/blanket/runtime/Lwjgl3Gl", caller("com/blanket/runtime/Lwjgl3Gl", box("getMinZ")),
                "com/blanket/runtime/Lwjgl2Gl", caller("com/blanket/runtime/Lwjgl2Gl", box("getMaxX")),
                "com/blanket/runtime/Unused", caller("com/blanket/runtime/Unused", box("getMaxY")),
                // an owned API class is shipped, so its descriptors count; the compile-time class of an alias type is not shipped
                "com/blanket/api/Shown", simple("com/blanket/api/Shown", "java/lang/Object", ACC_PUBLIC, w -> {
                    method(w, ACC_PUBLIC | ACC_STATIC, "make", "()V");
                    method(w, ACC_PUBLIC | ACC_STATIC, "keep", "(Lcom/blanket/api/Extra;)V");
                }),
                "com/blanket/api/Hitbox", simple("com/blanket/api/Hitbox", "java/lang/Object", ACC_PUBLIC,
                        w -> method(w, ACC_PUBLIC, "inflate", "(Lcom/blanket/api/Other;)V"))));
        Plan p = plan(f, mod, List.of(embed), PlannerTest.class.getClassLoader(), TARGETS_26);
        assertEquals(java.util.Set.of(
                "com/blanket/api/Client.get()Lcom/blanket/api/Client;", "com/blanket/api/Client.player()Lcom/blanket/api/Player;", "com/blanket/api/Player.x()D",
                "com/blanket/api/Hitbox.getMinX()D", "com/blanket/api/Hitbox.getMinY()D", "com/blanket/api/Hitbox.getMinZ()D"), p.members().keySet());
        assertEquals(Map.of("com/blanket/api/Client", "net/example/Game", "com/blanket/api/Player", "net/example/Hero",
                "com/blanket/api/Hitbox", "net/example/Aabb", "com/blanket/api/Extra", "net/example/Mob"), p.types());

        // without the embedded classes the plan is the mod's alone
        Plan alone = plan(f, mod, List.of(), PlannerTest.class.getClassLoader(), TARGETS_26);
        assertEquals(java.util.Set.of("com/blanket/api/Client.get()Lcom/blanket/api/Client;", "com/blanket/api/Client.player()Lcom/blanket/api/Player;",
                "com/blanket/api/Player.x()D"), alone.members().keySet());
    }

    // ---------------------------------------------------------------------------------------- M3: which events are wired

    private static java.util.function.Consumer<MethodVisitor> reads(String... events) {
        return v -> {
            for (String e : events) {
                v.visitFieldInsn(GETSTATIC, "com/blanket/api/Events", e, "Lcom/blanket/api/EventSource;");
                v.visitInsn(POP);
            }
        };
    }

    /** An embedded class whose static {@code run} reads the events, then calls {@code next.run()} when next is not null. */
    private static byte[] eventReader(String name, String next, String... events) {
        return simple(name, "java/lang/Object", ACC_PUBLIC, w -> {
            MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
            m.visitCode();
            reads(events).accept(m);
            if (next != null) m.visitMethodInsn(INVOKESTATIC, next, "run", "()V", false);
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        });
    }

    private static List<String> events(Plan p) {
        return p.hooks().stream().map(Hook::event).distinct().sorted().toList();
    }

    @Test
    void theDispatchersOwnEventReadsWireNothing() throws IOException {
        // the runtime's Dispatch reads every event to fire it; only the mod's reads are subscriptions (events-and-hooks 1.5)
        Path embed = jar("dispatch.jar", Map.of("com/blanket/runtime/Dispatch",
                eventReader("com/blanket/runtime/Dispatch", null, "CLIENT_TICK_END", "HUD_RENDER", "WORLD_RENDER")));
        Path m = modJar("tick-only.jar", reads("CLIENT_TICK_END"));
        for (String id : List.of("fabric:26.1.2", "neoforge:26.1.2")) {
            Plan p = plan(files(), m, List.of(embed), PlannerTest.class.getClassLoader(), id);
            assertEquals(List.of("CLIENT_TICK_END"), events(p), id);
            assertEquals(1, p.hooks().size(), id + " " + p.hooks());
        }
    }

    @Test
    void anEmbeddedClassReachedFromARootThatSubscribesWiresItsEvent() throws IOException {
        // Boot (a root) reaches a testkit-like class that subscribes to WORLD_RENDER: its read counts like the mod's
        Path embed = jar("embed.jar", Map.of(
                "com/blanket/runtime/Dispatch", eventReader("com/blanket/runtime/Dispatch", null, "CLIENT_TICK_END", "HUD_RENDER", "WORLD_RENDER"),
                "com/blanket/runtime/Boot", eventReader("com/blanket/runtime/Boot", "com/blanket/runtime/testkit/Probe"),
                "com/blanket/runtime/testkit/Probe", eventReader("com/blanket/runtime/testkit/Probe", null, "WORLD_RENDER")));
        Path m = modJar("tick-only.jar", reads("CLIENT_TICK_END"));
        assertEquals(List.of("CLIENT_TICK_END", "WORLD_RENDER"), events(plan(files(), m, List.of(embed), PlannerTest.class.getClassLoader(), TARGETS_26)));

        // a class reached only from Dispatch subscribes too: the exception is Dispatch itself
        Path fromDispatch = jar("from-dispatch.jar", Map.of(
                "com/blanket/runtime/Dispatch", eventReader("com/blanket/runtime/Dispatch", "com/blanket/runtime/Helper", "HUD_RENDER"),
                "com/blanket/runtime/Helper", eventReader("com/blanket/runtime/Helper", null, "WORLD_RENDER")));
        assertEquals(List.of("CLIENT_TICK_END", "WORLD_RENDER"), events(plan(files(), m, List.of(fromDispatch), PlannerTest.class.getClassLoader(), TARGETS_26)));
    }

    @Test
    void theTestkitIsARootOnlyInTestkitPlans() throws IOException {
        // the testkit subscribes to WORLD_RENDER and nothing reaches it: only a --testkit plan wires that event
        Path embed = jar("testkit.jar", Map.of(
                "com/blanket/runtime/Dispatch", eventReader("com/blanket/runtime/Dispatch", null, "CLIENT_TICK_END", "HUD_RENDER", "WORLD_RENDER"),
                Planner.TESTKIT, eventReader(Planner.TESTKIT, null, "WORLD_RENDER")));
        Path m = modJar("tick-only.jar", reads("CLIENT_TICK_END"));
        for (boolean testkit : List.of(false, true)) {
            PlanSource s = source(files(), m, List.of(embed), PlannerTest.class.getClassLoader(), testkit);
            assertEquals(testkit, s.testkit());
            Plan p = s.plan(s.targets().stream().filter(t -> t.id().equals(TARGETS_26)).findFirst().orElseThrow());
            assertEquals(testkit ? List.of("CLIENT_TICK_END", "WORLD_RENDER") : List.of("CLIENT_TICK_END"), events(p), "testkit " + testkit);
        }

        // a --testkit plan without the testkit class in the embedded classes is a usage error
        Path without = jar("no-testkit.jar", Map.of("com/blanket/runtime/Dispatch", eventReader("com/blanket/runtime/Dispatch", null)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> source(files(), m, List.of(without), PlannerTest.class.getClassLoader(), true));
        assertTrue(e.getMessage().contains(Planner.TESTKIT), e.getMessage());
    }

    static final String HOOKS_WITH_START = HOOKS.replace("\"tick.end\":",
            "\"tick.start\": [ { \"when\": { \"loader\": \"fabric\" }, \"method\": \"official:net.example.Game#tick()V\", \"at\": \"HEAD\" } ],\n  \"tick.end\":");

    static final String TICK_START = """
            { "schema": 1, "event": "CLIENT_TICK_START", "recipes": [
              { "when": { "loader": "fabric" }, "mixin": { "hook": "tick.start", "dispatch": "tickStart" } },
              { "when": { "loader": "neoforge" }, "native": { "event": "loader:net.loaderfx.TickEvent", "bus": "game", "dispatch": "tickStart" } } ] }""";

    private static String via(String event, String via) {
        return """
                { "schema": 1, "event": "%s", "recipes": [ { "when": {}, "via": "%s" } ] }""".formatted(event, via);
    }

    private Map<String, String> filesWithWorldEvents() {
        Map<String, String> f = files();
        f.put("hooks.json", HOOKS_WITH_START);
        f.put("events/ClientTickStart.json", TICK_START);
        f.put("events/WorldJoin.json", via("WORLD_JOIN", "CLIENT_TICK_START"));
        f.put("events/WorldLeave.json", via("WORLD_LEAVE", "CLIENT_TICK_START"));
        return f;
    }

    @Test
    void viaEventsAreDeliveredByTheNamedEventsHookWiredOnce() throws IOException {
        Path all = modJar("world.jar", reads("CLIENT_TICK_START", "WORLD_JOIN", "WORLD_LEAVE"));
        Plan fabric = plan(filesWithWorldEvents(), all, List.of(), PlannerTest.class.getClassLoader(), TARGETS_26);
        assertEquals(1, fabric.hooks().size(), fabric.hooks().toString());
        Hook start = fabric.hooks().get(0);
        assertEquals("CLIENT_TICK_START", start.event());
        assertEquals("tickStart", start.dispatch());
        assertEquals("()V", start.dispatchDesc());
        assertEquals("tick.start", start.hookId());
        assertEquals("tick()V", start.mixinMethod());
        assertEquals(new At("HEAD", null, -1, null, null), start.at());

        Plan neo = plan(filesWithWorldEvents(), all, List.of(), PlannerTest.class.getClassLoader(), "neoforge:26.1.2");
        assertEquals(1, neo.hooks().size(), neo.hooks().toString());
        assertEquals("net/loaderfx/TickEvent", neo.hooks().get(0).nativeEvent());
        assertNull(neo.hooks().get(0).hookId(), "a native listener has no hooks.json id");

        // a mod that only joins worlds still gets the tick-start hook; with CLIENT_TICK_END it gets both, once each
        Path join = modJar("join.jar", reads("WORLD_JOIN", "CLIENT_TICK_END"));
        Plan p = plan(filesWithWorldEvents(), join, List.of(), PlannerTest.class.getClassLoader(), TARGETS_26);
        assertEquals(List.of("tick.end", "tick.start"), p.hooks().stream().map(Hook::hookId).sorted().toList());
    }

    @Test
    void viaToAnUnboundEventOrAChainFailsPlanning() throws IOException {
        Map<String, String> f = filesWithWorldEvents();
        f.put("events/WorldJoin.json", via("WORLD_JOIN", "SCREEN_OPEN"));
        PlanSource s = source(f, modJar("join.jar", reads("WORLD_JOIN")), List.of(), PlannerTest.class.getClassLoader());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.plan(s.targets().get(0)));
        assertTrue(e.getMessage().startsWith("UNBOUND fabric:26.1.2 event WORLD_JOIN"), e.getMessage());
        assertTrue(e.getMessage().contains("goes via SCREEN_OPEN on fabric:26.1.2 (events/WorldJoin.json), which no events/ file binds"), e.getMessage());

        Map<String, String> chain = filesWithWorldEvents();
        chain.put("events/WorldLeave.json", via("WORLD_LEAVE", "WORLD_JOIN"));
        PlanSource c = source(chain, modJar("leave.jar", reads("WORLD_LEAVE")), List.of(), PlannerTest.class.getClassLoader());
        IllegalStateException ce = assertThrows(IllegalStateException.class, () -> c.plan(c.targets().get(0)));
        assertTrue(ce.getMessage().startsWith("UNBOUND fabric:26.1.2 event WORLD_LEAVE"), ce.getMessage());
        assertTrue(ce.getMessage().contains("a via chain or cycle"), ce.getMessage());

        // the named event lacks a recipe for one target
        Map<String, String> partial = filesWithWorldEvents();
        partial.put("events/ClientTickStart.json", """
                { "schema": 1, "event": "CLIENT_TICK_START", "recipes": [
                  { "when": { "loader": "fabric" }, "mixin": { "hook": "tick.start", "dispatch": "tickStart" } } ] }""");
        PlanSource ps = source(partial, modJar("join2.jar", reads("WORLD_JOIN")), List.of(), PlannerTest.class.getClassLoader());
        TargetSpec neo = ps.targets().stream().filter(t -> t.id().equals("neoforge:26.1.2")).findFirst().orElseThrow();
        IllegalStateException pe = assertThrows(IllegalStateException.class, () -> ps.plan(neo));
        assertTrue(pe.getMessage().contains("event WORLD_JOIN") && pe.getMessage().contains("which has no recipe for neoforge:26.1.2 in events/ClientTickStart.json"),
                pe.getMessage());
    }

    @Test
    void nativeArrayGivesOneListenerPerBody() throws IOException {
        Map<String, String> f = files();
        f.put("events/HudRender.json", HUD.replace(
                "\"native\": { \"event\": \"loader:net.loaderfx.HudEvent\", \"bus\": \"game\", \"dispatch\": \"hud\",\n"
                        + "    \"payload\": { \"partialTick\": { \"getter\": \"loader:net.loaderfx.HudEvent#partialTick()F\" } } } } ] }",
                "\"native\": [\n"
                        + "    { \"event\": \"loader:net.loaderfx.HudEvent\", \"bus\": \"game\", \"dispatch\": \"hud\","
                        + " \"payload\": { \"partialTick\": { \"getter\": \"loader:net.loaderfx.HudEvent#partialTick()F\" } } },\n"
                        + "    { \"event\": \"loader:net.loaderfx.WorldEvent\", \"bus\": \"game\", \"dispatch\": \"hud\","
                        + " \"payload\": { \"partialTick\": { \"const\": 0 } } } ] } ] }"));
        assertTrue(f.get("events/HudRender.json").contains("WorldEvent"), "the fixture recipe was rewritten");
        Plan p = plan(f, mod, List.of(), PlannerTest.class.getClassLoader(), "neoforge:26.1.2");
        List<Hook> hud = hooks(p, "HUD_RENDER");
        assertEquals(List.of("net/loaderfx/HudEvent", "net/loaderfx/WorldEvent"), hud.stream().map(Hook::nativeEvent).toList());
        assertEquals(List.of(List.of(Op.constant(0f))), hud.get(1).payloadByDispatchParam());
        assertTrue(hud.stream().allMatch(h -> !h.mixin() && h.hookId() == null));

        // Mixin hooks carry their hooks.json ids
        assertEquals(List.of("gui.record", "gui.replay"), hooks(plan("fabric:26.1.2"), "HUD_RENDER").stream().map(Hook::hookId).toList());
    }
}
