// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The key hand-over with fakes: a {@code String[]} stands for the game's {@code KeyMapping[]} and strings for the
 * mappings. The array keeps its component type (the game stores the result in a typed field), keeps the vanilla keys
 * first, appends in registration order, and a second hand-over adds nothing.
 */
class KeyHandoverTest {
    private static final String[] VANILLA = {"key.forward", "key.jump"};

    @BeforeEach
    @AfterEach
    void clean() {
        KeyQueue.reset();
    }

    @Test
    void appendsQueuedMappingsAfterTheGameKeysInOrder() {
        Object[] out = KeyHandover.append(VANILLA, Arrays.<Object>asList("key.mod.a", "key.mod.b"));
        assertArrayEquals(new String[] {"key.forward", "key.jump", "key.mod.a", "key.mod.b"}, out);
        assertSame(String[].class, out.getClass());
        assertArrayEquals(new String[] {"key.forward", "key.jump"}, VANILLA);
    }

    @Test
    void nothingQueuedKeepsTheSameArray() {
        assertSame(VANILLA, KeyHandover.append(VANILLA, Collections.emptyList()));
    }

    @Test
    void mappingsTheArrayAlreadyHoldsAreNotAddedTwice() {
        String mine = new String("key.mod.a");
        String[] keys = {"key.forward", mine};
        assertSame(keys, KeyHandover.append(keys, Collections.<Object>singletonList(mine)));
        Object[] out = KeyHandover.append(keys, Arrays.<Object>asList(mine, "key.mod.b", "key.mod.b"));
        assertArrayEquals(new String[] {"key.forward", "key.mod.a", "key.mod.b"}, out);
    }

    @Test
    void equalButDistinctMappingsAreBothKept() {
        String a = new String("key.mod.x"), b = new String("key.mod.x");
        assertEquals(4, KeyHandover.append(VANILLA, Arrays.<Object>asList(a, b)).length);
    }

    @Test
    void theQueueHandsOverOnceSoARepeatedLoadAddsNothing() {
        KeyQueue.begin("mod");
        KeyQueue.add("key.mod.a");
        KeyQueue.seal();
        Object[] first = KeyHandover.append(VANILLA, KeyQueue.drain());
        Object[] second = KeyHandover.append(first, KeyQueue.drain());
        assertSame(first, second);
        assertEquals(Collections.<Object>singletonList("key.mod.a"), KeyQueue.registered());
    }
}
