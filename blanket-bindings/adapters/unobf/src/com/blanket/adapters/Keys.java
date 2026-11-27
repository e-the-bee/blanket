// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.api.Key;
import com.blanket.runtime.KeyQueue;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/** {@code KeyBinding.register} and {@code Input.isKeyDown} on the unobf set (GLFW codes, {@code KeyMapping.Category}). */
public final class Keys {
    private Keys() {}

    /**
     * The mod's categories registered so far, by name. {@code KeyMapping.Category.register} throws
     * {@code IllegalArgumentException("Category '%s' is already registered.")} for an id it has seen (javap 26.1.2), so
     * the second key of a category reuses the first one's category.
     */
    private static final Map<String, KeyMapping.Category> CATEGORIES = new HashMap<String, KeyMapping.Category>();

    /**
     * Builds the mapping with the translation key of the mod and a category of the mod, and queues it. The category's
     * translation key is {@code key.category.<modid>.<category>} ({@code Category#label}: {@code Identifier#toLanguageKey("key.category")}).
     */
    public static KeyMapping register(String id, Key defaultKey, String category) {
        String mod = KeyQueue.modId();
        KeyMapping.Category cat = CATEGORIES.get(category);
        if (cat == null) {
            cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(mod, category));
            CATEGORIES.put(category, cat);
        }
        KeyMapping mapping = new KeyMapping("key." + mod + "." + id, KeyCodes.code(defaultKey), cat);
        KeyQueue.add(mapping);
        return mapping;
    }

    public static boolean isKeyDown(Key key) {
        int code = KeyCodes.code(key);
        return code != GLFW.GLFW_KEY_UNKNOWN && InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), code);
    }
}
