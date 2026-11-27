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
 * B-VALUE: a fixture mod uses every member of BlockPos, Vec3, Box and Direction; on the real bindings and real game
 * data (skipped offline) each member's lowering kind, owner and name are the ones {@code lookup} printed for the six
 * core targets. Vec3.scale, Box.inflate and Box.center have no direct lowering on 1.8.9; there they lower to a static
 * call of the legacy {@code Boxes} adapter.
 */
class BindingsValueTest implements Opcodes {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final String A = "com/blanket/api/";
    /** The targets in the order of every table row below. */
    private static final List<String> TARGETS = List.of("legacy-fabric:1.8.9", "forge:1.8.9", "fabric:1.21.1", "neoforge:1.21.1", "fabric:26.1.2", "forge:1.20.1");

    // runtime class of each alias type per target
    private static final String[] BLOCK_POS = {"net/minecraft/class_2552", "cj", "net/minecraft/class_2338", "net/minecraft/core/BlockPos", "net/minecraft/core/BlockPos", "net/minecraft/core/BlockPos"};
    private static final String[] VEC3I = {"net/minecraft/class_1167", "df", "net/minecraft/class_2382", "net/minecraft/core/Vec3i", "net/minecraft/core/Vec3i", "net/minecraft/core/Vec3i"};
    private static final String[] VEC3 = {"net/minecraft/class_236", "aui", "net/minecraft/class_243", "net/minecraft/world/phys/Vec3", "net/minecraft/world/phys/Vec3", "net/minecraft/world/phys/Vec3"};
    private static final String[] AABB = {"net/minecraft/class_231", "aug", "net/minecraft/class_238", "net/minecraft/world/phys/AABB", "net/minecraft/world/phys/AABB", "net/minecraft/world/phys/AABB"};
    private static final String[] DIR = {"net/minecraft/class_1354", "cq", "net/minecraft/class_2350", "net/minecraft/core/Direction", "net/minecraft/core/Direction", "net/minecraft/core/Direction"};

    @TempDir
    Path tmp;

    /** One API member used by the fixture mod. */
    private record Use(int opcode, String owner, String name, String desc) {
        String key() {
            return opcode == GETSTATIC ? owner + "." + name + ":" + desc : owner + "." + name + desc;
        }
    }

    private static final List<Use> USES = new ArrayList<>();
    /** key -> expected lowering per target: "NEW cls.<init>desc", "INVOKEVIRTUAL cls.name desc", "GETFIELD cls.name:desc" or "UNSUPPORTED". */
    private static final Map<String, String[]> EXPECTED = new LinkedHashMap<>();

    private static String api(String t) {
        return "L" + A + t + ";";
    }

    private static String rt(String desc, String[] bp, String[] v3i, String[] v3, String[] ab, String[] dir, int t) {
        return desc.replace("{BP}", "L" + bp[t] + ";").replace("{V3}", "L" + v3[t] + ";").replace("{AB}", "L" + ab[t] + ";")
                .replace("{DIR}", "L" + dir[t] + ";");
    }

    /** Registers an API member and its per-target expectation: {@code owners[t] + "." + names[t]} with the runtime descriptor. */
    private static void member(int opcode, String apiOwner, String name, String apiDesc, String rtOp, String[] owners, String[] names, String rtDesc) {
        USES.add(new Use(opcode, A + apiOwner, name, apiDesc));
        String[] e = new String[6];
        for (int t = 0; t < 6; t++) {
            String d = rt(rtDesc, BLOCK_POS, VEC3I, VEC3, AABB, DIR, t);
            e[t] = names[t] == null ? "UNSUPPORTED"
                    : rtOp + " " + owners[t] + "." + names[t] + (rtOp.equals("GETFIELD") || rtOp.equals("GETSTATIC") ? ":" : rtOp.equals("NEW") ? "" : " ") + d;
        }
        EXPECTED.put(USES.get(USES.size() - 1).key(), e);
    }

    /** On the two 1.8.9 targets the member lowers to a static call of the legacy adapter, receiver first. */
    private static void adapter(String apiOwner, String name, String apiDesc, String adapterName, String rtDesc) {
        String[] e = EXPECTED.get(A + apiOwner + "." + name + apiDesc);
        for (int t = 0; t < 2; t++) {
            e[t] = "INVOKESTATIC com/blanket/adapters/legacy/Boxes." + adapterName + " " + rtDesc;
        }
    }

    static {
        String bp = api("BlockPos"), v3 = api("Vec3"), ab = api("Box"), dir = api("Direction");
        // BlockPos
        member(INVOKESTATIC, "BlockPos", "of", "(III)" + bp, "NEW", BLOCK_POS, new String[] {"<init>", "<init>", "<init>", "<init>", "<init>", "<init>"}, "(III)V");
        String[] xs = {"method_10572", "n", "method_10263", "getX", "getX", "m_123341_"};
        String[] ys = {"method_10573", "o", "method_10264", "getY", "getY", "m_123342_"};
        String[] zs = {"method_10574", "p", "method_10260", "getZ", "getZ", "m_123343_"};
        member(INVOKEVIRTUAL, "BlockPos", "x", "()I", "INVOKEVIRTUAL", VEC3I, xs, "()I");
        member(INVOKEVIRTUAL, "BlockPos", "y", "()I", "INVOKEVIRTUAL", VEC3I, ys, "()I");
        member(INVOKEVIRTUAL, "BlockPos", "z", "()I", "INVOKEVIRTUAL", VEC3I, zs, "()I");
        member(INVOKEVIRTUAL, "BlockPos", "offset", "(" + dir + ")" + bp, "INVOKEVIRTUAL", BLOCK_POS,
                new String[] {"method_10490", "a", "method_10093", "relative", "relative", "m_121945_"}, "({DIR}){BP}");
        member(INVOKEVIRTUAL, "BlockPos", "offset", "(III)" + bp, "INVOKEVIRTUAL", BLOCK_POS,
                new String[] {"method_10487", "a", "method_10069", "offset", "offset", "m_7918_"}, "(III){BP}");
        // Vec3
        member(INVOKESTATIC, "Vec3", "of", "(DDD)" + v3, "NEW", VEC3, new String[] {"<init>", "<init>", "<init>", "<init>", "<init>", "<init>"}, "(DDD)V");
        member(INVOKEVIRTUAL, "Vec3", "x", "()D", "GETFIELD", VEC3, new String[] {"field_605", "a", "field_1352", "x", "x", "f_82479_"}, "D");
        member(INVOKEVIRTUAL, "Vec3", "y", "()D", "GETFIELD", VEC3, new String[] {"field_606", "b", "field_1351", "y", "y", "f_82480_"}, "D");
        member(INVOKEVIRTUAL, "Vec3", "z", "()D", "GETFIELD", VEC3, new String[] {"field_607", "c", "field_1350", "z", "z", "f_82481_"}, "D");
        member(INVOKEVIRTUAL, "Vec3", "add", "(" + v3 + ")" + v3, "INVOKEVIRTUAL", VEC3,
                new String[] {"method_9349", "e", "method_1019", "add", "add", "m_82549_"}, "({V3}){V3}");
        member(INVOKEVIRTUAL, "Vec3", "subtract", "(" + v3 + ")" + v3, "INVOKEVIRTUAL", VEC3,
                new String[] {"method_9348", "d", "method_1020", "subtract", "subtract", "m_82546_"}, "({V3}){V3}");
        member(INVOKEVIRTUAL, "Vec3", "scale", "(D)" + v3, "INVOKEVIRTUAL", VEC3,
                new String[] {null, null, "method_1021", "scale", "scale", "m_82490_"}, "(D){V3}");
        member(INVOKEVIRTUAL, "Vec3", "length", "()D", "INVOKEVIRTUAL", VEC3, new String[] {"method_612", "b", "method_1033", "length", "length", "m_82553_"}, "()D");
        member(INVOKEVIRTUAL, "Vec3", "distanceTo", "(" + v3 + ")D", "INVOKEVIRTUAL", VEC3,
                new String[] {"method_618", "f", "method_1022", "distanceTo", "distanceTo", "m_82554_"}, "({V3})D");
        // Box
        member(INVOKESTATIC, "Box", "of", "(DDDDDD)" + ab, "NEW", AABB, new String[] {"<init>", "<init>", "<init>", "<init>", "<init>", "<init>"}, "(DDDDDD)V");
        member(INVOKEVIRTUAL, "Box", "minX", "()D", "GETFIELD", AABB, new String[] {"field_582", "a", "field_1323", "minX", "minX", "f_82288_"}, "D");
        member(INVOKEVIRTUAL, "Box", "minY", "()D", "GETFIELD", AABB, new String[] {"field_583", "b", "field_1322", "minY", "minY", "f_82289_"}, "D");
        member(INVOKEVIRTUAL, "Box", "minZ", "()D", "GETFIELD", AABB, new String[] {"field_584", "c", "field_1321", "minZ", "minZ", "f_82290_"}, "D");
        member(INVOKEVIRTUAL, "Box", "maxX", "()D", "GETFIELD", AABB, new String[] {"field_585", "d", "field_1320", "maxX", "maxX", "f_82291_"}, "D");
        member(INVOKEVIRTUAL, "Box", "maxY", "()D", "GETFIELD", AABB, new String[] {"field_586", "e", "field_1325", "maxY", "maxY", "f_82292_"}, "D");
        member(INVOKEVIRTUAL, "Box", "maxZ", "()D", "GETFIELD", AABB, new String[] {"field_587", "f", "field_1324", "maxZ", "maxZ", "f_82293_"}, "D");
        member(INVOKEVIRTUAL, "Box", "inflate", "(D)" + ab, "INVOKEVIRTUAL", AABB, new String[] {null, null, "method_1014", "inflate", "inflate", "m_82400_"}, "(D){AB}");
        member(INVOKEVIRTUAL, "Box", "center", "()" + v3, "INVOKEVIRTUAL", AABB, new String[] {null, null, "method_1005", "getCenter", "getCenter", "m_82399_"}, "(){V3}");
        adapter("Vec3", "scale", "(D)" + v3, "scale", "(" + v3 + "D)" + v3);
        adapter("Box", "inflate", "(D)" + ab, "inflate", "(" + ab + "D)" + ab);
        adapter("Box", "center", "()" + v3, "center", "(" + ab + ")" + v3);
        // Direction
        member(INVOKEVIRTUAL, "Direction", "opposite", "()" + dir, "INVOKEVIRTUAL", DIR, new String[] {"method_10534", "d", "method_10153", "getOpposite", "getOpposite", "m_122424_"}, "(){DIR}");
        String[][] constants = {
                {"DOWN", "field_5200", "a", "field_11033", "DOWN", "DOWN", "DOWN"},
                {"UP", "field_5201", "b", "field_11036", "UP", "UP", "UP"},
                {"NORTH", "field_5202", "c", "field_11043", "NORTH", "NORTH", "NORTH"},
                {"SOUTH", "field_5203", "d", "field_11035", "SOUTH", "SOUTH", "SOUTH"},
                {"WEST", "field_5205", "e", "field_11039", "WEST", "WEST", "WEST"},
                {"EAST", "field_5204", "f", "field_11034", "EAST", "EAST", "EAST"}};
        for (String[] c : constants) {
            member(GETSTATIC, "Direction", c[0], dir, "GETSTATIC", DIR, new String[] {c[1], c[2], c[3], c[4], c[5], c[6]}, "{DIR}");
        }
    }

    /** The fixture mod: one call of every member, with null/zero arguments (the planner scans, it does not verify). */
    private Path mod() throws IOException {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, ACC_PUBLIC, "com/example/value/ValueMod", null, "java/lang/Object", new String[] {A + "ClientMod"});
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
        m.visitCode();
        for (Use u : USES) {
            if (u.opcode() == GETSTATIC) {
                m.visitFieldInsn(GETSTATIC, u.owner(), u.name(), u.desc());
                m.visitInsn(POP);
                continue;
            }
            Type[] args = Type.getArgumentTypes(u.desc());
            if (u.opcode() != INVOKESTATIC) m.visitInsn(ACONST_NULL);
            for (Type a : args) {
                switch (a.getSort()) {
                    case Type.INT -> m.visitInsn(ICONST_0);
                    case Type.DOUBLE -> m.visitInsn(DCONST_0);
                    default -> m.visitInsn(ACONST_NULL);
                }
            }
            m.visitMethodInsn(u.opcode(), u.owner(), u.name(), u.desc(), false);
            int size = Type.getReturnType(u.desc()).getSize();
            m.visitInsn(size == 2 ? POP2 : POP);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        Path jar = tmp.resolve("value.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("com/example/value/ValueMod.class"));
            z.write(w.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    /** The plan entry as a string comparable with {@link #EXPECTED}. */
    private static String show(Lowered l) {
        if (l instanceof Lowered.Construct c) return "NEW " + c.owner() + ".<init>" + c.ctorDesc();
        if (l instanceof Lowered.Unsupported) return "UNSUPPORTED";
        if (l instanceof Lowered.Adapter a) return "INVOKESTATIC " + a.owner() + "." + a.name() + " " + a.desc();
        List<Op> ops = ((Lowered.Inline) l).ops();
        assertEquals(1, ops.size(), "one direct instruction: " + ops);
        Op o = ops.get(0);
        String op = switch (o.opcode()) {
            case INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case INVOKESTATIC -> "INVOKESTATIC";
            case GETFIELD -> "GETFIELD";
            case GETSTATIC -> "GETSTATIC";
            default -> throw new AssertionError("opcode " + o.opcode());
        };
        return o.kind() == Op.Kind.FIELD ? op + " " + o.owner() + "." + o.name() + ":" + o.desc() : op + " " + o.owner() + "." + o.name() + " " + o.desc();
    }

    @Test
    void everyMemberLowersDirectlyOrIsExplicitlyUnsupportedOnTheSixTargets() throws IOException {
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
            for (Map.Entry<String, String[]> e : EXPECTED.entrySet()) {
                Lowered l = p.members().get(e.getKey());
                assertTrue(l != null, TARGETS.get(i) + " has no plan entry for " + e.getKey());
                assertEquals(e.getValue()[i], show(l), TARGETS.get(i) + " " + e.getKey());
            }
            assertEquals(BLOCK_POS[i], p.types().get(A + "BlockPos"), TARGETS.get(i));
            assertEquals(VEC3[i], p.types().get(A + "Vec3"), TARGETS.get(i));
            assertEquals(AABB[i], p.types().get(A + "Box"), TARGETS.get(i));
            assertEquals(DIR[i], p.types().get(A + "Direction"), TARGETS.get(i));
        }
    }
}
