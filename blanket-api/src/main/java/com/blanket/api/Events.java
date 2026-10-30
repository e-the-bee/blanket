// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

public final class Events {
    private Events() {}

    public static final EventSource<ClientTick> CLIENT_TICK_END = new EventSource<ClientTick>();
    public static final EventSource<HudRender> HUD_RENDER = new EventSource<HudRender>();
    public static final EventSource<WorldRender> WORLD_RENDER = new EventSource<WorldRender>();
}
