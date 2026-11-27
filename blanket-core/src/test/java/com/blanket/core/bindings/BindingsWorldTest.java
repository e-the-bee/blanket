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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-WORLD: a fixture mod uses every member of World and BlockState; on the real bindings and real game data (skipped
 * offline) each member's lowering kind, owner and name are the ones {@code lookup} printed for the six core targets.
 * 1.8.9's {@code IBlockState}/{@code BlockView} members are interface calls; the members that differ in shape are
 * adapters ({@code States}, {@code Worlds}).
 */
class BindingsWorldTest implements Opcodes {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final String A = "com/blanket/api/";
    private static final List<String> TARGETS = List.of("legacy-fabric:1.8.9", "forge:1.8.9", "fabric:1.21.1", "neoforge:1.21.1", "fabric:26.1.2", "forge:1.20.1");
    private static final String[] SET = {"legacy", "legacy", "modern", "modern", "unobf", "modern"};

    @TempDir
    Path tmp;

    private record Use(int opcode, String owner, String name, String desc) {
        String key() {
            return owner + "." + name + desc;
        }
    }

    private static final List<Use> USES = new ArrayList<>();
    /** key -> expected per target: "INVOKEVIRTUAL|INVOKEINTERFACE cls.name desc" or "ADAPTER Class#name". */
    private static final Map<String, String[]> EXPECTED = new LinkedHashMap<>();


    private static void use(String owner, String name, String desc, String[] expected) {
        USES.add(new Use(INVOKEVIRTUAL, A + owner, name, desc));
        EXPECTED.put(A + owner + "." + name + desc, expected);
    }

    private static String[] adapters(String cls, String method) {
        String[] e = new String[6];
        for (int t = 0; t < 6; t++) e[t] = "ADAPTER com/blanket/adapters/" + SET[t] + "/" + cls + "#" + method;
        return e;
    }

    static {
        String bp = "L" + A + "BlockPos;", bs = "L" + A + "BlockState;", str = "Ljava/lang/String;";
        // World.blockState: the 1.8.9 row is on the BlockView interface, modern rows on BlockGetter (an interface) except 26.1.2 (Level)
        use("World", "blockState", "(" + bp + ")" + bs, new String[] {
                "INVOKEINTERFACE net/minecraft/class_1158.method_8580(Lnet/minecraft/class_2552;)Lnet/minecraft/class_2232;",
                "INVOKEINTERFACE adq.p(Lcj;)Lalz;",
                "INVOKEINTERFACE net/minecraft/class_1922.method_8320(Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;",
                "INVOKEINTERFACE net/minecraft/world/level/BlockGetter.getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                "INVOKEVIRTUAL net/minecraft/world/level/Level.getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                "INVOKEINTERFACE net/minecraft/world/level/BlockGetter.m_8055_(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"});
        use("World", "gameTime", "()J", new String[] {
                "INVOKEVIRTUAL net/minecraft/class_1150.method_4678()J",
                "INVOKEVIRTUAL adm.K()J",
                "INVOKEVIRTUAL net/minecraft/class_1937.method_8510()J",
                "INVOKEVIRTUAL net/minecraft/world/level/Level.getGameTime()J",
                "INVOKEINTERFACE net/minecraft/world/level/LevelAccessor.getGameTime()J",
                "INVOKEVIRTUAL net/minecraft/world/level/Level.m_46467_()J"});
        use("World", "dimension", "()" + str, adapters("Worlds", "dimension"));
        use("BlockState", "blockId", "()" + str, adapters("States", "blockId"));
        use("BlockState", "isAir", "()Z", new String[] {
                "ADAPTER com/blanket/adapters/legacy/States#isAir",
                "ADAPTER com/blanket/adapters/legacy/States#isAir",
                "INVOKEVIRTUAL net/minecraft/class_4970$class_4971.method_26215()Z",
                "INVOKEVIRTUAL net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase.isAir()Z",
                "INVOKEVIRTUAL net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase.isAir()Z",
                "INVOKEVIRTUAL net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase.m_60795_()Z"});
        use("BlockState", "propertyValue", "(" + str + ")" + str, adapters("States", "propertyValue"));
    }

    private Path mod() throws IOException {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, ACC_PUBLIC, "com/example/world/WorldMod", null, "java/lang/Object", new String[] {A + "ClientMod"});
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
        m.visitCode();
        for (Use u : USES) {
            m.visitInsn(ACONST_NULL);
            for (Type a : Type.getArgumentTypes(u.desc())) m.visitInsn(ACONST_NULL);
            m.visitMethodInsn(u.opcode(), u.owner(), u.name(), u.desc(), false);
            m.visitInsn(Type.getReturnType(u.desc()).getSize() == 2 ? POP2 : POP);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        Path jar = tmp.resolve("world.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("com/example/world/WorldMod.class"));
            z.write(w.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    private static String show(Lowered l) {
        if (l instanceof Lowered.Adapter a) return "ADAPTER " + a.owner() + "#" + a.name();
        assertTrue(l instanceof Lowered.Inline, "inline or adapter, not " + l);
        List<Op> ops = ((Lowered.Inline) l).ops();
        assertEquals(1, ops.size(), "one direct instruction: " + ops);
        Op o = ops.get(0);
        String op = switch (o.opcode()) {
            case INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case INVOKEINTERFACE -> "INVOKEINTERFACE";
            default -> throw new AssertionError("opcode " + o.opcode());
        };
        return op + " " + o.owner() + "." + o.name() + o.desc();
    }

    @Test
    void worldAndBlockStateMembersLowerOnTheSixTargets() throws IOException {
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
                assertNotNull(l, TARGETS.get(i) + " has no plan entry for " + e.getKey());
                String got = show(l);
                String want = e.getValue()[i];
                if (l instanceof Lowered.Adapter a) {
                    assertTrue(a.desc().startsWith("(L" + A), TARGETS.get(i) + " receiver is the API type: " + a.desc());
                }
                assertEquals(want, got, TARGETS.get(i) + " " + e.getKey());
            }
            assertNotNull(p.types().get(A + "World"), TARGETS.get(i));
            assertNotNull(p.types().get(A + "BlockState"), TARGETS.get(i));
        }
        assertEquals("net/minecraft/class_478", source.plan(source.targets().get(0)).types().get(A + "World"));
        assertEquals("net/minecraft/class_2232", source.plan(source.targets().get(0)).types().get(A + "BlockState"));
        assertEquals("net/minecraft/class_638", source.plan(source.targets().get(2)).types().get(A + "World"));
        assertEquals("net/minecraft/class_2680", source.plan(source.targets().get(2)).types().get(A + "BlockState"));
    }
}
