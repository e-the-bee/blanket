// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Lowered;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReachabilityTest implements Opcodes {
    private static final String PLAYER = "com/blanket/api/Player";
    private static final String DESC = "()Lcom/blanket/api/ItemStack;";
    private static final Map<String, Lowered> MEMBERS =
            Map.of(PLAYER + ".offHandItem" + DESC, new Lowered.Unsupported("", "OFFHAND"));
    private static final String METHOD_DESC = "(L" + PLAYER + ";)V";

    private static ClassNode hud(boolean guarded) {
        return LowerFixtures.node(LowerFixtures.define("com/example/hud/HandsHud", "java/lang/Object", cv ->
                LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "render", METHOD_DESC, mv -> {
                    Label end = new Label();
                    if (guarded) {
                        FoldTestSupport.supports(mv, "OFFHAND");
                        mv.visitJumpInsn(IFEQ, end);
                    }
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "offHandItem", DESC, false);
                    mv.visitInsn(POP);
                    mv.visitLabel(end);
                    mv.visitInsn(RETURN);
                })));
    }

    private static List<Diagnostic> run(ClassNode n, Consumer<ClassNode> before) {
        List<Diagnostic> errors = new ArrayList<>();
        Folding.fold(n, Map.of("OFFHAND", false), "legacy-fabric:1.8.9", errors);
        DeadCode.remove(n);
        before.accept(n);
        Reachability.check(n, MEMBERS, "legacy-fabric:1.8.9", f -> "mc 1.9..", errors);
        return errors;
    }

    @Test
    void guardedUseIsNotReported() {
        assertEquals(List.of(), run(hud(true), n -> {}));
    }

    @Test
    void unguardedUseIsOneError() {
        List<Diagnostic> errors = run(hud(false), n -> {});
        assertEquals(1, errors.size());
        Diagnostic d = errors.get(0);
        assertEquals(ErrorCode.UNSUPPORTED, d.code());
        assertEquals("legacy-fabric:1.8.9", d.target());
        assertEquals("Player.offHandItem()", d.member());
        assertEquals("com.example.hud.HandsHud.render(Lcom/blanket/api/Player;)V calls Player.offHandItem() "
                + "which needs Feature.OFFHAND (available on mc 1.9..)", d.detail());
        assertEquals("Guard it with Blanket.supports(Feature.OFFHAND) or annotate the method @Requires(Feature.OFFHAND).", d.nextStep());
    }

    @Test
    void rangeIsOmittedWhenUnknown() {
        ClassNode n = hud(false);
        List<Diagnostic> errors = new ArrayList<>();
        Reachability.check(n, MEMBERS, "t", errors);
        assertEquals("com.example.hud.HandsHud.render(Lcom/blanket/api/Player;)V calls Player.offHandItem() which needs Feature.OFFHAND",
                errors.get(0).detail());
    }
}
