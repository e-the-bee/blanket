// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** Data model loaders for every binding file kind (binding-format.md sections 3, 4, 7). */
class BindingFilesTest {

    private static Path fixture(String rel) {
        try {
            return Paths.get(BindingFilesTest.class.getResource("/bindings/" + rel).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Round trip: read, write back, compare with the file's JSON ---------------------------

    @Test
    void targetsRoundTrip() {
        Path p = fixture("targets.json");
        JsonElement original = BindingFiles.readJson(p);
        assertEquals(original, BindingFiles.readTargets(p).toJson());
    }

    @Test
    void typeFileRoundTrip() {
        Path p = fixture("types/Player.json");
        assertEquals(BindingFiles.readJson(p), BindingFiles.readType(p).toJson());
    }

    @Test
    void glueFileRoundTrip() {
        Path p = fixture("glue/GlueFrame.json");
        assertEquals(BindingFiles.readJson(p), BindingFiles.readType(p).toJson());
    }

    @Test
    void hooksRoundTrip() {
        Path p = fixture("hooks.json");
        assertEquals(BindingFiles.readJson(p), BindingFiles.readHooks(p).toJson());
    }

    @Test
    void eventRoundTrip() {
        Path p = fixture("events/HudRender.json");
        assertEquals(BindingFiles.readJson(p), BindingFiles.readEvent(p).toJson());
    }

    @Test
    void typeFileContentIsReadAsModel() {
        TypeBinding t = BindingFiles.readType(fixture("types/Player.json"));
        assertEquals("com.blanket.api.Player", t.api());
        assertEquals(2, t.representation().size());
        assertEquals("lf:net/minecraft/class_864", t.representation().get(0).alias());
        assertEquals(4, t.members().size());
        TypeBinding.MemberRule offHand = t.members().get("offHandItem").get(0);
        assertEquals("OFFHAND", offHand.requires());
        assertEquals(Lowering.Kind.INVOKE, offHand.lower().kind());
        assertEquals(Lowering.Kind.CHAIN, t.members().get("foodLevel").get(0).lower().kind());
        assertEquals(2, t.members().get("foodLevel").get(0).lower().steps().size());
    }

    @Test
    void hookAtForms() {
        Hooks h = BindingFiles.readHooks(fixture("hooks.json"));
        assertEquals(Hooks.At.Kind.TAIL, h.hooks().get("gui.render").get(0).at().kind());
        Hooks.At str = h.hooks().get("gui.before-hand").get(0).at();
        assertEquals(Hooks.At.Kind.INVOKE_STRING, str.kind());
        assertEquals("hand", str.ldc());
        Hooks.At inv = h.hooks().get("world.tail").get(0).at();
        assertEquals("AFTER", inv.shift());
        assertEquals(Integer.valueOf(2), inv.ordinal());
        assertEquals(Hooks.At.Kind.RETURN, h.hooks().get("world.tail").get(1).at().kind());
    }

    @Test
    void eventRecipesAndPayloads() {
        EventBinding e = BindingFiles.readEvent(fixture("events/HudRender.json"));
        assertEquals("HUD_RENDER", e.event());
        EventBinding.Recipe mixin = e.recipes().get(0);
        assertEquals("gui.render", mixin.hook());
        assertEquals("arg", mixin.payload().get("partialTick").kind());
        EventBinding.Recipe nat = e.recipes().get(1);
        assertEquals("game", nat.bus());
        assertEquals("getter", nat.payload().get("partialTick").kind());
    }

    // ---- Strictness: unknown keys and wrong schema fail with the path --------------------------

    private static String message(Runnable r) {
        return assertThrows(BindingException.class, r::run).getMessage();
    }

    @Test
    void unknownRootKeyFailsWithPath() {
        String m = message(() -> TypeBinding.parse(
                JsonParser.parseString("{\"schema\":1,\"api\":\"x.Y\",\"membrs\":{}}"), "types/Y.json"));
        assertTrue(m.startsWith("types/Y.json: $.membrs: unknown key 'membrs'"), m);
    }

    @Test
    void wrongSchemaVersionFailsWithPath() {
        String m = message(() -> Targets.parse(
                JsonParser.parseString("{\"schema\":2,\"anchor\":\"1.21.1\",\"loaders\":{},\"versions\":{}}"), "targets.json"));
        assertEquals("targets.json: $.schema: expected schema 1, found 2", m);
    }

    @Test
    void unknownKeyInsideRuleFailsWithPath() {
        String json = "{\"schema\":1,\"api\":\"x.Y\",\"members\":{\"m\":[{\"when\":{},\"lowr\":{}}]}}";
        String m = message(() -> TypeBinding.parse(JsonParser.parseString(json), "types/Y.json"));
        assertTrue(m.startsWith("types/Y.json: $.members.m[0].lowr: unknown key 'lowr'"), m);
    }

    @Test
    void badSelectorInsideRuleNamesThePath() {
        String json = "{\"schema\":1,\"api\":\"x.Y\",\"members\":{\"m\":[{\"when\":{\"loader\":\"quilt\"},\"lower\":{\"const\":1}}]}}";
        String m = message(() -> TypeBinding.parse(JsonParser.parseString(json), "types/Y.json"));
        assertTrue(m.startsWith("types/Y.json: $.members.m[0].when.loader:"), m);
        assertTrue(m.contains("unknown loader 'quilt'"), m);
    }

    @Test
    void unknownLoweringKindFails() {
        String json = "{\"schema\":1,\"api\":\"x.Y\",\"members\":{\"m\":[{\"when\":{},\"lower\":{\"call\":\"a.B#c()V\"}}]}}";
        String m = message(() -> TypeBinding.parse(JsonParser.parseString(json), "types/Y.json"));
        assertTrue(m.startsWith("types/Y.json: $.members.m[0].lower:"), m);
        assertTrue(m.contains("unknown lowering kind 'call'"), m);
    }

    @Test
    void loweringWithTwoKindsFails() {
        String json = "{\"schema\":1,\"api\":\"x.Y\",\"members\":{\"m\":[{\"when\":{},\"lower\":{\"get\":\"a#b:I\",\"put\":\"a#b:I\"}}]}}";
        String m = message(() -> TypeBinding.parse(JsonParser.parseString(json), "types/Y.json"));
        assertTrue(m.contains("exactly one kind key"), m);
    }

    @Test
    void representationNeedsExactlyOneOfAliasAndOwned() {
        String json = "{\"schema\":1,\"api\":\"x.Y\",\"representation\":[{\"when\":{},\"alias\":\"a.B\",\"owned\":\"c.D\"}]}";
        String m = message(() -> TypeBinding.parse(JsonParser.parseString(json), "types/Y.json"));
        assertTrue(m.startsWith("types/Y.json: $.representation[0]:"), m);
    }

    @Test
    void unknownRuntimeNamespaceFails() {
        String json = "{\"schema\":1,\"anchor\":\"1.21.1\",\"loaders\":{\"fabric\":[{\"when\":{},\"runtime\":\"yarn\",\"emitter\":\"fabric\"}]},\"versions\":{}}";
        String m = message(() -> Targets.parse(JsonParser.parseString(json), "targets.json"));
        assertTrue(m.startsWith("targets.json: $.loaders.fabric[0].runtime:"), m);
    }

    @Test
    void unknownMappingSourceFails() {
        String json = "{\"schema\":1,\"anchor\":\"1.21.1\",\"loaders\":{},\"versions\":{\"1.21.1\":"
                + "{\"java\":21,\"mappings\":{\"yarn\":\"x\"},\"adapters\":\"modern\"}}}";
        String m = message(() -> Targets.parse(JsonParser.parseString(json), "targets.json"));
        assertTrue(m.startsWith("targets.json: $.versions.1.21.1.mappings.yarn:"), m);
    }

    @Test
    void hookRuleNeedsAt() {
        String json = "{\"schema\":1,\"hooks\":{\"h\":[{\"when\":{},\"method\":\"a.B#c()V\"}]}}";
        String m = message(() -> Hooks.parse(JsonParser.parseString(json), "hooks.json"));
        assertTrue(m.startsWith("hooks.json: $.hooks.h[0]: missing \"at\""), m);
    }

    @Test
    void invokeStringAtNeedsLdc() {
        String json = "{\"schema\":1,\"hooks\":{\"h\":[{\"when\":{},\"method\":\"a.B#c()V\","
                + "\"at\":{\"invokeString\":\"a.B#d(Ljava/lang/String;)V\"}}]}}";
        String m = message(() -> Hooks.parse(JsonParser.parseString(json), "hooks.json"));
        assertTrue(m.startsWith("hooks.json: $.hooks.h[0].at.ldc:"), m);
    }

    @Test
    void recipeNeedsExactlyOneOfMixinAndNative() {
        String json = "{\"schema\":1,\"event\":\"E\",\"recipes\":[{\"when\":{},\"mixin\":{\"hook\":\"h\"},\"native\":{}}]}";
        String m = message(() -> EventBinding.parse(JsonParser.parseString(json), "events/E.json"));
        assertTrue(m.contains("exactly one of \"mixin\" and \"native\""), m);
    }

    @Test
    void payloadSourceMustHaveOneKey() {
        String json = "{\"schema\":1,\"event\":\"E\",\"recipes\":[{\"when\":{},\"mixin\":{\"hook\":\"h\","
                + "\"payload\":{\"p\":{\"arg\":1,\"const\":2}}}}]}";
        String m = message(() -> EventBinding.parse(JsonParser.parseString(json), "events/E.json"));
        assertTrue(m.startsWith("events/E.json: $.recipes[0].mixin.payload.p:"), m);
    }

    @Test
    void lowerRuleMissingWhenFails() {
        String json = "{\"schema\":1,\"api\":\"x.Y\",\"members\":{\"m\":[{\"lower\":{\"const\":1}}]}}";
        String m = message(() -> TypeBinding.parse(JsonParser.parseString(json), "types/Y.json"));
        assertTrue(m.startsWith("types/Y.json: $.members.m[0]: missing \"when\""), m);
    }
}
