// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import com.blanket.core.plan.Glue;
import com.blanket.core.plan.Hook;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KEYS (S-07, events-and-hooks.md 4.7): on the real bindings and game data (skipped offline), a mod that calls
 * {@code KeyBinding.register} gets the key hand-over on all six targets, and a mod that only reads its keys does not.
 * Names are the ones {@code javap} and the mapping files give (M3 KEYS report): Fabric and Legacy Fabric hook the
 * options' {@code load()V} at HEAD with the options as {@code this}; Fabric shadows the final key array {@code @Mutable};
 * Forge 1.8.9 hands over in its {@code FMLInitializationEvent} method; Forge 1.20.1 and NeoForge listen to
 * {@code RegisterKeyMappingsEvent} on the mod bus.
 */
class BindingsKeyHandoverTest implements Opcodes {
    private static final Path BINDINGS = Path.of("..", "blanket-bindings");
    private static final String A = "com/blanket/api/";
    private static final String GLUE = "com/blanket/runtime/glue/GlueKeys";
    private static final List<String> TARGETS = List.of("legacy-fabric:1.8.9", "forge:1.8.9", "fabric:1.21.1", "neoforge:1.21.1", "fabric:26.1.2", "forge:1.20.1");

    @TempDir
    Path tmp;

    /** A mod whose init registers a key when {@code register} is true, and otherwise only polls one it was given. */
    private Path mod(boolean register) throws IOException {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, ACC_PUBLIC, "com/example/keys/KeysMod", null, "java/lang/Object", new String[] {A + "ClientMod"});
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
        m.visitCode();
        if (register) {
            m.visitLdcInsn("toggle");
            m.visitInsn(ACONST_NULL);
            m.visitLdcInsn("keys");
            m.visitMethodInsn(INVOKESTATIC, A + "KeyBinding", "register", "(Ljava/lang/String;L" + A + "Key;Ljava/lang/String;)L" + A + "KeyBinding;", false);
        } else {
            m.visitInsn(ACONST_NULL);
            m.visitTypeInsn(CHECKCAST, A + "KeyBinding");
        }
        m.visitMethodInsn(INVOKEVIRTUAL, A + "KeyBinding", "consumePress", "()Z", false);
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        Path jar = tmp.resolve(register ? "register.jar" : "poll.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("com/example/keys/KeysMod.class"));
            z.write(w.toByteArray());
            z.closeEntry();
        }
        return jar;
    }

    private PlanSource plan(boolean register) throws IOException {
        try {
            return Planner.plan(mod(register), BINDINGS, List.of());
        } catch (IllegalStateException e) {
            throw RealData.skipIfOffline(e);
        }
    }

    private static List<Hook> handOvers(Plan p) {
        return p.hooks().stream().filter(h -> h.event().equals("KEY_HANDOVER")).toList();
    }

    @Test
    void registerWiresTheHandOverOnTheSixTargets() throws IOException {
        PlanSource source = plan(true);
        List<String> ids = new ArrayList<>();
        for (TargetSpec t : source.targets()) ids.add(t.id());
        assertEquals(TARGETS, ids);

        Plan lf = source.plan(source.targets().get(0));
        Hook h = one(lf, "legacy-fabric:1.8.9");
        mixin(h, "net/minecraft/class_347", "method_865()V", List.of(), "legacy-fabric:1.8.9");
        glueField(lf, "setMappings", PUTFIELD, "net/minecraft/class_347", "field_945", "[Lnet/minecraft/class_327;");

        Plan forge189 = source.plan(source.targets().get(1));
        h = one(forge189, "forge:1.8.9");
        assertEquals(false, h.mixin());
        assertEquals("net/minecraftforge/fml/common/event/FMLInitializationEvent", h.nativeEvent());
        assertEquals("fml", h.bus());
        assertEquals("keysOptions", h.dispatch());
        List<Op> options = h.payloadByDispatchParam().get(0);
        Op last = options.get(options.size() - 1);
        assertEquals(GLUE + ".options()Ljava/lang/Object;", last.owner() + "." + last.name() + last.desc(), "forge:1.8.9 options from glue");
        glueField(forge189, "options", GETFIELD, "ave", "t", "Lavh;");
        glueField(forge189, "setMappings", PUTFIELD, "avh", "ax", "[Lavb;");

        Plan fabric = source.plan(source.targets().get(2));
        h = one(fabric, "fabric:1.21.1");
        mixin(h, "net/minecraft/class_315", "method_1636()V",
                List.of(new Hook.Field("net/minecraft/class_315", "field_1839", "[Lnet/minecraft/class_304;")), "fabric:1.21.1");
        glueField(fabric, "setMappings", PUTFIELD, "net/minecraft/class_315", "field_1839", "[Lnet/minecraft/class_304;");

        registerEvent(one(source.plan(source.targets().get(3)), "neoforge:1.21.1"), "net/neoforged/neoforge/client/event/RegisterKeyMappingsEvent");
        glueCall(source.plan(source.targets().get(3)), "net/neoforged/neoforge/client/event/RegisterKeyMappingsEvent",
                "(Lnet/minecraft/client/KeyMapping;)V");

        Plan off = source.plan(source.targets().get(4));
        h = one(off, "fabric:26.1.2");
        mixin(h, "net/minecraft/client/Options", "load()V",
                List.of(new Hook.Field("net/minecraft/client/Options", "keyMappings", "[Lnet/minecraft/client/KeyMapping;")), "fabric:26.1.2");

        Plan forge1201 = source.plan(source.targets().get(5));
        registerEvent(one(forge1201, "forge:1.20.1"), "net/minecraftforge/client/event/RegisterKeyMappingsEvent");
        glueCall(forge1201, "net/minecraftforge/client/event/RegisterKeyMappingsEvent", "(Lnet/minecraft/client/KeyMapping;)V");
    }

    @Test
    void aModThatDoesNotRegisterGetsNoHandOver() throws IOException {
        PlanSource source = plan(false);
        for (TargetSpec t : source.targets()) {
            assertEquals(List.of(), handOvers(source.plan(t)), t.id());
        }
    }

    private static Hook one(Plan p, String where) {
        List<Hook> found = handOvers(p);
        assertEquals(1, found.size(), where + ": " + p.hooks());
        return found.get(0);
    }

    private static void mixin(Hook h, String target, String method, List<Hook.Field> mutable, String where) {
        assertTrue(h.mixin(), where);
        assertEquals(target, h.mixinTarget(), where);
        assertEquals(method, h.mixinMethod(), where);
        assertEquals("HEAD", h.at().kind(), where);
        assertEquals("keys.options", h.hookId(), where);
        assertEquals("keysOptions", h.dispatch(), where);
        assertEquals("(Ljava/lang/Object;)V", h.dispatchDesc(), where);
        assertEquals(List.of(List.of(Op.load(ALOAD, 0))), h.payloadByDispatchParam(), where + ": the options are the hooked object");
        assertEquals(mutable, h.mutable(), where);
    }

    private static void registerEvent(Hook h, String event) {
        assertEquals(false, h.mixin(), event);
        assertEquals(event, h.nativeEvent());
        assertEquals("mod", h.bus(), event);
        assertEquals("keysRegister", h.dispatch(), event);
        assertEquals(List.of(List.of(Op.load(ALOAD, 1))), h.payloadByDispatchParam(), event + ": the event is the listener's argument");
    }

    private static Glue glue(Plan p, String member) {
        return p.glue().stream().filter(g -> g.name().equals(GLUE + "." + member)).findFirst()
                .orElseThrow(() -> new AssertionError(p.target().id() + ": no glue " + member + " in " + p.glue()));
    }

    private static void glueField(Plan p, String member, int opcode, String owner, String name, String desc) {
        Op op = glue(p, member).steps().stream().filter(o -> o.opcode() == opcode).findFirst()
                .orElseThrow(() -> new AssertionError(p.target().id() + " " + member + ": " + glue(p, member).steps()));
        assertEquals(owner + "." + name + ":" + desc, op.owner() + "." + op.name() + ":" + op.desc(), p.target().id() + " " + member);
    }

    private static void glueCall(Plan p, String owner, String desc) {
        Op op = glue(p, "register").steps().stream().filter(o -> o.opcode() == INVOKEVIRTUAL).findFirst()
                .orElseThrow(() -> new AssertionError(p.target().id() + " register: " + glue(p, "register").steps()));
        assertEquals(owner + ".register" + desc, op.owner() + "." + op.name() + op.desc(), p.target().id());
    }
}
