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
 * B-PLAYER: a fixture mod uses every member of Player and ItemStack; on the real bindings and real game data (skipped
 * offline) each member's lowering on the six core targets is the one {@code lookup} printed: direct instructions
 * (opcode owner.name), an adapter (owner class), or unsupported (offHandItem on both 1.8.9 targets).
 */
class BindingsPlayerTest implements Opcodes {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final String A = "com/blanket/api/";
    private static final List<String> TARGETS = List.of("legacy-fabric:1.8.9", "forge:1.8.9", "fabric:1.21.1", "neoforge:1.21.1", "fabric:26.1.2", "forge:1.20.1");

    @TempDir
    Path tmp;

    private record Use(String owner, String name, String desc) {
        String key() {
            return owner + "." + name + desc;
        }
    }

    private static final List<Use> USES = new ArrayList<>();
    /** key -> expectation per target in TARGETS order; see {@link #show}. */
    private static final Map<String, String[]> EXPECTED = new LinkedHashMap<>();
    private static void member(String type, String name, String desc, String... perTarget) {
        Use u = new Use(A + type, name, desc);
        USES.add(u);
        assertEquals(6, perTarget.length, u.key());
        EXPECTED.put(u.key(), perTarget);
    }

    static {
        member("Player", "x", "()D",
                "G class_864.field_3252", "G pk.s", "V class_1297.method_23317", "V E.getX", "V E.getX", "V E.m_20185_");
        member("Player", "y", "()D",
                "G class_864.field_3253", "G pk.t", "V class_1297.method_23318", "V E.getY", "V E.getY", "V E.m_20186_");
        member("Player", "z", "()D",
                "G class_864.field_3254", "G pk.u", "V class_1297.method_23321", "V E.getZ", "V E.getZ", "V E.m_20189_");
        member("Player", "position", "()L" + A + "Vec3;",
                "I class_1061.method_10787", "I m.d", "V class_1297.method_19538", "V E.position", "V E.position", "V E.m_20182_");
        member("Player", "yaw", "()F",
                "G class_864.field_3258", "G pk.y", "V class_1297.method_36454", "V E.getYRot", "V E.getYRot", "V E.m_146908_");
        member("Player", "pitch", "()F",
                "G class_864.field_3193", "G pk.z", "V class_1297.method_36455", "V E.getXRot", "V E.getXRot", "V E.m_146909_");
        member("Player", "onGround", "()Z",
                "G class_864.field_3197", "G pk.C", "V class_1297.method_24828", "V E.onGround", "V E.onGround", "V E.m_20096_");
        member("Player", "health", "()F",
                "V class_1699.method_6122", "V pr.bn", "V class_1309.method_6032", "V LE.getHealth", "V LE.getHealth", "V LE.m_21223_");
        member("Player", "maxHealth", "()F",
                "V class_1699.method_6125", "V pr.bu", "V class_1309.method_6063", "V LE.getMaxHealth", "V LE.getMaxHealth", "V LE.m_21233_");
        member("Player", "foodLevel", "()I",
                "V class_988.method_3188 > V class_1001.method_3238", "V wn.cl > V xg.a", "V class_1657.method_7344 > V class_1702.method_7586", "V PL.getFoodData > V FD.getFoodLevel", "V PL.getFoodData > V FD.getFoodLevel", "V PL.m_36324_ > V FD.m_38702_");
        member("Player", "saturation", "()F",
                "V class_988.method_3188 > V class_1001.method_3249", "V wn.cl > V xg.e", "V class_1657.method_7344 > V class_1702.method_7589", "V PL.getFoodData > V FD.getSaturationLevel", "V PL.getFoodData > V FD.getSaturationLevel", "V PL.m_36324_ > V FD.m_38722_");
        member("Player", "mainHandItem", "()L" + A + "ItemStack;",
                "A legacy/Stacks.mainHand", "A legacy/Stacks.mainHand", "V class_1309.method_6047", "V LE.getMainHandItem", "V LE.getMainHandItem", "V LE.m_21205_");
        member("Player", "offHandItem", "()L" + A + "ItemStack;",
                "U OFFHAND", "U OFFHAND", "V class_1309.method_6079", "V LE.getOffhandItem", "V LE.getOffhandItem", "V LE.m_21206_");
        member("Player", "selectedSlot", "()I",
                "G class_988.field_3999 > G class_987.field_3966", "G wn.bi > G wm.c", "V class_1657.method_31548 > G class_1661.field_7545", "V PL.getInventory > G INV.selected", "V PL.getInventory > V INV.getSelectedSlot", "V PL.m_150109_ > G INV.f_35977_");
        member("Player", "inventoryItem", "(I)L" + A + "ItemStack;",
                "A legacy/Inventory.inventoryItem", "A legacy/Inventory.inventoryItem", "A modern/Inventory.inventoryItem", "A modern/Inventory.inventoryItem", "A unobf/Inventory.inventoryItem", "A modern/Inventory.inventoryItem");
        member("Player", "boundingBox", "()L" + A + "Box;",
                "V class_864.method_10945", "V pk.aR", "I class_5568.method_5829", "I EA.getBoundingBox", "V E.getBoundingBox", "I EA.m_20191_");
        member("ItemStack", "isEmpty", "()Z",
                "A legacy/Stacks.isEmpty", "A legacy/Stacks.isEmpty", "V class_1799.method_7960", "V IS.isEmpty", "V IS.isEmpty", "V IS.m_41619_");
        member("ItemStack", "count", "()I",
                "A legacy/Stacks.count", "A legacy/Stacks.count", "V class_1799.method_7947", "V IS.getCount", "V IS.getCount", "V IS.m_41613_");
        member("ItemStack", "itemId", "()Ljava/lang/String;",
                "A legacy/Stacks.itemId", "A legacy/Stacks.itemId", "A modern/Stacks.itemId", "A modern/Stacks.itemId", "A unobf/Stacks.itemId", "A modern/Stacks.itemId");
        member("ItemStack", "displayName", "()Ljava/lang/String;",
                "A legacy/Stacks.displayName", "A legacy/Stacks.displayName", "A modern/Stacks.displayName", "A modern/Stacks.displayName", "A unobf/Stacks.displayName", "A modern/Stacks.displayName");
    }

    /** One call of every member, with null/zero arguments (the planner scans, it does not verify). */
    private Path mod() throws IOException {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, ACC_PUBLIC, "com/example/player/PlayerMod", null, "java/lang/Object", new String[] {A + "ClientMod"});
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
        m.visitCode();
        for (Use u : USES) {
            m.visitInsn(ACONST_NULL);
            for (Type a : Type.getArgumentTypes(u.desc())) m.visitInsn(a.getSort() == Type.INT ? ICONST_0 : ACONST_NULL);
            m.visitMethodInsn(INVOKEVIRTUAL, u.owner(), u.name(), u.desc(), false);
            m.visitInsn(Type.getReturnType(u.desc()).getSize() == 2 ? POP2 : POP);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        Path jar = tmp.resolve("player.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("com/example/player/PlayerMod.class"));
            z.write(w.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    private static final Map<String, String> SHORT = Map.of(
            "E.", "net/minecraft/world/entity/Entity.", "LE.", "net/minecraft/world/entity/LivingEntity.",
            "PL.", "net/minecraft/world/entity/player/Player.", "FD.", "net/minecraft/world/food/FoodData.",
            "INV.", "net/minecraft/world/entity/player/Inventory.", "IS.", "net/minecraft/world/item/ItemStack.",
            "EA.", "net/minecraft/world/level/entity/EntityAccess.");

    /** Expands the owner abbreviations of a table entry (a class reference at the start of each step). */
    private static String expand(String entry) {
        StringBuilder out = new StringBuilder();
        for (String step : entry.split(" > ")) {
            String[] w = step.split(" ", 2);
            String ref = w[1];
            for (Map.Entry<String, String> e : SHORT.entrySet()) if (ref.startsWith(e.getKey())) ref = e.getValue() + ref.substring(e.getKey().length());
            if (ref.startsWith("class_")) ref = "net/minecraft/" + ref;
            if (out.length() > 0) out.append(" > ");
            out.append(w[0]).append(' ').append(ref);
        }
        return out.toString();
    }

    /**
     * The plan entry as a table string: {@code V/I/G owner.name} per step joined by {@code " > "} (invokevirtual,
     * invokeinterface, getfield), {@code A owner.name} for an adapter, {@code U FEATURE} for a feature-gated member.
     */
    private static String show(Lowered l) {
        if (l instanceof Lowered.Unsupported u) return "U " + u.feature();
        if (l instanceof Lowered.Adapter a) return "A " + a.owner().substring("com/blanket/adapters/".length()) + "." + a.name();
        List<String> parts = new ArrayList<>();
        for (Op o : ((Lowered.Inline) l).ops()) {
            String op = switch (o.opcode()) {
                case INVOKEVIRTUAL -> "V";
                case INVOKEINTERFACE -> "I";
                case GETFIELD -> "G";
                default -> throw new AssertionError("opcode " + o.opcode());
            };
            parts.add(op + " " + o.owner() + "." + o.name());
        }
        return String.join(" > ", parts);
    }

    @Test
    void everyMemberLowersAsLookupShowedOnTheSixTargets() throws IOException {
        PlanSource source;
        try {
            source = Planner.plan(mod(), BINDINGS, List.of());
        } catch (IllegalStateException e) {
            throw RealData.skipIfOffline(e);
        }
        List<String> ids = new ArrayList<>();
        for (TargetSpec t : source.targets()) ids.add(t.id());
        assertEquals(TARGETS, ids);
        for (int i = 0; i < TARGETS.size(); i++) {
            Plan p = source.plan(source.targets().get(i));
            for (Use u : USES) {
                Lowered l = p.members().get(u.key());
                assertTrue(l != null, TARGETS.get(i) + " has no plan entry for " + u.key());
                assertEquals(expand(EXPECTED.get(u.key())[i]), show(l), TARGETS.get(i) + " " + u.key());
            }
        }
    }
}
