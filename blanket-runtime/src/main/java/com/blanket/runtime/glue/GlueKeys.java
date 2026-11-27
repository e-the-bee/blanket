// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime.glue;

/**
 * The game's side of the key hand-over (events-and-hooks 4.7). Glue stubs, bound per target in glue/GlueKeys.json (see
 * {@link GlueFrame}).
 */
public final class GlueKeys {
    private GlueKeys() {}

    /** The client's options object, once the client has one (Forge 1.8.9 at {@code FMLInitializationEvent}). */
    public static Object options() { throw GlueFrame.stub("options"); }

    /** The options' array of every key mapping (the controls screen and {@code options.txt} use it), typed as the game's array. */
    public static Object mappings(Object options) { throw GlueFrame.stub("mappings"); }

    /** Replaces the options' key mapping array; {@code mappings} has the array's own runtime type. */
    public static void setMappings(Object options, Object mappings) { throw GlueFrame.stub("setMappings"); }

    /** {@code RegisterKeyMappingsEvent#register(KeyMapping)} on the loaders that have the event. */
    public static void register(Object event, Object mapping) { throw GlueFrame.stub("register"); }
}
