// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the key mappings a mod registered in {@code ClientMod.init()} until the loader recipe hands them to the game
 * (events-and-hooks 4.7, built in M3). The mappings are the game's own key mapping objects, so they are held as
 * {@link Object}. {@link Boot#init} seals the queue when {@code init()} returns; a later {@link #add} throws
 * {@link IllegalStateException}, as {@code KeyBinding.register} promises on every target.
 */
public final class KeyQueue {
    private KeyQueue() {}

    private static final List<Object> PENDING = new ArrayList<>();
    private static boolean sealed;
    private static String modId = "";

    /** Sets the mod id used in translation keys; {@link Boot#init} calls it before {@code init()}. */
    public static synchronized void begin(String id) {
        modId = id;
        sealed = false;
    }

    /** The mod id of the registration window. */
    public static synchronized String modId() {
        return modId;
    }

    /** Queues a mapping. Called by the {@code Keys} adapter; throws once {@code init()} has returned. */
    public static synchronized void add(Object mapping) {
        if (sealed) {
            throw new IllegalStateException("KeyBinding.register is only allowed inside ClientMod.init()");
        }
        PENDING.add(mapping);
    }

    /** Ends the registration window. */
    public static synchronized void seal() {
        sealed = true;
    }

    /** The queued mappings in registration order, removed from the queue: the loader recipe's hand-over. */
    public static synchronized List<Object> drain() {
        List<Object> out = new ArrayList<>(PENDING);
        PENDING.clear();
        return out;
    }

    /** Number of mappings still queued. */
    public static synchronized int size() {
        return PENDING.size();
    }

    /** Test support: empties the queue and reopens the window. */
    static synchronized void reset() {
        PENDING.clear();
        sealed = false;
    }
}
