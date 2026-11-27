// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.api.Key;
import com.blanket.runtime.KeyQueue;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** {@code KeyBinding.register} and {@code Input.isKeyDown} on the modern set (GLFW key codes, String category). */
public final class Keys {
    private Keys() {}

    /** Builds the mapping with the translation keys of the mod and queues it for the loader recipe. */
    public static KeyMapping register(String id, Key defaultKey, String category) {
        String mod = KeyQueue.modId();
        KeyMapping mapping = new KeyMapping("key." + mod + "." + id, KeyCodes.code(defaultKey), "key.categories." + mod + "." + category);
        KeyQueue.add(mapping);
        return mapping;
    }

    public static boolean isKeyDown(Key key) {
        int code = KeyCodes.code(key);
        return code != GLFW.GLFW_KEY_UNKNOWN && InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), code);
    }
}
