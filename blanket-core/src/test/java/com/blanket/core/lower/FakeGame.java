// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Op;
import com.blanket.core.plan.Plan;
import org.objectweb.asm.Opcodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Invented API types, game classes and lowerings for the tests of the member lowering. Nothing here is a Minecraft
 * name: the {@code game/} classes only exist in the tests' class loaders.
 */
final class FakeGame implements Opcodes {
    private FakeGame() {}

    static final String PLAYER = "com/blanket/api/Player";
    static final String BLOCK_POS = "com/blanket/api/BlockPos";
    static final String BLOCK_STATE = "com/blanket/api/BlockState";
    static final String DIRECTION = "com/blanket/api/Direction";
    static final String VEC3 = "com/blanket/api/Vec3";
    static final String CLIENT = "com/blanket/api/Client";

    static final String ENTITY = "game/Entity";
    static final String LOCAL_PLAYER = "game/LocalPlayer";

    /** API alias type to runtime class. */
    static Map<String, String> types() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put(PLAYER, LOCAL_PLAYER);
        t.put(BLOCK_POS, "game/BlockPos");
        t.put(BLOCK_STATE, "game/IBlockState");
        t.put(DIRECTION, "game/Dir");
        t.put(VEC3, "game/Vec");
        t.put(CLIENT, "game/Mc");
        return t;
    }

    /** One lowering for every kind the passes know. */
    static Map<String, Lowered> members() {
        Map<String, Lowered> m = new LinkedHashMap<>();
        m.put(PLAYER + ".x()D", new Lowered.Inline(List.of(Op.invoke(INVOKEVIRTUAL, ENTITY, "getX", "()D", false))));
        m.put(VEC3 + ".zero()L" + VEC3 + ";", new Lowered.Inline(List.of(Op.invoke(INVOKESTATIC, "game/Vec", "zero", "()Lgame/Vec;", false))));
        m.put(BLOCK_STATE + ".id()I", new Lowered.Inline(List.of(Op.invoke(INVOKEINTERFACE, "game/IBlockState", "getId", "()I", true))));
        m.put(PLAYER + ".y()D", new Lowered.Inline(List.of(Op.field(GETFIELD, ENTITY, "posY", "D"))));
        m.put(PLAYER + ".setY(D)V", new Lowered.Inline(List.of(Op.field(PUTFIELD, ENTITY, "posY", "D"))));
        m.put(VEC3 + ".origin()L" + VEC3 + ";", new Lowered.Inline(List.of(Op.field(GETSTATIC, "game/Vec", "ORIGIN", "Lgame/Vec;"))));
        m.put(VEC3 + ".setOrigin(L" + VEC3 + ";)V", new Lowered.Inline(List.of(Op.field(PUTSTATIC, "game/Vec", "ORIGIN", "Lgame/Vec;"))));
        m.put(PLAYER + ".isFake()Z", new Lowered.Inline(List.of(Op.constant(false))));
        m.put(CLIENT + ".score()I", new Lowered.Inline(List.of(
                Op.field(GETFIELD, "game/Mc", "player", "Lgame/Entity;"),
                Op.cast(LOCAL_PLAYER),
                Op.constant("k"),
                Op.newArray("F", 2),
                Op.invoke(INVOKEVIRTUAL, LOCAL_PLAYER, "stat", "(Ljava/lang/String;[F)I", false))));
        m.put(PLAYER + ".name()Ljava/lang/String;",
                new Lowered.Adapter("com/blanket/adapters/modern/Names", "name", "(L" + PLAYER + ";)Ljava/lang/String;"));
        m.put(BLOCK_POS + ".of(III)L" + BLOCK_POS + ";", new Lowered.Construct("game/BlockPos", "(III)V"));
        m.put(DIRECTION + ".UP:L" + DIRECTION + ";", new Lowered.Inline(List.of(Op.field(GETSTATIC, "game/Dir", "UP", "Lgame/Dir;"))));
        m.put(PLAYER + ".gone()V", new Lowered.Unsupported("not on this target", null));
        return m;
    }

    static Plan plan() {
        return LowerFixtures.plan(types(), members());
    }

    /** The game classes of the fake game, enough to load a lowered fixture: Entity, LocalPlayer. */
    static Map<String, byte[]> gameClasses() {
        Map<String, byte[]> c = new LinkedHashMap<>();
        c.put(ENTITY, LowerFixtures.define(ENTITY, "java/lang/Object", cv -> {
            cv.visitField(ACC_PUBLIC, "posX", "D", null, null).visitEnd();
            cv.visitField(ACC_PUBLIC, "posY", "D", null, null).visitEnd();
            LowerFixtures.defaultConstructor(cv, "java/lang/Object");
            LowerFixtures.method(cv, ACC_PUBLIC, "getX", "()D", mv -> {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, ENTITY, "posX", "D");
                mv.visitInsn(DRETURN);
            });
        }));
        c.put(LOCAL_PLAYER, LowerFixtures.define(LOCAL_PLAYER, ENTITY, cv -> LowerFixtures.defaultConstructor(cv, ENTITY)));
        return c;
    }
}
