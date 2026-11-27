// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.Minecraft;

/** {@code Client.runOnClientThread} on the unobf set. */
public final class Tasks {
    private Tasks() {}

    public static void runOnClientThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }
}
