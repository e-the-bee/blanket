// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;

import static com.blanket.core.lower.FakeGame.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternSwitchRejectTest implements Opcodes {
    private static Handle bootstrap(String name) {
        return new Handle(H_INVOKESTATIC, "java/lang/runtime/SwitchBootstraps", name,
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                        + "Ljava/lang/invoke/CallSite;", false);
    }

    private static List<Diagnostic> check(String name, String selectorDesc, Object... labels) {
        byte[] cls = LowerFixtures.define("mod/K", "java/lang/Object", cv -> LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "pick",
                "(" + selectorDesc + ")I", mv -> {
                    Label l = new Label();
                    mv.visitLabel(l);
                    mv.visitLineNumber(17, l);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitInsn(ICONST_0);
                    mv.visitInvokeDynamicInsn(name, "(" + selectorDesc + "I)I", bootstrap(name), labels);
                    mv.visitInsn(IRETURN);
                }));
        ClassNode node = LowerFixtures.node(cls);
        List<Diagnostic> errors = new ArrayList<>();
        Handles.rewrite(node, FakeGame.plan(), new Bridges(node), errors);
        return errors;
    }

    private static void assertRejected(List<Diagnostic> errors, String typeName) {
        assertEquals(1, errors.size(), errors.toString());
        Diagnostic d = errors.get(0);
        assertEquals(ErrorCode.FORBIDDEN, d.code());
        String text = d.format();
        assertTrue(text.contains("use if/instanceof or a classic switch"), text);
        assertTrue(text.contains("mod/K"), text);
        assertTrue(text.contains("pick"), text);
        assertTrue(text.contains(":17"), text);
        assertTrue(text.contains(typeName), text);
    }

    @Test
    void typeSwitchWithApiClassLabelsIsForbidden() {
        assertRejected(check("typeSwitch", "Ljava/lang/Object;", Type.getObjectType(PLAYER), Type.getObjectType(BLOCK_POS)),
                "com.blanket.api.Player");
    }

    @Test
    void typeSwitchOverAnApiSelectorIsForbidden() {
        assertRejected(check("typeSwitch", "L" + PLAYER + ";", "label"), "com.blanket.api.Player");
    }

    @Test
    void enumSwitchOverAnAliasEnumIsForbidden() {
        assertRejected(check("enumSwitch", "L" + DIRECTION + ";", "UP", "DOWN"), "com.blanket.api.Direction");
    }

    @Test
    void switchesOverOtherTypesAreAllowed() {
        assertEquals(List.of(), check("typeSwitch", "Ljava/lang/Object;", Type.getObjectType("java/lang/String"),
                Type.getObjectType("java/lang/Integer")));
        assertEquals(List.of(), check("enumSwitch", "Ljava/util/concurrent/TimeUnit;", "SECONDS"));
    }
}
