// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Op;
import com.blanket.core.plan.Plan;
import com.blanket.core.plan.PlanSource;
import com.blanket.core.plan.Planner;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.testsupport.RealData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-CLIENT: a fixture mod uses all 13 members of {@code Client}; on the real bindings and real game data (skipped
 * offline) each member's lowering kind, owner and name per target are the ones {@code lookup} printed (get, player,
 * world) or the adapter classes of the target's set.
 */
class BindingsClientTest implements Opcodes {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final String A = "com/blanket/api/";
    private static final List<String> TARGETS = List.of("legacy-fabric:1.8.9", "forge:1.8.9", "fabric:1.21.1", "neoforge:1.21.1", "fabric:26.1.2", "forge:1.20.1");
    private static final String[] SET = {"legacy", "legacy", "modern", "modern", "unobf", "modern"};
    private static final String CLIENT = "L" + A + "Client;";

    @TempDir
    Path tmp;

    private record Use(int opcode, String name, String desc) {}

    private static final List<Use> USES = List.of(
            new Use(INVOKESTATIC, "get", "()" + CLIENT),
            new Use(INVOKEVIRTUAL, "player", "()L" + A + "Player;"),
            new Use(INVOKEVIRTUAL, "world", "()L" + A + "World;"),
            new Use(INVOKEVIRTUAL, "guiWidth", "()I"),
            new Use(INVOKEVIRTUAL, "guiHeight", "()I"),
            new Use(INVOKEVIRTUAL, "guiScale", "()D"),
            new Use(INVOKEVIRTUAL, "openScreen", "(L" + A + "Screen;)V"),
            new Use(INVOKEVIRTUAL, "closeScreen", "()V"),
            new Use(INVOKEVIRTUAL, "isScreenOpen", "()Z"),
            new Use(INVOKEVIRTUAL, "crosshairTarget", "()L" + A + "Target;"),
            new Use(INVOKEVIRTUAL, "showMessage", "(Ljava/lang/String;)V"),
            new Use(INVOKESTATIC, "runOnClientThread", "(Ljava/lang/Runnable;)V"),
            new Use(INVOKEVIRTUAL, "translate", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"));

    /** member name -> expected lowering per target. */
    private static final Map<String, String[]> EXPECTED = new LinkedHashMap<>();

    private static String ad(int t, String cls, String name, String desc) {
        return "ADAPTER com/blanket/adapters/" + SET[t] + "/" + cls + "." + name + " " + desc;
    }

    static {
        EXPECTED.put("get", new String[] {"INVOKESTATIC net/minecraft/class_1600.method_2965 ()Lnet/minecraft/class_1600;", "INVOKESTATIC ave.A ()Lave;",
                "INVOKESTATIC net/minecraft/class_310.method_1551 ()Lnet/minecraft/class_310;",
                "INVOKESTATIC net/minecraft/client/Minecraft.getInstance ()Lnet/minecraft/client/Minecraft;",
                "INVOKESTATIC net/minecraft/client/Minecraft.getInstance ()Lnet/minecraft/client/Minecraft;",
                "INVOKESTATIC net/minecraft/client/Minecraft.m_91087_ ()Lnet/minecraft/client/Minecraft;"});
        EXPECTED.put("player", new String[] {"GETFIELD net/minecraft/class_1600.field_10310:Lnet/minecraft/class_518;", "GETFIELD ave.h:Lbew;",
                "GETFIELD net/minecraft/class_310.field_1724:Lnet/minecraft/class_746;",
                "GETFIELD net/minecraft/client/Minecraft.player:Lnet/minecraft/client/player/LocalPlayer;",
                "GETFIELD net/minecraft/client/Minecraft.player:Lnet/minecraft/client/player/LocalPlayer;",
                "GETFIELD net/minecraft/client/Minecraft.f_91074_:Lnet/minecraft/client/player/LocalPlayer;"});
        EXPECTED.put("world", new String[] {"GETFIELD net/minecraft/class_1600.field_3803:Lnet/minecraft/class_478;", "GETFIELD ave.f:Lbdb;",
                "GETFIELD net/minecraft/class_310.field_1687:Lnet/minecraft/class_638;",
                "GETFIELD net/minecraft/client/Minecraft.level:Lnet/minecraft/client/multiplayer/ClientLevel;",
                "GETFIELD net/minecraft/client/Minecraft.level:Lnet/minecraft/client/multiplayer/ClientLevel;",
                "GETFIELD net/minecraft/client/Minecraft.f_91073_:Lnet/minecraft/client/multiplayer/ClientLevel;"});
        String[][] adapters = {
                {"guiWidth", "ClientInfo", "guiWidth", "(" + CLIENT + ")I"},
                {"guiHeight", "ClientInfo", "guiHeight", "(" + CLIENT + ")I"},
                {"guiScale", "ClientInfo", "guiScale", "(" + CLIENT + ")D"},
                {"openScreen", "Screens", "open", "(" + CLIENT + "L" + A + "Screen;)V"},
                {"closeScreen", "Screens", "close", "(" + CLIENT + ")V"},
                {"isScreenOpen", "Screens", "isOpen", "(" + CLIENT + ")Z"},
                {"crosshairTarget", "Targets", "crosshair", "(" + CLIENT + ")L" + A + "Target;"},
                {"showMessage", "Chat", "showMessage", "(" + CLIENT + "Ljava/lang/String;)V"},
                {"runOnClientThread", "Tasks", "runOnClientThread", "(Ljava/lang/Runnable;)V"},
                {"translate", "Chat", "translate", "(" + CLIENT + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"}};
        for (String[] a : adapters) {
            String[] e = new String[6];
            for (int t = 0; t < 6; t++) {
                // 1.20.1 opens screens through the @Stubs("1.20.1") host
                String cls = a[0].equals("openScreen") && t == 5 ? "Screens1201" : a[1];
                e[t] = ad(t, cls, a[2], a[3]);
            }
            EXPECTED.put(a[0], e);
        }
    }

    private Path mod() throws IOException {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, ACC_PUBLIC, "com/example/client/ClientMod", null, "java/lang/Object", new String[] {A + "ClientMod"});
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
        m.visitCode();
        for (Use u : USES) {
            if (u.opcode() != INVOKESTATIC) m.visitInsn(ACONST_NULL);
            for (int i = 0; i < Type.getArgumentTypes(u.desc()).length; i++) m.visitInsn(ACONST_NULL);
            m.visitMethodInsn(u.opcode(), A + "Client", u.name(), u.desc(), false);
            int size = Type.getReturnType(u.desc()).getSize();
            if (size > 0) m.visitInsn(size == 2 ? POP2 : POP);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        Path jar = tmp.resolve("client.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("com/example/client/ClientMod.class"));
            z.write(w.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    private static String show(Lowered l) {
        if (l instanceof Lowered.Adapter a) return "ADAPTER " + a.owner() + "." + a.name() + " " + a.desc();
        if (l instanceof Lowered.Unsupported u) return "UNSUPPORTED " + u.reason();
        List<Op> ops = ((Lowered.Inline) l).ops();
        assertEquals(1, ops.size(), "one direct instruction: " + ops);
        Op o = ops.get(0);
        String op = switch (o.opcode()) {
            case INVOKESTATIC -> "INVOKESTATIC";
            case INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case GETFIELD -> "GETFIELD";
            default -> throw new AssertionError("opcode " + o.opcode());
        };
        return o.kind() == Op.Kind.FIELD ? op + " " + o.owner() + "." + o.name() + ":" + o.desc() : op + " " + o.owner() + "." + o.name() + " " + o.desc();
    }

    @Test
    void everyClientMemberLowersOnTheSixTargets() throws IOException {
        PlanSource source;
        try {
            source = Planner.plan(mod(), BINDINGS, List.of());
        } catch (IllegalStateException e) {
            throw RealData.skipIfOffline(e);
        }
        List<String> ids = new ArrayList<>();
        for (TargetSpec t : source.targets()) ids.add(t.id());
        assertEquals(TARGETS, ids);
        assertEquals(13, USES.size());
        for (int i = 0; i < TARGETS.size(); i++) {
            Plan p = source.plan(source.targets().get(i));
            for (Use u : USES) {
                String key = A + "Client." + u.name() + u.desc();
                Lowered l = p.members().get(key);
                assertTrue(l != null, TARGETS.get(i) + " has no plan entry for " + key);
                assertEquals(EXPECTED.get(u.name())[i], show(l), TARGETS.get(i) + " " + key);
            }
        }
    }
}
