// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** D-015: WORLD_LEAVE (old world not null) then WORLD_JOIN (new world not null), by identity, once per change. */
class WorldChangeTest {
    private static final int BOTH = WorldChange.LEAVE | WorldChange.JOIN;

    @Test
    void noWorldAtStartIsNoChange() {
        assertEquals(0, new WorldChange().update(null));
    }

    @Test
    void joinStayLeave() {
        WorldChange w = new WorldChange();
        Object world = new Object();
        assertEquals(WorldChange.JOIN, w.update(world));
        assertEquals(0, w.update(world), "the same world on the next tick");
        assertEquals(WorldChange.LEAVE, w.update(null));
        assertEquals(0, w.update(null));
    }

    @Test
    void anotherWorldObjectIsALeaveThenAJoin() {
        WorldChange w = new WorldChange();
        w.update(new Object());
        assertEquals(BOTH, w.update(new Object()), "dimension change or reconnect without a null tick in between");
    }

    @Test
    void identityNotEquality() {
        WorldChange w = new WorldChange();
        w.update(new String("level"));
        assertEquals(BOTH, w.update(new String("level")));
    }
}
