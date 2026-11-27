// SPDX-License-Identifier: GPL-3.0-only
package com.example.apitour;

import com.blanket.api.Blanket;
import com.blanket.api.BlockPos;
import com.blanket.api.BlockState;
import com.blanket.api.Box;
import com.blanket.api.Client;
import com.blanket.api.ClientMod;
import com.blanket.api.Direction;
import com.blanket.api.Events;
import com.blanket.api.Feature;
import com.blanket.api.ItemStack;
import com.blanket.api.Player;
import com.blanket.api.Target;
import com.blanket.api.Vec3;
import com.blanket.api.World;

import java.util.Collections;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * The M2 api-tour sample: every 40 ticks it logs one line per API value, {@code [apitour] <key>=<value>}, and ends the
 * block with {@code [apitour] end}. The gate compares the lines with {@code expected.json}.
 */
public class ApiTour implements ClientMod {
    private int ticks;

    @Override
    public void init() {
        log("init");
        Events.CLIENT_TICK_END.register(e -> {
            Client client = Client.get();
            Player p = client.player();
            if (p == null || ++ticks % 40 != 0) {
                return;
            }
            World world = client.world();

            log("pos=" + f(p.x()) + " " + f(p.y()) + " " + f(p.z()));
            double yaw = ((p.yaw() % 360) + 360) % 360;   // raw yaw is 180 or -180 depending on the target
            log("rot=" + f(yaw) + " " + f(p.pitch()));
            log("onGround=" + p.onGround());
            log("health=" + f(p.health()));
            log("maxHealth=" + f(p.maxHealth()));
            log("food=" + p.foodLevel());
            log("saturation=" + f(p.saturation()));
            log("main=" + item(p.mainHandItem()));
            log("slot8=" + item(p.inventoryItem(8)));
            Box box = p.boundingBox();
            Vec3 position = p.position();

            BlockPos below = BlockPos.of((int) Math.floor(p.x()), (int) Math.floor(p.y()) - 1, (int) Math.floor(p.z()));
            log("up.y=" + below.offset(Direction.UP).y());
            BlockState state = world.blockState(below);
            log("block.id=" + state.blockId());
            log("block.facing=" + state.propertyValue("facing"));
            log("block.air=" + state.isAir());
            log("block.nope=" + state.propertyValue("nope"));
            log("dim=" + world.dimension());

            if (Blanket.supports(Feature.OFFHAND)) {
                System.out.println("[apitour] offhand=" + item(p.offHandItem()));   // the whole prefix is one constant: the 1.8.9 jars drop it
            } else {
                System.out.println("[apitour] offhand.skip=true");
            }

            Target target = client.crosshairTarget();
            if (target == null) {
                log("target=null");
            } else {
                BlockPos at = target.blockPos();
                log("target=" + target.kind() + " " + (at == null ? "null" : at.x() + " " + at.y() + " " + at.z()) + " " + (target.side() == null ? "null" : target.side().name()));
                Vec3 loc = target.location();
                log("target.loc=" + (loc == null ? "null" : f(loc.x()) + " " + f(loc.y()) + " " + f(loc.z())));
                log("side.switch=" + sideName(target.side()));
            }

            ToDoubleFunction<Player> fy = Player::y;
            ToDoubleFunction<Player> xz = q -> q.x() + q.z();
            log("mref.y=" + f(fy.applyAsDouble(p)));
            log("lambda.xz=" + f(xz.applyAsDouble(p)));
            int seen = 0;
            for (Player q : Collections.singletonList(p)) {
                if (q != null) {
                    seen++;
                }
            }
            log("loop=" + seen);

            log("dir=" + Direction.NORTH.opposite().name() + " " + Direction.NORTH.ordinal() + " " + Direction.values().length);
            log("box.w=" + f(box.maxX() - box.minX()));
            log("dist=" + f(Vec3.of(p.x(), p.y(), p.z()).distanceTo(position)));
            log("end");
        });
    }

    /** A classic enum switch over all six constants (javac compiles it through a {@code $SwitchMap$} array). */
    static String sideName(Direction side) {
        if (side == null) {
            return "null";
        }
        switch (side) {
            case DOWN: return "DOWN";
            case UP: return "UP";
            case NORTH: return "NORTH";
            case SOUTH: return "SOUTH";
            case WEST: return "WEST";
            case EAST: return "EAST";
            default: return "?";
        }
    }

    private static String item(ItemStack s) {
        return s.itemId() + " " + s.count() + " " + s.displayName() + " " + s.isEmpty();
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static void log(String line) {
        System.out.println("[apitour] " + line);
    }
}
