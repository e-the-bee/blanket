// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** The M1 T9 grammar additions: cast (D-053), glue payload source (D-054), const/newarray steps and before (D-056). */
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
        assertTrue(e.getMessage().contains("takes 1 arguments but 0"), e.getMessage());
    }

    // ---- D-056: const and newarray chain steps, before -------------------------------------

    @Test
    void laterConstantFeedsAOneArgumentInvoke() {
        String json = "{\"chain\": [{\"cast\": \"a.D\"}, {\"const\": true}, {\"invoke\": \"a.D#p(Z)F\"}]}";
        Lowering l = lower(json);
        assertEquals(Lowering.Kind.CONST, l.steps().get(1).kind());
        assertEquals(JsonParser.parseString(json), l.toJson());
    }

    @Test
    void argumentCountMismatchFails() {
        BindingException e = assertThrows(BindingException.class, () -> lower(
                "{\"chain\": [{\"cast\": \"a.D\"}, {\"const\": 1}, {\"const\": 2}, {\"invoke\": \"a.D#p(I)F\"}]}"));
        assertTrue(e.getMessage().contains("takes 1 arguments but 2"), e.getMessage());
    }

    @Test
    void argumentTypeMismatchFails() {
        assertThrows(BindingException.class,
                () -> lower("{\"chain\": [{\"cast\": \"a.D\"}, {\"const\": 1}, {\"invoke\": \"a.D#p(Z)F\"}]}"));
    }

    @Test
    void laterConstantNoInvokeConsumesFails() {
        assertThrows(BindingException.class, () -> lower("{\"chain\": [{\"invoke\": \"a.B#f()La/C;\"}, {\"const\": 1}]}"));
        assertThrows(BindingException.class, () -> lower(
                "{\"chain\": [{\"invoke\": \"a.B#f()La/C;\"}, {\"const\": 1}, {\"get\": \"a.C#x:I\"}]}"));
    }

    @Test
    void newarrayFeedsGetFloatArray() {
        String json = "{\"chain\": [{\"cast\": \"org.joml.Matrix4f\"}, {\"newarray\": \"F\", \"length\": 16},"
                + " {\"invoke\": \"org.joml.Matrix4f#get([F)[F\"}]}";
        Lowering l = lower(json);
        assertEquals(Lowering.Kind.NEWARRAY, l.steps().get(1).kind());
        assertEquals("F", l.steps().get(1).ref());
        assertEquals(16, l.steps().get(1).length());
        assertEquals(JsonParser.parseString(json), l.toJson());
    }

    @Test
    void newarrayOfWrongTypeOrOutsideAChainFails() {
        assertThrows(BindingException.class, () -> lower("{\"chain\": [{\"cast\": \"a.M\"}, {\"newarray\": \"I\", \"length\": 16},"
                + " {\"invoke\": \"a.M#get([F)[F\"}]}"));
        assertThrows(BindingException.class, () -> lower("{\"newarray\": \"F\", \"length\": 16}"));
        assertThrows(BindingException.class, () -> lower("{\"chain\": [{\"cast\": \"a.M\"}, {\"newarray\": \"F\"},"
                + " {\"invoke\": \"a.M#get([F)[F\"}]}"));
    }

    private static String beforeEvent(String member) {
        return "{\"schema\": 1, \"event\": \"E\", \"recipes\": [{\"when\": {}, \"mixin\": {\"hook\": \"h\", \"dispatch\": \"d\","
                + " \"before\": [{\"glue\": \"" + member + "\", \"args\": [{\"arg\": 1}]}],"
                + " \"payload\": {\"p\": {\"const\": null}}}}]}";
    }

    @Test
    void beforeWithAVoidGlueMemberParsesAndRoundTrips() {
        String json = beforeEvent("GlueGui#flush(Ljava/lang/Object;)V");
        EventBinding e = event(json);
        assertEquals("GlueGui#flush(Ljava/lang/Object;)V", e.recipes().get(0).injections().get(0).before().get(0).value().getAsString());
        assertEquals(JsonParser.parseString(json), e.toJson());
    }

    @Test
    void beforeWithANonVoidMemberFails() {
        BindingException e = assertThrows(BindingException.class, () -> event(beforeEvent("GlueFrame#fbo()I")));
        assertTrue(e.getMessage().contains("void"), e.getMessage());
        assertThrows(BindingException.class, () -> event(beforeEvent("GlueGui#flush")));
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
            + "\"event\": \"loader:x.Y\", \"bus\": \"game\", \"dispatch\": \"d\", \"payload\": {"
            + "\"w\": {\"glue\": \"GlueWorld#camX\", \"args\": [{\"arg\": 3}]},"
            + "\"p\": {\"glue\": \"GlueFrame#partialTick\", \"args\": [{\"glue\": \"GlueWorld#camX\"}, {\"const\": true}]},"
            + "\"f\": {\"glue\": \"GlueFrame#fbo\"}}}}]}";

    @Test
    void glueSourceWithArgumentsParsesAndRoundTrips() {
        EventBinding e = event(GLUE_EVENT);
        EventBinding.Source w = e.recipes().get(0).injections().get(0).payload().get("w");
        assertEquals("glue", w.kind());
        assertEquals("GlueWorld#camX", w.value().getAsString());
        assertEquals(1, w.args().size());
        assertEquals("arg", w.args().get(0).kind());
        assertEquals(JsonParser.parseString(GLUE_EVENT), e.toJson());
    }

    @Test
    void glueArgumentsMayBeNestedGlueAndConstants() {
        EventBinding.Source p = event(GLUE_EVENT).recipes().get(0).injections().get(0).payload().get("p");
        assertEquals("glue", p.args().get(0).kind());
        assertEquals("const", p.args().get(1).kind());
    }

    @Test
    void glueSourceWithoutArgumentsHasNoArguments() {
        EventBinding.Source f = event(GLUE_EVENT).recipes().get(0).injections().get(0).payload().get("f");
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

    // ---- D-057: dispatch, mixin arrays, filter, field getters ------------------------------

    private static final String NATIVE = "{\"schema\": 1, \"event\": \"E\", \"recipes\": [{\"when\": {}, \"native\": {"
            + "\"event\": \"loader:x.Y\", \"bus\": \"game\", FILTER\"dispatch\": \"d\","
            + " \"payload\": {\"p\": {\"getter\": \"GETTER\"}}}}]}";

    private static String nativeEvent(String filter, String getter) {
        return NATIVE.replace("FILTER", filter == null ? "" : "\"filter\": " + filter + ", ").replace("GETTER", getter);
    }

    @Test
    void dispatchIsRequiredOnMixinAndNative() {
        BindingException m = assertThrows(BindingException.class,
                () -> event(beforeEvent("GlueGui#flush(Ljava/lang/Object;)V").replace("\"dispatch\": \"d\", ", "")));
        assertTrue(m.getMessage().contains("$.recipes[0].mixin: missing \"dispatch\""), m.getMessage());
        BindingException n = assertThrows(BindingException.class,
                () -> event(GLUE_EVENT.replace("\"dispatch\": \"d\", ", "")));
        assertTrue(n.getMessage().contains("$.recipes[0].native: missing \"dispatch\""), n.getMessage());
        assertThrows(BindingException.class, () -> event(GLUE_EVENT.replace("\"dispatch\": \"d\"", "\"dispatch\": \"a.b\"")));
    }

    @Test
    void mixinArrayParsesInOrderAndRoundTrips() {
        String json = "{\"schema\": 1, \"event\": \"E\", \"recipes\": [{\"when\": {}, \"mixin\": ["
                + "{\"hook\": \"gui.record\", \"dispatch\": \"hudRecord\", \"payload\": {\"partialTick\": {\"arg\": 2}}},"
                + "{\"hook\": \"gui.replay\", \"dispatch\": \"hudReplay\"}]}]}";
        EventBinding e = event(json);
        EventBinding.Recipe r = e.recipes().get(0);
        assertTrue(r.isMixin());
        assertEquals(2, r.injections().size());
        assertEquals("gui.replay", r.injections().get(1).hook());
        assertEquals("hudReplay", r.injections().get(1).dispatch());
        assertEquals(JsonParser.parseString(json), e.toJson());
    }

    @Test
    void emptyMixinArrayFails() {
        assertThrows(BindingException.class, () -> event(
                "{\"schema\": 1, \"event\": \"E\", \"recipes\": [{\"when\": {}, \"mixin\": []}]}"));
    }

    @Test
    void filterMapsAGetterOrFieldToAStaticFieldAndRoundTrips() {
        String json = nativeEvent("{\"loader:x.Y#phase:Lx/P;\": \"loader:x.P#END:Lx/P;\","
                + " \"loader:x.Y#getStage()Lx/S;\": \"loader:x.S#AFTER:Lx/S;\"}", "loader:x.Y#getP()F");
        EventBinding e = event(json);
        java.util.Map<String, String> f = e.recipes().get(0).injections().get(0).filter();
        assertEquals(java.util.List.of("loader:x.Y#phase:Lx/P;", "loader:x.Y#getStage()Lx/S;"), java.util.List.copyOf(f.keySet()));
        assertEquals("loader:x.P#END:Lx/P;", f.get("loader:x.Y#phase:Lx/P;"));
        assertEquals(JsonParser.parseString(json), e.toJson());
    }

    @Test
    void filterRejectsOldNameFormsAndNonFieldValues() {
        assertThrows(BindingException.class, () -> event(nativeEvent("{\"phase\": \"END\"}", "loader:x.Y#getP()F")));
        assertThrows(BindingException.class, () -> event(nativeEvent("{\"loader:x.Y#phase:Lx/P;\": \"END\"}", "loader:x.Y#getP()F")));
        assertThrows(BindingException.class,
                () -> event(nativeEvent("{\"loader:x.Y#phase:Lx/P;\": \"loader:x.P#end()Lx/P;\"}", "loader:x.Y#getP()F")));
        assertThrows(BindingException.class,
                () -> event(nativeEvent("{\"loader:x.Y#is(I)Z\": \"loader:x.P#END:Lx/P;\"}", "loader:x.Y#getP()F")));
        assertThrows(BindingException.class, () -> event(nativeEvent("{\"loader:x.Y#phase:Lx/P;\": 1}", "loader:x.Y#getP()F")));
        assertThrows(BindingException.class, () -> event(nativeEvent("{}", "loader:x.Y#getP()F")));
    }

    @Test
    void getterAcceptsAFieldRefAndRejectsMethodsWithParameters() {
        EventBinding e = event(nativeEvent(null, "loader:x.Y#partialTicks:F"));
        assertEquals("loader:x.Y#partialTicks:F", e.recipes().get(0).injections().get(0).payload().get("p").value().getAsString());
        assertThrows(BindingException.class, () -> event(nativeEvent(null, "loader:x.Y#get(I)F")));
        assertThrows(BindingException.class, () -> event(nativeEvent(null, "loader:x.Y#partialTicks")));
    }
}
