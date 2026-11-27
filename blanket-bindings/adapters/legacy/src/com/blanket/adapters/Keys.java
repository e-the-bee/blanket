// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.api.Key;
import com.blanket.runtime.KeyQueue;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.input.Keyboard;

/** {@code KeyBinding.register} and {@code Input.isKeyDown} on the legacy set (LWJGL 2 key codes). */
public final class Keys {
    private Keys() {}

    /** Builds the mapping with the translation keys of the mod and queues it for the loader recipe. */
    public static KeyBinding register(String id, Key defaultKey, String category) {
        String mod = KeyQueue.modId();
        KeyBinding mapping = new KeyBinding("key." + mod + "." + id, KeyCodes.code(defaultKey), "key.categories." + mod + "." + category);
        KeyQueue.add(mapping);
        return mapping;
    }

    public static boolean isKeyDown(Key key) {
        int code = KeyCodes.code(key);
        return code != Keyboard.KEY_NONE && Keyboard.isKeyDown(code);
    }
}
