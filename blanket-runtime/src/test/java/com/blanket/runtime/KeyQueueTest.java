// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The key queue keeps registration order, hands over once, and refuses registration after init. */
class KeyQueueTest {
    @BeforeEach
    @AfterEach
    void clean() {
        KeyQueue.reset();
    }

    @Test
    void drainReturnsMappingsInOrderAndEmptiesTheQueue() {
        KeyQueue.add("a");
        KeyQueue.add("b");
        assertEquals(2, KeyQueue.size());
        assertEquals(Arrays.asList("a", "b"), KeyQueue.drain());
        assertEquals(0, KeyQueue.size());
        assertTrue(KeyQueue.drain().isEmpty());
    }

    @Test
    void addAfterSealThrowsAndKeepsEarlierMappings() {
        KeyQueue.add("a");
        KeyQueue.seal();
        assertThrows(IllegalStateException.class, () -> KeyQueue.add("late"));
        assertEquals(Arrays.asList("a"), KeyQueue.drain());
    }

    @Test
    void sealedQueueStillDrainsAfterSeal() {
        KeyQueue.seal();
        assertTrue(KeyQueue.drain().isEmpty());
    }

    @Test
    void beginSetsModIdAndReopensTheWindow() {
        KeyQueue.seal();
        KeyQueue.begin("demo");
        assertEquals("demo", KeyQueue.modId());
        KeyQueue.add("a");
        assertEquals(1, KeyQueue.size());
    }
}
