// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.blanket.core.plan.Filter;
import com.blanket.core.plan.Glue;
import com.blanket.core.plan.Hook;
import com.blanket.core.plan.Op;
import com.blanket.core.plan.Plan;
import com.blanket.core.plan.PlanSource;
import com.blanket.core.plan.Planner;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.testsupport.GlueCallers;
import com.blanket.core.testsupport.RealData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-EVT: a fixture mod subscribes to CLIENT_TICK_START, WORLD_JOIN and SCREEN_OPEN. On the real bindings and real game
 * data (skipped offline) the planner wires one tick-start hook (WORLD_JOIN and WORLD_LEAVE ride on it, D-015) and the
 * screen hook or hooks of each target, with the names {@code lookup}, {@code javap} and the loader patches gave:
 * <ul>
 *   <li>{@code lookup --ref 'intermediary:net/minecraft/class_310#method_1507(Lnet/minecraft/class_437;)V'} on
 *       fabric:1.21.1: {@code Minecraft.setScreen} is {@code method_1507} (int-1.21.1.tiny: {@code fgo.a(Lfod;)V}, mojmap
 *       {@code setScreen(Screen) -> a}); {@code tick()V} is {@code method_1574}.</li>
 *   <li>Legacy Fabric 1.8.9: {@code class_1600#method_2928(Lnet/minecraft/class_388;)V} is {@code setScreen}, {@code method_2954}
 *       is {@code tick} (lfyarn-1.8.9.tiny).</li>
 *   <li>26.1.2: {@code javap -p net.minecraft.client.Minecraft}: {@code public void setScreen(Screen)}, {@code public void tick()},
 *       {@code public Screen screen}.</li>
 *   <li>Forge 1.8.9: {@code javap -p GuiOpenEvent}: {@code public axu gui}. Its patched {@code Minecraft.a(axu)} (binpatch
 *       applied to client-1.8.9.jar) posts {@code GuiOpenEvent(aload_1)} with the new screen even when it is null.</li>
 *   <li>Forge 1.20.1 and NeoForge 1.21.1: the Minecraft patch posts {@code ScreenEvent.Opening} only for a non-null new screen
 *       and {@code ScreenEvent.Closing(old)} when the screen is replaced or closed, so each gets a second native body.</li>
 * </ul>
 */
class BindingsEventsTest implements Opcodes {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final String A = "com/blanket/api/";
    private static final String GLUE = "com/blanket/runtime/glue/GlueScreen";
    private static final List<String> TARGETS = List.of("legacy-fabric:1.8.9", "forge:1.8.9", "fabric:1.21.1", "neoforge:1.21.1", "fabric:26.1.2", "forge:1.20.1");
    private static final String SCREEN_OPEN = "screenOpen(Ljava/lang/Object;)V";

    @TempDir
    Path tmp;

    private Path mod(String name, String... events) throws IOException {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, ACC_PUBLIC, "com/example/events/EventsMod", null, "java/lang/Object", new String[] {A + "ClientMod"});
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
        m.visitCode();
        for (String e : events) {
            m.visitFieldInsn(GETSTATIC, A + "Events", e, "L" + A + "EventSource;");
            m.visitInsn(POP);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        Path jar = tmp.resolve(name + ".jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("com/example/events/EventsMod.class"));
            z.write(w.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    private PlanSource plan(Path mod) throws IOException {
        PlanSource source;
        try {
            // Dispatch.screenOpen and hudRecord call the screen glue in the real runtime
            source = Planner.plan(mod, BINDINGS, List.of(GlueCallers.jar(tmp.resolve("glue-callers.jar"),
                    "GlueScreen.kind", "GlueScreen.isModScreen", "GlueScreen.current")));
        } catch (IllegalStateException e) {
            throw RealData.skipIfOffline(e);
        }
        List<String> ids = new ArrayList<>();
        for (TargetSpec t : source.targets()) ids.add(t.id());
        assertEquals(TARGETS, ids);
        return source;
    }

    private static String text(Op op) {
        return switch (op.kind()) {
            case LOAD -> "ALOAD " + op.arg();
            case FIELD -> (op.opcode() == GETSTATIC ? "GETSTATIC " : "GETFIELD ") + op.owner() + "." + op.name() + ":" + op.desc();
            case INVOKE -> (op.opcode() == INVOKESTATIC ? "INVOKESTATIC " : "INVOKEVIRTUAL ") + op.owner() + "." + op.name() + " " + op.desc();
            case CONST -> "CONST " + op.arg();
            default -> throw new AssertionError(op);
        };
    }

    private static String text(List<List<Op>> payload) {
        List<String> out = new ArrayList<>();
        for (List<Op> chain : payload) {
            List<String> ops = new ArrayList<>();
            for (Op op : chain) ops.add(text(op));
            out.add(String.join(", ", ops));
        }
        return out.toString();
    }

    /** One line per hook: the injection or the listener, its filters, the Dispatch method and the payload chains. */
    private static List<String> hooks(Plan p) {
        List<String> out = new ArrayList<>();
        for (Hook h : p.hooks()) {
            StringBuilder b = new StringBuilder(h.mixin() ? "mixin " : "native ").append(h.event());
            if (h.mixin()) {
                b.append(' ').append(h.hookId()).append(' ').append(h.mixinTarget()).append(' ').append(h.mixinMethod()).append(' ').append(h.at().kind());
            } else {
                b.append(' ').append(h.nativeEvent()).append(' ').append(h.bus());
                for (Filter f : h.filters()) b.append(" filter ").append(text(f.getter().get(f.getter().size() - 1))).append(" == ").append(text(f.staticField()));
            }
            out.add(b.append(" -> ").append(h.dispatch()).append(h.dispatchDesc()).append(" payload ").append(text(h.payloadByDispatchParam())).toString());
        }
        Collections.sort(out);
        return out;
    }

    private static final String TICK_8 = "net/minecraftforge/fml/common/gameevent/TickEvent";
    private static final String TICK_1201 = "net/minecraftforge/event/TickEvent";
    private static final String SCREEN_8 = "net/minecraftforge/client/event/GuiOpenEvent";
    private static final String SCREEN_1201 = "net/minecraftforge/client/event/ScreenEvent";
    private static final String SCREEN_NEO = "net/neoforged/neoforge/client/event/ScreenEvent";
    private static final String MOJ_SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";

    private static String mixin(String event, String hook, String target, String method, String dispatch, String payload) {
        return "mixin " + event + " " + hook + " " + target + " " + method + " HEAD -> " + dispatch + " payload " + payload;
    }

    @Test
    void tickStartWorldJoinAndScreenOpenWireOneTickHookAndTheScreenHooksOnTheSixTargets() throws IOException {
        PlanSource source = plan(mod("events", "CLIENT_TICK_START", "WORLD_JOIN", "SCREEN_OPEN"));
        String tickFabric = "tickStart()V";
        String noPayload = "[]";
        List<List<String>> expected = List.of(
                List.of(mixin("CLIENT_TICK_START", "tick.start", "net/minecraft/class_1600", "method_2954()V", tickFabric, noPayload),
                        mixin("SCREEN_OPEN", "screen.open", "net/minecraft/class_1600", "method_2928(Lnet/minecraft/class_388;)V", SCREEN_OPEN, "[ALOAD 1]")),
                List.of("native CLIENT_TICK_START " + TICK_8 + "$ClientTickEvent game filter GETFIELD " + TICK_8 + ".phase:L" + TICK_8 + "$Phase; == GETSTATIC "
                                + TICK_8 + "$Phase.START:L" + TICK_8 + "$Phase; -> " + tickFabric + " payload " + noPayload,
                        "native SCREEN_OPEN " + SCREEN_8 + " game -> " + SCREEN_OPEN + " payload [ALOAD 1, GETFIELD " + SCREEN_8 + ".gui:Laxu;]"),
                List.of(mixin("CLIENT_TICK_START", "tick.start", "net/minecraft/class_310", "method_1574()V", tickFabric, noPayload),
                        mixin("SCREEN_OPEN", "screen.open", "net/minecraft/class_310", "method_1507(Lnet/minecraft/class_437;)V", SCREEN_OPEN, "[ALOAD 1]")),
                List.of("native CLIENT_TICK_START net/neoforged/neoforge/client/event/ClientTickEvent$Pre game -> " + tickFabric + " payload " + noPayload,
                        "native SCREEN_OPEN " + SCREEN_NEO + "$Closing game -> " + SCREEN_OPEN + " payload [CONST null]",
                        "native SCREEN_OPEN " + SCREEN_NEO + "$Opening game -> " + SCREEN_OPEN
                                + " payload [ALOAD 1, INVOKEVIRTUAL " + SCREEN_NEO + "$Opening.getNewScreen ()" + MOJ_SCREEN + "]"),
                List.of(mixin("CLIENT_TICK_START", "tick.start", "net/minecraft/client/Minecraft", "tick()V", tickFabric, noPayload),
                        mixin("SCREEN_OPEN", "screen.open", "net/minecraft/client/Minecraft", "setScreen(" + MOJ_SCREEN + ")V", SCREEN_OPEN, "[ALOAD 1]")),
                List.of("native CLIENT_TICK_START " + TICK_1201 + "$ClientTickEvent game filter GETFIELD " + TICK_1201 + ".phase:L" + TICK_1201 + "$Phase; == GETSTATIC "
                                + TICK_1201 + "$Phase.START:L" + TICK_1201 + "$Phase; -> " + tickFabric + " payload " + noPayload,
                        "native SCREEN_OPEN " + SCREEN_1201 + "$Closing game -> " + SCREEN_OPEN + " payload [CONST null]",
                        "native SCREEN_OPEN " + SCREEN_1201 + "$Opening game -> " + SCREEN_OPEN
                                + " payload [ALOAD 1, INVOKEVIRTUAL " + SCREEN_1201 + "$Opening.getNewScreen ()" + MOJ_SCREEN + "]"));
        for (int i = 0; i < TARGETS.size(); i++) {
            List<String> want = new ArrayList<>(expected.get(i));
            Collections.sort(want);
            assertEquals(want, hooks(source.plan(source.targets().get(i))), TARGETS.get(i));
        }
    }

    @Test
    void worldJoinAndWorldLeaveAloneWireOnlyTheTickStartHookAndAScreenOnlyModGetsNoTickHook() throws IOException {
        PlanSource source = plan(mod("world", "WORLD_JOIN", "WORLD_LEAVE"));
        PlanSource screens = plan(mod("screens", "SCREEN_OPEN"));
        for (int i = 0; i < TARGETS.size(); i++) {
            Plan p = source.plan(source.targets().get(i));
            assertEquals(1, p.hooks().size(), TARGETS.get(i) + " " + hooks(p));
            assertEquals("CLIENT_TICK_START", p.hooks().get(0).event(), TARGETS.get(i));
            assertEquals("tickStart", p.hooks().get(0).dispatch(), TARGETS.get(i));
            Plan s = screens.plan(screens.targets().get(i));
            assertTrue(s.hooks().stream().allMatch(h -> h.event().equals("SCREEN_OPEN")), TARGETS.get(i) + " " + hooks(s));
        }
    }

    /** Per target: adapter set, class of {@code kind}, class of {@code isModScreen}, then the steps of {@code current}. */
    @Test
    void glueScreenIsBoundOnTheSixTargets() throws IOException {
        PlanSource source = plan(mod("glue", "SCREEN_OPEN"));
        String[][] want = {
                {"legacy", "ScreenKinds", "ScreenKinds", "INVOKESTATIC net/minecraft/class_1600.method_2965 ()Lnet/minecraft/class_1600;",
                        "GETFIELD net/minecraft/class_1600.field_3816:Lnet/minecraft/class_388;"},
                {"legacy", "ScreenKinds", "ScreenKinds", "INVOKESTATIC ave.A ()Lave;", "GETFIELD ave.m:Laxu;"},
                {"modern", "ScreenKinds", "ScreenKindsHost", "INVOKESTATIC net/minecraft/class_310.method_1551 ()Lnet/minecraft/class_310;",
                        "GETFIELD net/minecraft/class_310.field_1755:Lnet/minecraft/class_437;"},
                {"modern", "ScreenKinds", "ScreenKindsHost", "INVOKESTATIC net/minecraft/client/Minecraft.getInstance ()Lnet/minecraft/client/Minecraft;",
                        "GETFIELD net/minecraft/client/Minecraft.screen:" + MOJ_SCREEN},
                {"unobf", "ScreenKinds", "ScreenKinds", "INVOKESTATIC net/minecraft/client/Minecraft.getInstance ()Lnet/minecraft/client/Minecraft;",
                        "GETFIELD net/minecraft/client/Minecraft.screen:" + MOJ_SCREEN},
                {"modern", "ScreenKinds", "ScreenKindsHost1201", "INVOKESTATIC net/minecraft/client/Minecraft.m_91087_ ()Lnet/minecraft/client/Minecraft;",
                        "GETFIELD net/minecraft/client/Minecraft.f_91080_:" + MOJ_SCREEN}};
        for (int i = 0; i < TARGETS.size(); i++) {
            String t = TARGETS.get(i);
            Plan p = source.plan(source.targets().get(i));
            String pkg = "com/blanket/adapters/" + want[i][0] + "/";
            assertEquals(List.of(Op.load(ALOAD, 0), Op.invoke(INVOKESTATIC, pkg + want[i][1], "kind", "(Ljava/lang/Object;)I", false)),
                    glue(p, GLUE + ".kind").steps(), t + " kind");
            assertEquals(List.of(Op.load(ALOAD, 0), Op.invoke(INVOKESTATIC, pkg + want[i][2], "isModScreen", "(Ljava/lang/Object;)Z", false)),
                    glue(p, GLUE + ".isModScreen").steps(), t + " isModScreen");
            Glue current = glue(p, GLUE + ".current");
            assertEquals("Ljava/lang/Object;", current.retDesc(), t);
            List<String> steps = new ArrayList<>();
            for (Op op : current.steps()) steps.add(text(op));
            assertEquals(List.of(want[i][3], want[i][4]), steps, t + " current");
            assertTrue(p.adapters().containsKey(pkg + want[i][1]) && p.adapters().containsKey(pkg + want[i][2]), t + " " + p.adapters().keySet());
        }
        // 1.20.1 has its own mod screen host: the closure must not reach the 1.21.1 one, which does not override there
        Plan forge1201 = source.plan(source.targets().get(5));
        assertFalse(forge1201.adapters().containsKey("com/blanket/adapters/modern/ScreenHost"), forge1201.adapters().keySet().toString());
        assertTrue(forge1201.adapters().containsKey("com/blanket/adapters/modern/ScreenHost1201"), forge1201.adapters().keySet().toString());
    }

    private static Glue glue(Plan p, String name) {
        for (Glue g : p.glue()) if (g.name().equals(name)) return g;
        throw new AssertionError("no glue " + name + " in " + p.glue().stream().map(Glue::name).toList());
    }
}
