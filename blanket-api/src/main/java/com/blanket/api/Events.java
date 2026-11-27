// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

@Owned
public final class Events {
    private Events() {}

    public static final EventSource<ClientTick> CLIENT_TICK_START = new EventSource<ClientTick>("CLIENT_TICK_START");
    public static final EventSource<ClientTick> CLIENT_TICK_END = new EventSource<ClientTick>("CLIENT_TICK_END");
    public static final EventSource<HudRender> HUD_RENDER = new EventSource<HudRender>("HUD_RENDER");
    public static final EventSource<WorldRender> WORLD_RENDER = new EventSource<WorldRender>("WORLD_RENDER");
    public static final EventSource<ScreenOpen> SCREEN_OPEN = new EventSource<ScreenOpen>("SCREEN_OPEN");
    public static final EventSource<WorldJoin> WORLD_JOIN = new EventSource<WorldJoin>("WORLD_JOIN");
    public static final EventSource<WorldLeave> WORLD_LEAVE = new EventSource<WorldLeave>("WORLD_LEAVE");
}
