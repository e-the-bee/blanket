// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** The payload of {@link Events#WORLD_LEAVE}: an empty marker. */
@Owned
public final class WorldLeave {
    public static final WorldLeave INSTANCE = new WorldLeave();

    private WorldLeave() {}
}
