// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.api.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** {@code Client.crosshairTarget} on the unobf set (binding-format.md example 6). */
public final class Targets {
    private Targets() {}

    public static Target crosshair(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (mc.level == null || hit == null) return null;
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult b = (BlockHitResult) hit;
            return Target.block(b.getBlockPos(), b.getDirection(), hit.getLocation());
        }
        if (hit.getType() == HitResult.Type.ENTITY) return Target.entity(hit.getLocation());
        return Target.miss(hit.getLocation());
    }
}
