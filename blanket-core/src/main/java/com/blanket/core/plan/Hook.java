// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import java.util.List;

/**
 * How one event reaches {@code com.blanket.runtime.Dispatch} on a target: a Mixin injection or a native listener.
 *
 * @param event                  the API event constant whose recipe this is ({@code CLIENT_TICK_END}); for an event that
 *                               goes {@code via} another, the other one ({@code CLIENT_TICK_START} for {@code WORLD_JOIN})
 * @param dispatch               the {@code Dispatch} method name ({@code tickEnd})
 * @param dispatchDesc           its descriptor ({@code ()V})
 * @param mixin                  true for a Mixin injection, false for a native listener
 * @param mixinTarget            Mixin only: target class internal name
 * @param mixinMethod            Mixin only: the {@code @Inject method} string, {@code name(args)ret}
 * @param at                     Mixin only: the injection point
 * @param handlerDesc            Mixin only: the handler's parameters without the CallbackInfo, {@code (F)V}; the
 *                               generator appends the CallbackInfo parameter
 * @param nativeEvent            native only: the event class internal name (the listener's one parameter)
 * @param bus                    native only: {@code game} (the loader's game event bus), {@code mod} (the mod bus) or
 *                               {@code fml} (a lifecycle event the entry class receives itself)
 * @param filters                native only: identity checks, ANDed, run first
 * @param before                 chains run before the payload; each leaves nothing on the stack
 * @param payloadByDispatchParam one chain per {@code Dispatch} parameter, in order; each leaves that parameter's value
 * @param hookId                 Mixin only: the hooks.json id ({@code tick.end}), which names the Mixin class
 *                               {@code <Event>$<HookId>} (events-and-hooks.md section 3); null for a native listener
 * @param mutable                Mixin only: fields of the target class the hook's code assigns although they are final
 *                               (hooks.json {@code "mutable"}); the Mixin class shadows each with {@code @Shadow @Final
 *                               @Mutable}, so Mixin removes {@code final} from the target field. Empty for most hooks
 */
public record Hook(String event, String dispatch, String dispatchDesc, boolean mixin, String mixinTarget,
                   String mixinMethod, At at, String handlerDesc, String nativeEvent, String bus, List<Filter> filters,
                   List<List<Op>> before, List<List<Op>> payloadByDispatchParam, String hookId, List<Field> mutable) {

    /** A field of the Mixin target in runtime names: owner internal name, name, descriptor. */
    public record Field(String owner, String name, String desc) {}

    /** A hook with no mutable fields. */
    public Hook(String event, String dispatch, String dispatchDesc, boolean mixin, String mixinTarget, String mixinMethod, At at,
                String handlerDesc, String nativeEvent, String bus, List<Filter> filters, List<List<Op>> before,
                List<List<Op>> payloadByDispatchParam, String hookId) {
        this(event, dispatch, dispatchDesc, mixin, mixinTarget, mixinMethod, at, handlerDesc, nativeEvent, bus, filters, before,
                payloadByDispatchParam, hookId, List.of());
    }

    /** A hook without a hooks.json id: a native listener, or a Mixin fixture that does not care about its class name. */
    public Hook(String event, String dispatch, String dispatchDesc, boolean mixin, String mixinTarget, String mixinMethod, At at,
                String handlerDesc, String nativeEvent, String bus, List<Filter> filters, List<List<Op>> before,
                List<List<Op>> payloadByDispatchParam) {
        this(event, dispatch, dispatchDesc, mixin, mixinTarget, mixinMethod, at, handlerDesc, nativeEvent, bus, filters, before,
                payloadByDispatchParam, null, List.of());
    }
}
