// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

/**
 * WORLD_LEAVE and WORLD_JOIN synthesis (events-and-hooks.md section 4.2, D-015): compares the client's world with the one
 * seen at the previous tick start, by identity. Holds the world as {@link Object}, so it needs no game type. Client
 * thread only.
 */
final class WorldChange {
    /** The previous world was not null: fire WORLD_LEAVE. */
    static final int LEAVE = 1;
    /** The new world is not null: fire WORLD_JOIN (after WORLD_LEAVE). */
    static final int JOIN = 2;

    private Object last;

    /** Records {@code world} and returns {@link #LEAVE} and/or {@link #JOIN} when it is another object than last time, else 0. */
    int update(Object world) {
        if (world == last) return 0;
        int change = (last != null ? LEAVE : 0) | (world != null ? JOIN : 0);
        last = world;
        return change;
    }
}
