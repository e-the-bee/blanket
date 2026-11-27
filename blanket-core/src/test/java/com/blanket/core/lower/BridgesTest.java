// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Op;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static com.blanket.core.lower.FakeGame.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgesTest implements Opcodes {
    private static final String OF_KEY = BLOCK_POS + ".of(III)L" + BLOCK_POS + ";";
    private static final String OF_DESC = "(III)L" + BLOCK_POS + ";";
    private static final Lowered CONSTRUCT = new Lowered.Construct("game/BlockPos", "(III)V");

    private static ClassNode empty(int access) {
        ClassNode n = new ClassNode();
        n.visit(V21, access, "mod/K", null, "java/lang/Object", null);
        return n;
    }

    private static MethodNode find(ClassNode n, String name, String desc) {
        for (MethodNode m : n.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        throw new AssertionError("no method " + name + desc);
    }

    private static List<Integer> opcodes(MethodNode m) {
        List<Integer> out = new ArrayList<>();
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() >= 0) out.add(in.getOpcode());
        }
        return out;
    }

    @Test
    void newBridgeIsPrivateStaticSyntheticWithTheConstructorBody() {
        ClassNode n = empty(ACC_PUBLIC);
        Handle h = new Bridges(n).bridge(OF_KEY, OF_DESC, CONSTRUCT);
        assertEquals("blanket$new$BlockPos$of", h.getName());
        assertEquals(H_INVOKESTATIC, h.getTag());
        assertEquals("mod/K", h.getOwner());
        assertFalse(h.isInterface());
        MethodNode m = find(n, h.getName(), OF_DESC);
        assertEquals(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, m.access);
        assertEquals(List.of(Opcodes.NEW, DUP, ILOAD, ILOAD, ILOAD, INVOKESPECIAL, ARETURN), opcodes(m));
    }

    @Test
    void oneBridgePerMemberAndClass() {
        ClassNode n = empty(ACC_PUBLIC);
        Bridges b = new Bridges(n);
        Handle a = b.bridge(OF_KEY, OF_DESC, CONSTRUCT);
        Handle c = b.bridge(OF_KEY, OF_DESC, CONSTRUCT);
        assertSame(a, c);
        assertEquals(1, n.methods.size());
    }

    @Test
    void nameNeverClashesWithExistingMethods() {
        ClassNode n = empty(ACC_PUBLIC);
        n.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$main$0", "()V", null, null);
        n.visitMethod(ACC_PRIVATE | ACC_STATIC, "blanket$new$BlockPos$of", OF_DESC, null, null);
        Handle h = new Bridges(n).bridge(OF_KEY, OF_DESC, CONSTRUCT);
        assertEquals("blanket$new$BlockPos$of$2", h.getName());
        assertTrue(h.getName().startsWith("blanket$"), "javac's lambda$ names are never taken");
    }

    @Test
    void inlineBridgeLoadsReceiverThenRunsTheOps() {
        ClassNode n = empty(ACC_PUBLIC);
        String desc = "(L" + PLAYER + ";)D";
        Handle h = new Bridges(n).bridge(PLAYER + ".y()D", desc,
                new Lowered.Inline(List.of(Op.field(GETFIELD, ENTITY, "posY", "D"))));
        assertEquals("blanket$get$Player$y", h.getName());
        assertEquals(List.of(ALOAD, GETFIELD, DRETURN), opcodes(find(n, h.getName(), desc)));
    }

    @Test
    void constantBridgeIgnoresItsArguments() {
        ClassNode n = empty(ACC_PUBLIC);
        String desc = "(L" + PLAYER + ";)Z";
        Handle h = new Bridges(n).bridge(PLAYER + ".isFake()Z", desc, new Lowered.Inline(List.of(Op.constant(false))));
        assertEquals("blanket$const$Player$isFake", h.getName());
        assertEquals(List.of(ICONST_0, IRETURN), opcodes(find(n, h.getName(), desc)));
    }

    @Test
    void adapterBridgeCallsTheAdapter() {
        ClassNode n = empty(ACC_PUBLIC);
        String desc = "(L" + PLAYER + ";)Ljava/lang/String;";
        Handle h = new Bridges(n).bridge(PLAYER + ".name()Ljava/lang/String;", desc,
                new Lowered.Adapter("com/blanket/adapters/modern/Names", "name", desc));
        assertEquals(List.of(ALOAD, INVOKESTATIC, ARETURN), opcodes(find(n, h.getName(), desc)));
    }

    @Test
    void overloadsOfOneNameGetOneBridgeEach() {
        ClassNode n = empty(ACC_PUBLIC);
        Bridges b = new Bridges(n);
        b.bridge(OF_KEY, OF_DESC, CONSTRUCT);
        String d2 = "(DDD)L" + BLOCK_POS + ";";
        Handle h2 = b.bridge(BLOCK_POS + ".of(DDD)L" + BLOCK_POS + ";", d2, new Lowered.Construct("game/BlockPos", "(DDD)V"));
        assertEquals("blanket$new$BlockPos$of", h2.getName(), "different descriptor, same name is legal");
        assertEquals(2, n.methods.size());
        assertEquals(List.of(Opcodes.NEW, DUP, DLOAD, DLOAD, DLOAD, INVOKESPECIAL, ARETURN), opcodes(find(n, h2.getName(), d2)));
    }

    @Test
    void interfaceClassGetsAnInterfaceHandle() {
        ClassNode n = empty(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT);
        Handle h = new Bridges(n).bridge(OF_KEY, OF_DESC, CONSTRUCT);
        assertTrue(h.isInterface());
    }
}
