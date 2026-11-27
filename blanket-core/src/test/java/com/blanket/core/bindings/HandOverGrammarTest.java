// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M3 KEYS grammar (binding-format.md section 7): an events file's {@code usedBy}, a hook rule's {@code mutable}. */
class HandOverGrammarTest {
    private static final String RECIPE = "{ \"when\": {}, \"mixin\": { \"hook\": \"keys.options\", \"dispatch\": \"keysOptions\","
            + " \"payload\": { \"options\": { \"arg\": 0 } } } }";

    private static EventBinding event(String usedBy, String recipe) {
        return EventBinding.parse(JsonParser.parseString("{ \"schema\": 1, \"event\": \"KEY_HANDOVER\","
                + (usedBy == null ? "" : " \"usedBy\": \"" + usedBy + "\",") + " \"recipes\": [ " + recipe + " ] }"), "test.json");
    }

    private static Hooks hooks(String rule) {
        return Hooks.parse(JsonParser.parseString("{ \"schema\": 1, \"hooks\": { \"keys.options\": [ " + rule + " ] } }"), "hooks.json");
    }

    @Test
    void usedByParsesMatchesTheApiMethodAndRoundTrips() {
        EventBinding eb = event("com.blanket.api.KeyBinding#register", RECIPE);
        assertEquals("com.blanket.api.KeyBinding#register", eb.usedBy());
        assertTrue(eb.usedBy("com/blanket/api/KeyBinding", "register"));
        assertFalse(eb.usedBy("com/blanket/api/KeyBinding", "consumePress"));
        assertFalse(eb.usedBy("com/blanket/api/Input", "register"));
        assertEquals(eb.toJson(), EventBinding.parse(eb.toJson(), "test.json").toJson());
    }

    @Test
    void anEventWithoutUsedByIsUsedByNoMember() {
        EventBinding eb = event(null, RECIPE);
        assertNull(eb.usedBy());
        assertFalse(eb.usedBy("com/blanket/api/KeyBinding", "register"));
        assertFalse(eb.toJson().has("usedBy"));
    }

    @Test
    void usedByMustNameAnApiMethodWithoutDescriptor() {
        for (String bad : List.of("KeyBinding#register", "com.blanket.api.KeyBinding", "com.blanket.api.KeyBinding#register()V",
                "net.minecraft.client.KeyMapping#register")) {
            BindingException e = assertThrows(BindingException.class, () -> event(bad, RECIPE), bad);
            assertTrue(e.getMessage().contains("$.usedBy"), e.getMessage());
        }
    }

    @Test
    void aHandOverCannotGoViaAnEvent() {
        BindingException e = assertThrows(BindingException.class,
                () -> event("com.blanket.api.KeyBinding#register", "{ \"when\": {}, \"via\": \"CLIENT_TICK_START\" }"));
        assertTrue(e.getMessage().contains("$.recipes[0].via"), e.getMessage());
    }

    @Test
    void mutableListsFieldsOfTheHookedClassAndRoundTrips() {
        Hooks h = hooks("{ \"when\": {}, \"method\": \"official:a.Options#load()V\", \"at\": \"HEAD\","
                + " \"mutable\": [ \"official:a.Options#keyMappings:[La/KeyMapping;\" ] }");
        Hooks.Rule r = h.hooks().get("keys.options").get(0);
        assertEquals(List.of("official:a.Options#keyMappings:[La/KeyMapping;"), r.mutable());
        assertEquals(h.toJson(), Hooks.parse(h.toJson(), "hooks.json").toJson());
        assertEquals(List.of(), hooks("{ \"when\": {}, \"method\": \"a.Options#load()V\", \"at\": \"HEAD\" }")
                .hooks().get("keys.options").get(0).mutable());
    }

    @Test
    void mutableRejectsMethodsOtherClassesOtherNamespacesAndEmptyLists() {
        for (String bad : List.of("\"a.Options#load()V\"", "\"a.Other#keyMappings:[La/KeyMapping;\"",
                "\"official:a.Options#keyMappings:[La/KeyMapping;\"")) {
            BindingException e = assertThrows(BindingException.class, () -> hooks("{ \"when\": {}, \"method\": \"a.Options#load()V\","
                    + " \"at\": \"HEAD\", \"mutable\": [ " + bad + " ] }"), bad);
            assertTrue(e.getMessage().contains("mutable[0]"), e.getMessage());
        }
        BindingException e = assertThrows(BindingException.class,
                () -> hooks("{ \"when\": {}, \"method\": \"a.Options#load()V\", \"at\": \"HEAD\", \"mutable\": [] }"));
        assertTrue(e.getMessage().contains("mutable"), e.getMessage());
    }
}
