// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/** {@code Client.showMessage} and {@code Client.translate} on the modern set. */
public final class Chat {
    private Chat() {}

    public static void showMessage(Minecraft mc, String text) {
        mc.gui.getChat().addMessage(Component.literal(text));
    }

    public static String translate(Minecraft mc, String key, Object[] args) {
        return I18n.get(key, args);
    }
}
