// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.runtime;

import com.blanket.api.ClientMod;

/** Called once by the generated loader entry class. */
public final class Boot {
    private Boot() {}

    public static void init(String modId, Gl gl, ClientMod mod) {
        Executor.gl = gl;
        System.out.println("[blanket] " + modId + ": runtime up (" + gl.getClass().getSimpleName() + ")");
        try {
            mod.init();
        } catch (Throwable t) {
            throw new RuntimeException("mod " + modId + " failed in init()", t);
        }
    }
}
