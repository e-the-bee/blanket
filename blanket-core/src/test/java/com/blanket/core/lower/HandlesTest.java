// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Op;
import com.blanket.core.plan.Plan;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.blanket.core.lower.FakeGame.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlesTest implements Opcodes {
    static final Handle LMF = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);

    /** {@code mod/K.run()} that makes one {@code ToDoubleFunction}-like lambda from {@code impl}. */
    static byte[] lambdaOver(Handle impl, String samName, String samDesc, String instantiated, String iface) {
        return LowerFixtures.define("mod/K", "java/lang/Object", cv -> LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "run",
                "()L" + iface + ";", mv -> {
                    mv.visitInvokeDynamicInsn(samName, "()L" + iface + ";", LMF,
                            Type.getMethodType(samDesc), impl, Type.getMethodType(instantiated));
                    mv.visitInsn(ARETURN);
                }));
    }

    static InvokeDynamicInsnNode indy(ClassNode n, String method) {
        for (MethodNode m : n.methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof InvokeDynamicInsnNode d) return d;
            }
        }
        throw new AssertionError("no invokedynamic in " + method);
    }

    private record Result(ClassNode node, List<Diagnostic> errors, InvokeDynamicInsnNode indy) {
        Handle impl() {
            return (Handle) indy.bsmArgs[1];
        }
    }

    private static Result rewrite(byte[] cls, Plan plan) {
        ClassNode node = LowerFixtures.node(cls);
        List<Diagnostic> errors = new ArrayList<>();
        Handles.rewrite(node, plan, new Bridges(node), errors);
        return new Result(node, errors, indy(node, "run"));
    }

    private static boolean hasBridge(ClassNode n) {
        for (MethodNode m : n.methods) if (m.name.startsWith("blanket$")) return true;
        return false;
    }

    private static Result method(Handle impl, String samDesc, String instantiated) {
        return rewrite(lambdaOver(impl, "apply", samDesc, instantiated, "java/util/function/Function"), FakeGame.plan());
    }

    @Test
    void invokeLoweringRetargetsTheHandleWithoutABridge() {
        Result r = method(new Handle(H_INVOKEVIRTUAL, PLAYER, "x", "()D", false), "(Ljava/lang/Object;)Ljava/lang/Object;",
                "(L" + PLAYER + ";)D");
        assertEquals(List.of(), r.errors());
        assertEquals(new Handle(H_INVOKEVIRTUAL, ENTITY, "getX", "()D", false), r.impl());
        assertFalse(hasBridge(r.node()));
    }

    @Test
    void interfaceMemberGetsAnInterfaceHandle() {
        Result r = method(new Handle(H_INVOKEVIRTUAL, BLOCK_STATE, "id", "()I", false), "(Ljava/lang/Object;)Ljava/lang/Object;",
                "(L" + BLOCK_STATE + ";)I");
        assertEquals(new Handle(H_INVOKEINTERFACE, "game/IBlockState", "getId", "()I", true), r.impl());
        assertFalse(hasBridge(r.node()));
    }

    @Test
    void staticInvokeLoweringIsRetargetedDirectly() {
        Plan base = FakeGame.plan();
        Map<String, Lowered> members = new LinkedHashMap<>(base.members());
        members.put(VEC3 + ".of(DDD)L" + VEC3 + ";", new Lowered.Inline(List.of(
                Op.invoke(INVOKESTATIC, "game/Vec", "make", "(DDD)Lgame/Vec;", false))));
        Plan plan = LowerFixtures.plan(base.types(), members);
        Result r = rewrite(lambdaOver(new Handle(H_INVOKESTATIC, VEC3, "of", "(DDD)L" + VEC3 + ";", false), "apply",
                "(DDD)Ljava/lang/Object;", "(DDD)L" + VEC3 + ";", "java/util/function/Supplier"), plan);
        assertEquals(new Handle(H_INVOKESTATIC, "game/Vec", "make", "(DDD)Lgame/Vec;", false), r.impl());
        assertFalse(hasBridge(r.node()));
    }

    private void bridged(Handle impl, String instantiated, String bridgeDesc) {
        Result r = method(impl, "(Ljava/lang/Object;)Ljava/lang/Object;", instantiated);
        assertEquals(List.of(), r.errors());
        Handle h = r.impl();
        assertEquals(H_INVOKESTATIC, h.getTag());
        assertEquals("mod/K", h.getOwner());
        assertTrue(h.getName().startsWith("blanket$"), h.getName());
        assertEquals(bridgeDesc, h.getDesc());
        assertTrue(r.node().methods.stream().anyMatch(m -> m.name.equals(h.getName()) && m.desc.equals(h.getDesc())));
        assertEquals(Type.getMethodType(instantiated), r.indy().bsmArgs[2], "instantiatedMethodType is unchanged");
        assertEquals(Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"), r.indy().bsmArgs[0]);
    }

    @Test
    void getLoweringGetsABridge() {
        bridged(new Handle(H_INVOKEVIRTUAL, PLAYER, "y", "()D", false), "(L" + PLAYER + ";)D", "(L" + PLAYER + ";)D");
    }

    @Test
    void chainLoweringGetsABridge() {
        bridged(new Handle(H_INVOKEVIRTUAL, CLIENT, "score", "()I", false), "(L" + CLIENT + ";)I", "(L" + CLIENT + ";)I");
    }

    @Test
    void constLoweringGetsABridge() {
        bridged(new Handle(H_INVOKEVIRTUAL, PLAYER, "isFake", "()Z", false), "(L" + PLAYER + ";)Z", "(L" + PLAYER + ";)Z");
    }

    @Test
    void adapterLoweringGetsABridge() {
        String d = "(L" + PLAYER + ";)Ljava/lang/String;";
        bridged(new Handle(H_INVOKEVIRTUAL, PLAYER, "name", "()Ljava/lang/String;", false), d, d);
    }

    @Test
    void newLoweringGetsABridgeSharedWithCallSites() {
        String d = "(III)L" + BLOCK_POS + ";";
        Result r = rewrite(lambdaOver(new Handle(H_INVOKESTATIC, BLOCK_POS, "of", d, false), "apply",
                "(III)Ljava/lang/Object;", d, "java/util/function/Supplier"), FakeGame.plan());
        Handle h = r.impl();
        assertEquals("blanket$new$BlockPos$of", h.getName());
        assertEquals(H_INVOKESTATIC, h.getTag());
        assertEquals(d, h.getDesc());
    }

    @Test
    void stringConcatIsUntouched() {
        byte[] cls = LowerFixtures.define("mod/K", "java/lang/Object", cv -> LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "run",
                "(L" + PLAYER + ";)Ljava/lang/String;", mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitInvokeDynamicInsn("makeConcatWithConstants", "(L" + PLAYER + ";)Ljava/lang/String;",
                            new Handle(H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                            + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false),
                            "p=\u0001");
                    mv.visitInsn(ARETURN);
                }));
        ClassNode node = LowerFixtures.node(cls);
        List<Diagnostic> errors = new ArrayList<>();
        Handles.rewrite(node, FakeGame.plan(), new Bridges(node), errors);
        assertEquals(List.of(), errors);
        assertEquals(List.of("p=\u0001"), List.of(indy(node, "run").bsmArgs));
        assertFalse(hasBridge(node));
    }

    @Test
    void unboundMemberReferenceIsReported() {
        Result r = method(new Handle(H_INVOKEVIRTUAL, PLAYER, "z", "()D", false), "(Ljava/lang/Object;)Ljava/lang/Object;",
                "(L" + PLAYER + ";)D");
        assertEquals(1, r.errors().size());
        assertEquals(ErrorCode.UNBOUND, r.errors().get(0).code());
    }

    @Test
    void ownedClassHandleIsUntouched() {
        Handle owned = new Handle(H_INVOKESTATIC, "com/blanket/api/Blanket", "log", "()V", false);
        Result r = method(owned, "(Ljava/lang/Object;)Ljava/lang/Object;", "(L" + PLAYER + ";)D");
        assertEquals(owned, r.impl());
    }
}
