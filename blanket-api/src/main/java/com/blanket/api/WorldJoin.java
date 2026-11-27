// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** The payload of {@link Events#WORLD_JOIN}: an empty marker. */
@Owned
public final class WorldJoin {
    public static final WorldJoin INSTANCE = new WorldJoin();

    private WorldJoin() {}
}
