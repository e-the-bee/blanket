// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.mapping;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingFileTest {

    private static Path fixture(String name) throws Exception {
        return Path.of(MappingFileTest.class.getResource("/mapping/" + name).toURI());
    }

    @Test
    void proguardKeepsWantedMembersAndTranslatesDescriptors() throws Exception {
        MappingFile f = MappingFile.open(fixture("proguard.txt"), "named", "obf");
        MappingIndex idx = f.read(Set.of(f.key("named", "net/example/Client", "tick"),
                f.key("named", "net/example/Player", "getX")));
        assertEquals("a", idx.mapClass("named", "net/example/Client", "obf"));
        assertEquals("c", idx.member("named", "net/example/Client", "tick", "()V").name(1));
        assertEquals("f", idx.member("named", "net/example/Player", "getX", "()D").name(1));
        assertNull(idx.member("named", "net/example/Client", "unused", "()V"), "unwanted rows are dropped");
        // the field row is not wanted, so it is absent even though its class is kept
        assertNull(idx.member("named", "net/example/Client", "player", "Lnet/example/Player;"));
        assertEquals("(La;I)V", idx.translateDesc("named", "(Lnet/example/Client;I)V", "obf"));
    }

    @Test
    void proguardDescriptorsOfWantedMembersAreTranslated() throws Exception {
        MappingFile f = MappingFile.open(fixture("proguard.txt"), "named", "obf");
        MappingIndex idx = f.read(Set.of(f.key("named", "net/example/Client", "player")));
        MappingIndex.MemberRow row = idx.member("named", "net/example/Client", "player", "Lnet/example/Player;");
        assertNotNull(row);
        assertEquals("b", row.name(1));
        assertEquals("Ld;", row.desc(1));
    }

    @Test
    void tinyV2TranslatesOfficialIntermediaryAndNamed() throws Exception {
        MappingFile f = MappingFile.open(fixture("intermediary.tiny"), "obf", "id", "named");
        MappingIndex idx = f.read(Set.of(f.key("id", "class_1", "method_1"), f.key("obf", "d", "e")));
        assertEquals("class_1", idx.mapClass("obf", "a", "id"));
        assertEquals("NamedClient", idx.mapClass("id", "class_1", "named"));
        MappingIndex.MemberRow tick = idx.member("id", "class_1", "method_1", "()V");
        assertNotNull(tick);
        assertEquals("c", tick.name(0));
        assertEquals("tick", tick.name(2));
        assertEquals("(Lclass_1;I)V", idx.translateDesc("obf", "(La;I)V", "id"));
        assertNull(idx.member("id", "class_1", "method_9", "()V"), "method_9 was not wanted");
        // a wanted field keyed from the official side
        MappingIndex.MemberRow x = idx.member("obf", "d", "e", "D");
        assertNotNull(x);
        assertEquals("field_3", x.name(1));
    }

    @Test
    void tsrg2GivesSrgNamesAndObfDescriptors() throws Exception {
        MappingFile f = MappingFile.open(fixture("joined.tsrg"), "obf", "srg", "id");
        MappingIndex idx = f.read(Set.of(f.key("obf", "a", "c"), f.key("obf", "d", "f")));
        MappingIndex.MemberRow c = idx.member("obf", "a", "c", "()V");
        assertNotNull(c);
        assertEquals("m_1_", c.name(1));
        assertEquals("net/example/Client_fx", idx.mapClass("obf", "a", "srg"));
        assertEquals("m_4_", idx.member("obf", "d", "f", "()D").name(1));
        assertTrue(idx.membersNamed("obf", "a", "z").isEmpty());
    }

    @Test
    void openRejectsWrongNamespaceCount() throws Exception {
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> MappingFile.open(fixture("intermediary.tiny"), "obf", "id"));
    }
}
