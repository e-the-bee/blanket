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
        Path dir = Files.createTempDirectory(tmp, "bindings");
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        return Planner.plan(mod, dir, data());
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
        assertEquals(Map.of(), p.features());
        assertEquals(Map.of(), p.adapters());
        try (var h = s.hierarchy(t)) {
            assertEquals(Optional.empty(), h.get("net/example/Game"));
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
}
