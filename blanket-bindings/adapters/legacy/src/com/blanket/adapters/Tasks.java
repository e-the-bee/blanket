// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.MinecraftClient;

/** {@code Client.runOnClientThread} on the legacy set. */
public final class Tasks {
    private Tasks() {}

    public static void runOnClientThread(Runnable task) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isOnThread()) {
            task.run();
        } else {
            mc.submit(task);
        }
    }
}
