// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.testsupport.RealData;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The M0 oracle: on the real bindings and real game data (skipped offline), the plans of the six core targets name what
 * {@code blanket-emit/m0-spike/gen/src/blanket/m0/Gen.java} hard-coded and ran in real games: the member lowerings, the
 * glue bodies, the hook targets, and the game members each hook body reaches (directly or through glue).
 */
class PlannerOracleTest implements Opcodes {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final String GLUE = "com/blanket/runtime/glue/";

    @TempDir
    static Path tmp;

    private static PlanSource source;

    // ---------------------------------------------------------------------------------------- the M0 sample's used set

    /** What the M0 sample uses: Client.get().player().x(), y(), z(), and the three events. */
    private static Path m0Mod() throws IOException {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, ACC_PUBLIC, "com/example/m0/M0Mod", null, "java/lang/Object", new String[] {"com/blanket/api/ClientMod"});
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
        m.visitCode();
        for (String axis : List.of("x", "y", "z")) {
            m.visitMethodInsn(INVOKESTATIC, "com/blanket/api/Client", "get", "()Lcom/blanket/api/Client;", false);
            m.visitMethodInsn(INVOKEVIRTUAL, "com/blanket/api/Client", "player", "()Lcom/blanket/api/Player;", false);
            m.visitMethodInsn(INVOKEVIRTUAL, "com/blanket/api/Player", axis, "()D", false);
            m.visitInsn(POP2);
        }
        for (String e : List.of("CLIENT_TICK_END", "HUD_RENDER", "WORLD_RENDER")) {
            m.visitFieldInsn(GETSTATIC, "com/blanket/api/Events", e, "Lcom/blanket/api/EventSource;");
            m.visitInsn(POP);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        Path jar = tmp.resolve("m0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("com/example/m0/M0Mod.class"));
            z.write(w.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    private static Plan plan(String id) {
        try {
            if (source == null) source = Planner.plan(m0Mod(), BINDINGS);
            for (TargetSpec t : source.targets()) if (t.id().equals(id)) return source.plan(t);
        } catch (IOException | RuntimeException e) {
            throw RealData.skipIfOffline(e);
        }
        throw new AssertionError(id + " is not supported in targets.json");
    }

    // ---------------------------------------------------------------------------------------- Gen.java, as data

    private static final String C_GET = "com/blanket/api/Client.get()Lcom/blanket/api/Client;";
    private static final String C_PLAYER = "com/blanket/api/Client.player()Lcom/blanket/api/Player;";

    /** Gen's {@code t.member} calls: Client.get, Client.player, Player.x/y/z. */
    private static Map<String, String> members(String get, String player, String op, String x, String y, String z) {
        Map<String, String> m = new TreeMap<>();
        m.put(C_GET, get);
        m.put(C_PLAYER, player);
        m.put("com/blanket/api/Player.x()D", op + " " + x);
        m.put("com/blanket/api/Player.y()D", op + " " + y);
        m.put("com/blanket/api/Player.z()D", op + " " + z);
        return m;
    }

    private static final String MC = "net/minecraft/client/Minecraft", RT = "com/mojang/blaze3d/pipeline/RenderTarget",
            WIN = "com/mojang/blaze3d/platform/Window", ENTITY = "net/minecraft/world/entity/Entity", LP = "net/minecraft/client/player/LocalPlayer";

    private static Map<String, String> mojangMembers(String getInstance, String player, String x, String y, String z) {
        return members("INVOKESTATIC " + MC + "." + getInstance + "()L" + MC + ";", "GETFIELD " + MC + "." + player + ":L" + LP + ";",
                "INVOKEVIRTUAL", ENTITY + "." + x + "()D", ENTITY + "." + y + "()D", ENTITY + "." + z + "()D");
    }

    /** Gen's {@code legacyGlue}: GlueWorld px..lastPz = get, player, entity field. */
    private static Map<String, List<String>> legacyGlue(String get, String player, String entity, String... fields) {
        Map<String, List<String>> g = new TreeMap<>();
        String[] names = {"px", "py", "pz", "lastPx", "lastPy", "lastPz"};
        for (int i = 0; i < 6; i++) g.put(GLUE + "GlueWorld." + names[i], List.of(get, player, "GETFIELD " + entity + "." + fields[i] + ":D"));
        return g;
    }

    /** Gen's {@code modernGlue}: GlueFrame fbo, fbWidth, fbHeight from the main target; guiWidth, guiHeight from the window. */
    private static Map<String, List<String>> modernGlue(String get, String target, String fb, String w, String h, String win, String guiW, String guiH) {
        Map<String, List<String>> g = new TreeMap<>();
        g.put(GLUE + "GlueFrame.fbo", List.of(get, target, fb));
        g.put(GLUE + "GlueFrame.fbWidth", List.of(get, target, w));
        g.put(GLUE + "GlueFrame.fbHeight", List.of(get, target, h));
        g.put(GLUE + "GlueFrame.guiWidth", List.of(get, win, guiW));
        g.put(GLUE + "GlueFrame.guiHeight", List.of(get, win, guiH));
        return g;
    }

    private static Map<String, List<String>> modernMojangGlue(String getInstance, String mainTarget, String fbId, String w, String h,
                                                              String getWindow, String guiW, String guiH) {
        return modernGlue("INVOKESTATIC " + MC + "." + getInstance + "()L" + MC + ";", "INVOKEVIRTUAL " + MC + "." + mainTarget + "()L" + RT + ";",
                "GETFIELD " + RT + "." + fbId + ":I", "GETFIELD " + RT + "." + w + ":I", "GETFIELD " + RT + "." + h + ":I",
                "INVOKEVIRTUAL " + MC + "." + getWindow + "()L" + WIN + ";", "INVOKEVIRTUAL " + WIN + "." + guiW + "()I", "INVOKEVIRTUAL " + WIN + "." + guiH + "()I");
    }

    private static final Map<String, Map<String, String>> MEMBERS = new TreeMap<>();
    private static final Map<String, Map<String, String>> TYPES = new TreeMap<>();
    private static final Map<String, Map<String, List<String>>> GLUE_BODIES = new TreeMap<>();
    /** Per target, per hook in plan order: the hook line, then the game members its body reaches. */
    private static final Map<String, Map<String, Set<String>>> HOOKS = new TreeMap<>();

    private static Map<String, String> types(String client, String player) {
        return Map.of("com/blanket/api/Client", client, "com/blanket/api/Player", player);
    }

    private static Map<String, Set<String>> hooks(Object... lineThenMembers) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < lineThenMembers.length; i += 2) {
            out.put((String) lineThenMembers[i], new TreeSet<>(List.of((String[]) lineThenMembers[i + 1])));
        }
        return out;
    }

    private static String[] of(String... s) {
        return s;
    }

    static {
        // legacyFabric189()
        String lc = "net/minecraft/class_1600", lp = "net/minecraft/class_518", le = "net/minecraft/class_864";
        MEMBERS.put("legacy-fabric:1.8.9", members("INVOKESTATIC " + lc + ".method_2965()L" + lc + ";", "GETFIELD " + lc + ".field_10310:L" + lp + ";",
                "GETFIELD", le + ".field_3252:D", le + ".field_3253:D", le + ".field_3254:D"));
        TYPES.put("legacy-fabric:1.8.9", types(lc, lp));
        GLUE_BODIES.put("legacy-fabric:1.8.9", legacyGlue("INVOKESTATIC " + lc + ".method_2965()L" + lc + ";", "GETFIELD " + lc + ".field_10310:L" + lp + ";",
                le, "field_3252", "field_3253", "field_3254", "field_3211", "field_3212", "field_3213"));
        HOOKS.put("legacy-fabric:1.8.9", hooks(
                "tickEnd mixin " + lc + " method_2954()V RETURN", of(),
                "hud mixin net/minecraft/class_371 method_9420(F)V TAIL", of(),
                "worldLegacy mixin net/minecraft/class_524 method_9776(IFJ)V INVOKE_STRING Lnet/minecraft/class_839;method_2360(Ljava/lang/String;)V ldc=hand", of()));

        // forge189()
        MEMBERS.put("forge:1.8.9", members("INVOKESTATIC ave.A()Lave;", "GETFIELD ave.h:Lbew;", "GETFIELD", "pk.s:D", "pk.t:D", "pk.u:D"));
        TYPES.put("forge:1.8.9", types("ave", "bew"));
        GLUE_BODIES.put("forge:1.8.9", legacyGlue("INVOKESTATIC ave.A()Lave;", "GETFIELD ave.h:Lbew;", "pk", "s", "t", "u", "P", "Q", "R"));
        String tick8 = "net/minecraftforge/fml/common/gameevent/TickEvent", overlay = "net/minecraftforge/client/event/RenderGameOverlayEvent";
        HOOKS.put("forge:1.8.9", hooks(
                "tickEnd native " + tick8 + "$ClientTickEvent filter GETFIELD " + tick8 + ".phase:L" + tick8 + "$Phase; == GETSTATIC " + tick8 + "$Phase.END:L" + tick8 + "$Phase;", of(),
                "hud native " + overlay + "$Post filter GETFIELD " + overlay + ".type:L" + overlay + "$ElementType; == GETSTATIC " + overlay + "$ElementType.ALL:L" + overlay + "$ElementType;",
                of("GETFIELD " + overlay + ".partialTicks:F"),
                "worldLegacy native net/minecraftforge/client/event/RenderWorldLastEvent", of("GETFIELD net/minecraftforge/client/event/RenderWorldLastEvent.partialTicks:F")));

        // fabric1211()
        String fc = "net/minecraft/class_310", rtgt = "net/minecraft/class_276", window = "net/minecraft/class_1041", dt = "net/minecraft/class_9779";
        MEMBERS.put("fabric:1.21.1", members("INVOKESTATIC " + fc + ".method_1551()L" + fc + ";", "GETFIELD " + fc + ".field_1724:Lnet/minecraft/class_746;",
                "INVOKEVIRTUAL", "net/minecraft/class_1297.method_23317()D", "net/minecraft/class_1297.method_23318()D", "net/minecraft/class_1297.method_23321()D"));
        TYPES.put("fabric:1.21.1", types(fc, "net/minecraft/class_746"));
        GLUE_BODIES.put("fabric:1.21.1", modernGlue("INVOKESTATIC " + fc + ".method_1551()L" + fc + ";", "INVOKEVIRTUAL " + fc + ".method_1522()L" + rtgt + ";",
                "GETFIELD " + rtgt + ".field_1476:I", "GETFIELD " + rtgt + ".field_1482:I", "GETFIELD " + rtgt + ".field_1481:I",
                "INVOKEVIRTUAL " + fc + ".method_22683()L" + window + ";", "INVOKEVIRTUAL " + window + ".method_4486()I", "INVOKEVIRTUAL " + window + ".method_4502()I"));
        String partial1211 = "INVOKEINTERFACE " + dt + ".method_60637(Z)F", toArray = "INVOKEVIRTUAL org/joml/Matrix4f.get([F)[F";
        HOOKS.put("fabric:1.21.1", hooks(
                "tickEnd mixin " + fc + " method_1574()V RETURN", of(),
                "hud mixin net/minecraft/class_329 method_1753(Lnet/minecraft/class_332;L" + dt + ";)V TAIL",
                of("INVOKEVIRTUAL net/minecraft/class_332.method_51452()V", partial1211),
                "world mixin net/minecraft/class_761 method_22710(L" + dt + ";ZLnet/minecraft/class_4184;Lnet/minecraft/class_757;Lnet/minecraft/class_765;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V TAIL",
                of(partial1211, toArray, "INVOKEVIRTUAL net/minecraft/class_4184.method_19326()Lnet/minecraft/class_243;",
                        "GETFIELD net/minecraft/class_243.field_1352:D", "GETFIELD net/minecraft/class_243.field_1351:D", "GETFIELD net/minecraft/class_243.field_1350:D")));

        // neoforge1211()
        MEMBERS.put("neoforge:1.21.1", mojangMembers("getInstance", "player", "getX", "getY", "getZ"));
        TYPES.put("neoforge:1.21.1", types(MC, LP));
        GLUE_BODIES.put("neoforge:1.21.1", modernMojangGlue("getInstance", "getMainRenderTarget", "frameBufferId", "width", "height", "getWindow", "getGuiScaledWidth", "getGuiScaledHeight"));
        String ev = "net/neoforged/neoforge/client/event/", stage = ev + "RenderLevelStageEvent", mdt = "net/minecraft/client/DeltaTracker";
        String partialMoj = "INVOKEINTERFACE " + mdt + ".getGameTimeDeltaPartialTick(Z)F", vec = "net/minecraft/world/phys/Vec3";
        HOOKS.put("neoforge:1.21.1", hooks(
                "tickEnd native " + ev + "ClientTickEvent$Post", of(),
                "hud native " + ev + "RenderGuiEvent$Post", of("INVOKEVIRTUAL " + ev + "RenderGuiEvent.getGuiGraphics()Lnet/minecraft/client/gui/GuiGraphics;",
                        "INVOKEVIRTUAL net/minecraft/client/gui/GuiGraphics.flush()V", "INVOKEVIRTUAL " + ev + "RenderGuiEvent.getPartialTick()L" + mdt + ";", partialMoj),
                "world native " + stage + " filter INVOKEVIRTUAL " + stage + ".getStage()L" + stage + "$Stage; == GETSTATIC " + stage + "$Stage.AFTER_LEVEL:L" + stage + "$Stage;",
                of("INVOKEVIRTUAL " + stage + ".getPartialTick()L" + mdt + ";", partialMoj, "INVOKEVIRTUAL " + stage + ".getProjectionMatrix()Lorg/joml/Matrix4f;", toArray,
                        "INVOKEVIRTUAL " + stage + ".getModelViewMatrix()Lorg/joml/Matrix4f;", "INVOKEVIRTUAL " + stage + ".getCamera()Lnet/minecraft/client/Camera;",
                        "INVOKEVIRTUAL net/minecraft/client/Camera.getPosition()L" + vec + ";", "GETFIELD " + vec + ".x:D", "GETFIELD " + vec + ".y:D", "GETFIELD " + vec + ".z:D")));

        // fabric2612()
        MEMBERS.put("fabric:26.1.2", mojangMembers("getInstance", "player", "getX", "getY", "getZ"));
        TYPES.put("fabric:26.1.2", types(MC, LP));
        String get = "INVOKESTATIC " + MC + ".getInstance()L" + MC + ";", target = "INVOKEVIRTUAL " + MC + ".getMainRenderTarget()L" + RT + ";";
        String gpu = "Lcom/mojang/blaze3d/textures/GpuTexture;", gl = "com/mojang/blaze3d/opengl/GlTexture", win = "INVOKEVIRTUAL " + MC + ".getWindow()L" + WIN + ";";
        Map<String, List<String>> g26 = new TreeMap<>();
        g26.put(GLUE + "GlueFrame.fbo", List.of("CONST -2"));
        g26.put(GLUE + "GlueFrame.colorTex", List.of(get, target, "INVOKEVIRTUAL " + RT + ".getColorTexture()" + gpu, "CHECKCAST " + gl, "INVOKEVIRTUAL " + gl + ".glId()I"));
        g26.put(GLUE + "GlueFrame.depthTex", List.of(get, target, "INVOKEVIRTUAL " + RT + ".getDepthTexture()" + gpu, "CHECKCAST " + gl, "INVOKEVIRTUAL " + gl + ".glId()I"));
        g26.put(GLUE + "GlueFrame.fbWidth", List.of(get, target, "GETFIELD " + RT + ".width:I"));
        g26.put(GLUE + "GlueFrame.fbHeight", List.of(get, target, "GETFIELD " + RT + ".height:I"));
        g26.put(GLUE + "GlueFrame.guiWidth", List.of(get, win, "INVOKEVIRTUAL " + WIN + ".getGuiScaledWidth()I"));
        g26.put(GLUE + "GlueFrame.guiHeight", List.of(get, win, "INVOKEVIRTUAL " + WIN + ".getGuiScaledHeight()I"));
        GLUE_BODIES.put("fabric:26.1.2", g26);
        String lambda = "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                + "Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
                + "Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;"
                + "Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;ZLorg/joml/Matrix4fc;)V";
        String cam = "net/minecraft/client/Camera";
        HOOKS.put("fabric:26.1.2", hooks(
                "tickEnd mixin " + MC + " tick()V RETURN", of(),
                "hudRecord mixin net/minecraft/client/gui/Gui extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;L" + mdt + ";)V TAIL", of(partialMoj),
                "hudReplay mixin net/minecraft/client/renderer/GameRenderer render(L" + mdt + ";Z)V INVOKE Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V shift=AFTER", of(),
                "world mixin net/minecraft/client/renderer/LevelRenderer lambda$addMainPass$0" + lambda + " RETURN",
                of(get, "GETFIELD " + MC + ".gameRenderer:Lnet/minecraft/client/renderer/GameRenderer;",
                        "INVOKEVIRTUAL net/minecraft/client/renderer/GameRenderer.getMainCamera()L" + cam + ";", "NEW org/joml/Matrix4f",
                        "INVOKEVIRTUAL " + cam + ".getViewRotationProjectionMatrix(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;", toArray,
                        "INVOKEVIRTUAL " + cam + ".position()L" + vec + ";", "GETFIELD " + vec + ".x:D", "GETFIELD " + vec + ".y:D", "GETFIELD " + vec + ".z:D")));

        // forge1201()
        MEMBERS.put("forge:1.20.1", mojangMembers("m_91087_", "f_91074_", "m_20185_", "m_20186_", "m_20189_"));
        TYPES.put("forge:1.20.1", types(MC, LP));
        GLUE_BODIES.put("forge:1.20.1", modernMojangGlue("m_91087_", "m_91385_", "f_83920_", "f_83915_", "f_83916_", "m_91268_", "m_85445_", "m_85446_"));
        String fev = "net/minecraftforge/client/event/", fstage = fev + "RenderLevelStageEvent", ftick = "net/minecraftforge/event/TickEvent";
        String pose = "com/mojang/blaze3d/vertex/PoseStack";
        HOOKS.put("forge:1.20.1", hooks(
                "tickEnd native " + ftick + "$ClientTickEvent filter GETFIELD " + ftick + ".phase:L" + ftick + "$Phase; == GETSTATIC " + ftick + "$Phase.END:L" + ftick + "$Phase;", of(),
                "hud native " + fev + "RenderGuiEvent$Post", of("INVOKEVIRTUAL " + fev + "RenderGuiEvent.getGuiGraphics()Lnet/minecraft/client/gui/GuiGraphics;",
                        "INVOKEVIRTUAL net/minecraft/client/gui/GuiGraphics.m_280262_()V", "INVOKEVIRTUAL " + fev + "RenderGuiEvent.getPartialTick()F"),
                "world native " + fstage + " filter INVOKEVIRTUAL " + fstage + ".getStage()L" + fstage + "$Stage; == GETSTATIC " + fstage + "$Stage.AFTER_TRANSLUCENT_BLOCKS:L" + fstage + "$Stage;",
                of("INVOKEVIRTUAL " + fstage + ".getPartialTick()F", "INVOKEVIRTUAL " + fstage + ".getProjectionMatrix()Lorg/joml/Matrix4f;", toArray,
                        "INVOKEVIRTUAL " + fstage + ".getPoseStack()L" + pose + ";", "INVOKEVIRTUAL " + pose + ".m_85850_()L" + pose + "$Pose;",
                        "INVOKEVIRTUAL " + pose + "$Pose.m_252922_()Lorg/joml/Matrix4f;", "INVOKEVIRTUAL " + fstage + ".getCamera()L" + cam + ";",
                        "INVOKEVIRTUAL " + cam + ".m_90583_()L" + vec + ";", "GETFIELD " + vec + ".f_82479_:D", "GETFIELD " + vec + ".f_82480_:D", "GETFIELD " + vec + ".f_82481_:D")));
    }

    // ---------------------------------------------------------------------------------------- rendering plans as text

    private static final Map<Integer, String> OPCODES = Map.of(INVOKESTATIC, "INVOKESTATIC", INVOKEVIRTUAL, "INVOKEVIRTUAL",
            INVOKEINTERFACE, "INVOKEINTERFACE", INVOKESPECIAL, "INVOKESPECIAL", GETFIELD, "GETFIELD", GETSTATIC, "GETSTATIC",
            PUTFIELD, "PUTFIELD", PUTSTATIC, "PUTSTATIC", ALOAD, "ALOAD", FLOAD, "FLOAD");

    private static String text(Op op) {
        String code = OPCODES.getOrDefault(op.opcode(), "");
        return switch (op.kind()) {
            case INVOKE -> code + " " + op.owner() + "." + op.name() + op.desc();
            case FIELD -> code + " " + op.owner() + "." + op.name() + ":" + op.desc();
            case CAST -> "CHECKCAST " + op.owner();
            case CONST -> "CONST " + op.arg();
            case NEW -> "NEW " + op.owner();
            case NEWARRAY -> "NEWARRAY " + op.desc() + " " + op.arg();
            case LOAD -> code + " " + op.arg();
        };
    }

    private static String line(Hook h) {
        StringBuilder b = new StringBuilder(h.dispatch());
        if (h.mixin()) {
            At at = h.at();
            b.append(" mixin ").append(h.mixinTarget()).append(' ').append(h.mixinMethod()).append(' ').append(at.kind());
            if (at.target() != null) b.append(' ').append(at.target());
            if (at.ordinal() >= 0) b.append(" ordinal=").append(at.ordinal());
            if (at.ldc() != null) b.append(" ldc=").append(at.ldc());
            if (at.shift() != null) b.append(" shift=").append(at.shift());
        } else {
            b.append(" native ").append(h.nativeEvent());
            for (Filter f : h.filters()) {
                b.append(" filter");
                for (Op op : f.getter()) if (op.kind() != Op.Kind.LOAD) b.append(' ').append(text(op));
                b.append(" == ").append(text(f.staticField()));
            }
        }
        return b.toString();
    }

    /** The game members a hook body reaches: its own invokes, fields and news, and those of the glue bodies it calls. */
    private static Set<String> reached(Hook h, Plan p) {
        Map<String, Glue> glue = new LinkedHashMap<>();
        for (Glue g : p.glue()) glue.put(g.name(), g);
        Set<String> out = new TreeSet<>();
        List<List<Op>> chains = new ArrayList<>(h.before());
        chains.addAll(h.payloadByDispatchParam());
        for (List<Op> chain : chains) collect(chain, glue, out);
        return out;
    }

    private static void collect(List<Op> ops, Map<String, Glue> glue, Set<String> out) {
        for (Op op : ops) {
            if (op.kind() == Op.Kind.INVOKE && op.owner().startsWith(GLUE)) {
                collect(glue.get(op.owner() + "." + op.name()).steps(), glue, out);
            } else if (op.kind() == Op.Kind.INVOKE || op.kind() == Op.Kind.FIELD || op.kind() == Op.Kind.NEW) {
                out.add(text(op));
            }
        }
    }

    // ---------------------------------------------------------------------------------------- tests

    @ParameterizedTest
    @ValueSource(strings = {"legacy-fabric:1.8.9", "forge:1.8.9", "fabric:1.21.1", "neoforge:1.21.1", "fabric:26.1.2", "forge:1.20.1"})
    void planNamesWhatGenHardCoded(String id) {
        Plan p = plan(id);

        Map<String, String> members = new TreeMap<>();
        for (Map.Entry<String, Lowered> e : p.members().entrySet()) {
            List<Op> ops = ((Lowered.Inline) e.getValue()).ops();
            assertEquals(1, ops.size(), id + " " + e.getKey() + " is one instruction in M0");
            members.put(e.getKey(), text(ops.get(0)));
        }
        assertEquals(MEMBERS.get(id), members, id + " members");
        assertEquals(TYPES.get(id), p.types(), id + " types");

        Map<String, List<String>> glue = new TreeMap<>();
        for (Glue g : p.glue()) {
            if (!GLUE_BODIES.get(id).containsKey(g.name())) continue;   // Gen's base-class defaults and hook-inlined conversions
            List<String> steps = new ArrayList<>();
            for (Op op : g.steps()) steps.add(text(op));
            glue.put(g.name(), steps);
        }
        assertEquals(GLUE_BODIES.get(id), glue, id + " glue bodies");

        Map<String, Set<String>> hooks = new LinkedHashMap<>();
        for (Hook h : p.hooks()) hooks.put(line(h), reached(h, p));
        assertEquals(new LinkedHashSet<>(HOOKS.get(id).keySet()).stream().toList(), hooks.keySet().stream().toList(), id + " hook targets");
        assertEquals(HOOKS.get(id), hooks, id + " hook bodies");
    }
}
