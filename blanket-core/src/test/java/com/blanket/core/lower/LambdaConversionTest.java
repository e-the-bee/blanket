// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.validate.Diagnostic;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.blanket.core.lower.FakeGame.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Research-log R-EXP-01 as a test: {@code Player::y} lowered to a field read cannot be a lambda's implementation
 * handle, and the bridge of {@link Handles} fixes it. A plain {@code ClassRemapper} stands in for pass 5.
 */
class LambdaConversionTest implements Opcodes {

    /** {@code mod/L.call(Player)D}: makes {@code ToDoubleFunction<Player> f = Player::y} and applies it. */
    private static ClassNode fixture() {
        String iface = "java/util/function/ToDoubleFunction";
        byte[] cls = LowerFixtures.define("mod/L", "java/lang/Object", cv -> LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC,
                "call", "(L" + PLAYER + ";)D", mv -> {
                    mv.visitInvokeDynamicInsn("applyAsDouble", "()L" + iface + ";", HandlesTest.LMF,
                            org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)D"),
                            new Handle(H_INVOKEVIRTUAL, PLAYER, "y", "()D", false),
                            org.objectweb.asm.Type.getMethodType("(L" + PLAYER + ";)D"));
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKEINTERFACE, iface, "applyAsDouble", "(Ljava/lang/Object;)D", true);
                    mv.visitInsn(DRETURN);
                }));
        return LowerFixtures.node(cls);
    }

    /** Pass 5 stand-in: the alias map only. */
    private static byte[] remap(ClassNode node) {
        ClassWriter cw = new ClassWriter(0);
        node.accept(new ClassRemapper(cw, new SimpleRemapper(FakeGame.types())));
        return cw.toByteArray();
    }

    private static double run(byte[] mod) throws Throwable {
        Map<String, byte[]> classes = new LinkedHashMap<>(FakeGame.gameClasses());
        classes.put("mod/L", mod);
        Class<?> c = LowerFixtures.load(classes, "mod/L");
        Class<?> player = c.getClassLoader().loadClass("game.LocalPlayer");
        Object p = player.getDeclaredConstructor().newInstance();
        player.getField("posY").setDouble(p, 64.5);
        return (double) LowerFixtures.invokeStatic(c, "call", new Class<?>[] {player}, p);
    }

    @Test
    void handlePointedAtTheFieldFailsLambdaConversion() {
        ClassNode node = fixture();
        HandlesTest.indy(node, "call").bsmArgs[1] = new Handle(H_GETFIELD, ENTITY, "posY", "D", false);
        BootstrapMethodError e = assertThrows(BootstrapMethodError.class, () -> run(remap(node)));
        assertInstanceOf(java.lang.invoke.LambdaConversionException.class, e.getCause(), String.valueOf(e.getCause()));
    }

    @Test
    void bridgeMakesTheFieldReadAValidLambda() throws Throwable {
        ClassNode node = fixture();
        List<Diagnostic> errors = new ArrayList<>();
        Handles.rewrite(node, FakeGame.plan(), new Bridges(node), errors);
        assertEquals(List.of(), errors);
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("blanket$get$Player$y")));
        assertEquals(64.5, run(remap(node)));
    }
}
