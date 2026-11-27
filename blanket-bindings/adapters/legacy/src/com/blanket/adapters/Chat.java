// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.LiteralText;

/** {@code Client.showMessage} and {@code Client.translate} on the legacy set. */
public final class Chat {
    private Chat() {}

    public static void showMessage(MinecraftClient mc, String text) {
        mc.inGameHud.getChatHud().addMessage(new LiteralText(text));
    }

    public static String translate(MinecraftClient mc, String key, Object[] args) {
        return I18n.translate(key, args);
    }
}
