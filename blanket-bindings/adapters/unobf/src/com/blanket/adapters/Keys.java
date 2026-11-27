// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.api.Key;
import com.blanket.runtime.KeyQueue;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** {@code KeyBinding.register} and {@code Input.isKeyDown} on the unobf set (GLFW codes, {@code KeyMapping.Category}). */
public final class Keys {
    private Keys() {}

    /** Builds the mapping with the translation key of the mod and a category of the mod, and queues it. */
    public static KeyMapping register(String id, Key defaultKey, String category) {
        String mod = KeyQueue.modId();
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(mod, category));
        KeyMapping mapping = new KeyMapping("key." + mod + "." + id, KeyCodes.code(defaultKey), cat);
        KeyQueue.add(mapping);
        return mapping;
    }

    public static boolean isKeyDown(Key key) {
        int code = KeyCodes.code(key);
        return code != GLFW.GLFW_KEY_UNKNOWN && InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), code);
    }
}
