// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.api.Target;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;

/** {@code Client.crosshairTarget} on the legacy set: 1.8.9 has one hit class with a type enum (binding-format.md example 6). */
public final class Targets {
    private Targets() {}

    public static Target crosshair(MinecraftClient mc) {
        BlockHitResult hit = mc.result;
        if (mc.world == null || hit == null) return null;
        if (hit.type == BlockHitResult.Type.BLOCK) return Target.block(hit.getBlockPos(), hit.direction, hit.pos);
        if (hit.type == BlockHitResult.Type.ENTITY) return Target.entity(hit.pos);
        return Target.miss(hit.pos);
    }
}
