// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import java.util.List;

/**
 * How one event reaches {@code com.blanket.runtime.Dispatch} on a target: a Mixin injection or a native listener.
 *
 * @param event                  the API event constant ({@code CLIENT_TICK_END})
 * @param dispatch               the {@code Dispatch} method name ({@code tickEnd})
 * @param dispatchDesc           its descriptor ({@code ()V})
 * @param mixin                  true for a Mixin injection, false for a native listener
 * @param mixinTarget            Mixin only: target class internal name
 * @param mixinMethod            Mixin only: the {@code @Inject method} string, {@code name(args)ret}
 * @param at                     Mixin only: the injection point
 * @param handlerDesc            Mixin only: the handler's parameters without the CallbackInfo, {@code (F)V}; the
 *                               generator appends the CallbackInfo parameter
 * @param nativeEvent            native only: the event class internal name (the listener's one parameter)
 * @param bus                    native only: {@code game} (the loader's game event bus)
 * @param filters                native only: identity checks, ANDed, run first
 * @param before                 chains run before the payload; each leaves nothing on the stack
 * @param payloadByDispatchParam one chain per {@code Dispatch} parameter, in order; each leaves that parameter's value
 */
public record Hook(String event, String dispatch, String dispatchDesc, boolean mixin, String mixinTarget,
                   String mixinMethod, At at, String handlerDesc, String nativeEvent, String bus, List<Filter> filters,
                   List<List<Op>> before, List<List<Op>> payloadByDispatchParam) {}
