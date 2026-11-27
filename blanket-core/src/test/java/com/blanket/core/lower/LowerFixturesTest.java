// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.lower;

import com.blanket.core.plan.Lowered;
import com.blanket.core.plan.Plan;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The helpers of {@link LowerFixtures} and the contract types of this package. */
class LowerFixturesTest implements Opcodes {

    private static byte[] answer() {
        return LowerFixtures.define("fx/Answer", "java/lang/Object", cv -> {
            LowerFixtures.defaultConstructor(cv, "java/lang/Object");
            LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "pick", "(I)I", mv -> {
                mv.visitVarInsn(ILOAD, 0);
                org.objectweb.asm.Label other = new org.objectweb.asm.Label();
                mv.visitJumpInsn(IFEQ, other);
                mv.visitIntInsn(BIPUSH, 42);
                mv.visitInsn(IRETURN);
                mv.visitLabel(other);
                mv.visitInsn(ICONST_0);
                mv.visitInsn(IRETURN);
            });
        });
    }

    @Test
    void definedClassLoadsInAFreshLoaderAndRuns() throws Throwable {
        Map<String, byte[]> classes = Map.of("fx/Answer", answer());
        Class<?> c = LowerFixtures.load(classes, "fx/Answer");
        assertEquals(42, LowerFixtures.invokeStatic(c, "pick", new Class<?>[] {int.class}, 1));
        assertEquals(0, LowerFixtures.invokeStatic(c, "pick", new Class<?>[] {int.class}, 0));
        assertTrue(c.getClassLoader() != LowerFixturesTest.class.getClassLoader());
    }

    @Test
    void dumpIsTextifierOutput() {
        String text = LowerFixtures.dump(answer());
        assertTrue(text.contains("// class version 65.0"), text);
        assertTrue(text.contains("BIPUSH 42"), text);
    }

    @Test
    void badBytecodeFailsVerificationOnLoad() {
        byte[] bad = LowerFixtures.define("fx/Bad", "java/lang/Object", cv ->
                LowerFixtures.method(cv, ACC_PUBLIC | ACC_STATIC, "f", "()I", mv -> {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitInsn(IRETURN);
                }));
        assertThrows(VerifyError.class, () -> LowerFixtures.load(Map.of("fx/Bad", bad), "fx/Bad"));
    }

    @Test
    void handBuiltPlanCarriesTheContractFields() {
        Plan p = LowerFixtures.plan(Map.of("com/blanket/api/Client", "net/x/Game"),
                Map.of("com/blanket/api/Client.get()Lcom/blanket/api/Client;", new Lowered.Construct("net/x/Game", "()V")), Map.of("OFFHAND", false));
        assertEquals(65, p.classVersionMax());
        assertEquals(Map.of("OFFHAND", false), p.features());
        assertTrue(p.adapters().isEmpty());
        assertEquals(1, LowerFixtures.withAdapters(p, Map.of("a", new byte[0])).adapters().size());
    }

    @Test
    void hierarchyEmptyAndLayered() throws Exception {
        assertEquals(Optional.empty(), Hierarchy.empty().get("a/B"));
        Hierarchy.Facts facts = new Hierarchy.Facts("java/lang/Object", List.of("a/I"), false);
        try (Hierarchy h = Hierarchy.layered(Map.of("a/B", facts), Hierarchy.layered(Map.of("a/C", facts), Hierarchy.empty()))) {
            assertEquals(Optional.of(facts), h.get("a/B"));
            assertEquals(Optional.of(facts), h.get("a/C"));
            assertFalse(h.get("a/D").isPresent());
        }
    }
}
