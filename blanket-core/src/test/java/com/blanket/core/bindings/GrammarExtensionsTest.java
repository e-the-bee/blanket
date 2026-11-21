// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** The M1 T9 grammar additions: the {@code cast} chain step (D-053) and the {@code glue} payload source (D-054). */
class GrammarExtensionsTest {

    private static Lowering lower(String json) {
        return Lowering.parse(JsonParser.parseString(json), "test.json", "$");
    }

    private static EventBinding event(String json) {
        return EventBinding.parse(JsonParser.parseString(json), "test.json");
    }

    // ---- D-053: cast chain step -----------------------------------------------------------

    @Test
    void castIsAChainStepAndRoundTrips() {
        String json = "{\"chain\": [{\"get\": \"a.B#f:I\"}, {\"cast\": \"a.C\"}, {\"invoke\": \"a.C#g()I\"}]}";
        Lowering l = lower(json);
        assertEquals(Lowering.Kind.CHAIN, l.kind());
        assertEquals(Lowering.Kind.CAST, l.steps().get(1).kind());
        assertEquals("a.C", l.steps().get(1).ref());
        assertEquals(JsonParser.parseString(json), l.toJson());
    }

    @Test
    void castMayBeTheFirstStep() {
        Lowering l = lower("{\"chain\": [{\"cast\": \"a.C\"}, {\"invoke\": \"a.C#g()I\"}]}");
        assertEquals(Lowering.Kind.CAST, l.steps().get(0).kind());
    }

    @Test
    void castAsTopLevelLoweringFails() {
        BindingException e = assertThrows(BindingException.class, () -> lower("{\"cast\": \"a.C\"}"));
        assertTrue(e.getMessage().contains("chain step"), e.getMessage());
    }

    @Test
    void castWithAnotherKeyFailsWithPath() {
        BindingException e = assertThrows(BindingException.class,
                () -> lower("{\"chain\": [{\"get\": \"a.B#f:I\"}, {\"cast\": \"a.C\", \"invoke\": \"a.C#g()I\"}]}"));
        assertTrue(e.getMessage().contains("$.chain[1]"), e.getMessage());
    }

    @Test
    void laterStepWithParametersFails() {
        BindingException e = assertThrows(BindingException.class,
                () -> lower("{\"chain\": [{\"invoke\": \"a.B#f()La/C;\"}, {\"invoke\": \"a.C#g(I)V\"}]}"));
        assertTrue(e.getMessage().contains("no arguments"), e.getMessage());
    }

    @Test
    void laterConstantStepFails() {
        assertThrows(BindingException.class, () -> lower("{\"chain\": [{\"invoke\": \"a.B#f()La/C;\"}, {\"const\": 1}]}"));
    }

    @Test
    void laterAdapterStepFails() {
        assertThrows(BindingException.class,
                () -> lower("{\"chain\": [{\"invoke\": \"a.B#f()La/C;\"}, {\"adapter\": \"set:a.X#m\"}]}"));
    }

    @Test
    void firstStepConstantFails() {
        assertThrows(BindingException.class, () -> lower("{\"chain\": [{\"const\": 1}, {\"invoke\": \"a.C#g()I\"}]}"));
    }

    // ---- D-054: glue payload source -------------------------------------------------------

    private static final String GLUE_EVENT = "{\"schema\": 1, \"event\": \"E\", \"recipes\": [{\"when\": {}, \"native\": {"
            + "\"event\": \"loader:x.Y\", \"bus\": \"game\", \"payload\": {"
            + "\"w\": {\"glue\": \"GlueWorld#camX\", \"args\": [{\"arg\": 3}]},"
            + "\"p\": {\"glue\": \"GlueFrame#partialTick\", \"args\": [{\"glue\": \"GlueWorld#camX\"}, {\"const\": true}]},"
            + "\"f\": {\"glue\": \"GlueFrame#fbo\"}}}}]}";

    @Test
    void glueSourceWithArgumentsParsesAndRoundTrips() {
        EventBinding e = event(GLUE_EVENT);
        EventBinding.Source w = e.recipes().get(0).payload().get("w");
        assertEquals("glue", w.kind());
        assertEquals("GlueWorld#camX", w.value().getAsString());
        assertEquals(1, w.args().size());
        assertEquals("arg", w.args().get(0).kind());
        assertEquals(JsonParser.parseString(GLUE_EVENT), e.toJson());
    }

    @Test
    void glueArgumentsMayBeNestedGlueAndConstants() {
        EventBinding.Source p = event(GLUE_EVENT).recipes().get(0).payload().get("p");
        assertEquals("glue", p.args().get(0).kind());
        assertEquals("const", p.args().get(1).kind());
    }

    @Test
    void glueSourceWithoutArgumentsHasNoArguments() {
        EventBinding.Source f = event(GLUE_EVENT).recipes().get(0).payload().get("f");
        assertTrue(f.args().isEmpty());
    }

    @Test
    void glueMemberMustBeTypeAndMember() {
        BindingException e = assertThrows(BindingException.class,
                () -> event(GLUE_EVENT.replace("GlueFrame#fbo", "GlueFrame")));
        assertTrue(e.getMessage().contains("GlueType#member"), e.getMessage());
    }

    @Test
    void glueSourceRejectsOtherKeys() {
        assertThrows(BindingException.class,
                () -> event(GLUE_EVENT.replace("\"GlueFrame#fbo\"}", "\"GlueFrame#fbo\", \"const\": 1}")));
    }

    @Test
    void glueArgumentsMustBePayloadSources() {
        assertThrows(BindingException.class,
                () -> event(GLUE_EVENT.replace("{\"arg\": 3}", "{\"bogus\": 3}")));
    }

    @Test
    void argumentsOnlyAllowedOnGlue() {
        assertThrows(BindingException.class,
                () -> event(GLUE_EVENT.replace("{\"arg\": 3}", "{\"arg\": 3, \"args\": []}")));
    }
}
