package dev.minescreen.client;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import dev.minescreen.MineScreen;
import dev.minescreen.ScreenGeometry;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenRegionLayout;

/**
 * Analytical ray/plane picker. It deliberately does not depend on Minecraft's block hit result:
 * the screen can extend into air across several blocks. Group BFS remains client-tick-only, while
 * the cheap analytical intersection may be refreshed once per rendered frame so pointer UV cannot
 * lag behind the crosshair at frame rates above 20 FPS.
 */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class ScreenRaycast {
    private static final double MAX_DISTANCE = 8.0D;
    private static volatile ScreenHit currentHit;

    private ScreenRaycast() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            currentHit = null;
            return;
        }
        ScreenGroupManager.ensure(level);
        currentHit = calculate(minecraft);
    }

    /** Refreshes against the current camera rotation without rebuilding connected groups. */
    @Nullable
    public static ScreenHit raycastNow() {
        currentHit = calculate(Minecraft.getInstance());
        return currentHit;
    }

    @Nullable
    private static ScreenHit calculate(Minecraft minecraft) {
        Entity camera = minecraft.getCameraEntity();
        if (minecraft.level == null || camera == null) {
            return null;
        }
        Vec3 start = camera.getEyePosition(1.0F);
        Vec3 direction = camera.getViewVector(1.0F).normalize();
        ScreenHit nearest = null;
        // minecraft.hitResult is tick-produced and can describe the previous camera direction at
        // high FPS. Clip the current render-frame ray so an old occluder cannot make the browser
        // pointer freeze at a coordinate different from the crosshair.
        HitResult vanillaHit = minecraft.level.clip(new ClipContext(start,
                start.add(direction.scale(MAX_DISTANCE)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, camera));
        double nearestDistance = vanillaHit.getType() == HitResult.Type.MISS
                ? MAX_DISTANCE
                : Math.min(MAX_DISTANCE, vanillaHit.getLocation().distanceTo(start) + 0.01D);
        for (ScreenGroup group : ScreenGroupManager.groups()) {
            ScreenHit hit = intersect(minecraft.level, group, start, direction);
            if (hit != null && hit.distance() < nearestDistance) {
                nearest = hit;
                nearestDistance = hit.distance();
            }
        }
        return nearest;
    }

    @Nullable
    public static ScreenHit currentHit() {
        return currentHit;
    }

    @Nullable
    private static ScreenHit intersect(ClientLevel level, ScreenGroup group, Vec3 rayStart,
            Vec3 rayDirection) {
        Vec3 normal = ScreenGeometry.normal(group.facing());
        double denominator = rayDirection.dot(normal);
        if (Math.abs(denominator) < 1.0E-6D) {
            return null;
        }

        Vec3 planePoint = ScreenGeometry.origin(group.origin(), group.facing());
        double distance = planePoint.subtract(rayStart).dot(normal) / denominator;
        if (distance < 0.0D || distance > MAX_DISTANCE) {
            return null;
        }

        Vec3 point = rayStart.add(rayDirection.scale(distance));
        Vec3 local = point.subtract(planePoint);
        double horizontal = local.dot(ScreenGeometry.right(group.facing()));
        double vertical = local.dot(ScreenGeometry.up(group.facing()));
        double u = horizontal / group.columns();
        double v = 1.0D - vertical / group.rows();
        if (u < 0.0D || u > 1.0D || v < 0.0D || v > 1.0D) {
            return null;
        }
        int regionId = 0;
        double regionU = u;
        double regionV = v;
        ScreenHostNetworkManager.HostNetwork host = ScreenHostNetworkManager.networkFor(group);
        boolean joinedCanvas = host != null && host.panoramic();
        if (group.legacyAnchor()) {
            if (!ScreenTileIndex.isLive(level, group.master(), group.facing())) {
                return null;
            }
        } else {
            int column = Math.min(group.columns() - 1,
                    Math.max(0, (int) Math.floor(horizontal)));
            int row = Math.min(group.rows() - 1, Math.max(0, (int) Math.floor(vertical)));
            BlockPos tile = group.origin()
                    .relative(ScreenGeometry.rightDirection(group.facing()), column)
                    .relative(ScreenGeometry.upDirection(group.facing()), row);
            ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
            if (!group.tiles().contains(tile)
                    || !ScreenTileIndex.isLive(level, tile, group.facing())
                    || profile.disabledTiles.contains(tile.asLong())) {
                return null;
            }
            if (!joinedCanvas) {
                regionId = ScreenRegionLayout.regionAt(profile, tile);
                ScreenRegionLayout.Canvas canvas = ScreenRegionLayout.canvas(group, profile, regionId);
                if (canvas == null) {
                    return null;
                }
                regionU = (horizontal - canvas.minColumn()) / canvas.columns();
                regionV = 1.0D - (vertical - canvas.minRow()) / canvas.rows();
            }
        }
        return new ScreenHit(group.groupId(), group.master(), regionId, regionU, regionV,
                distance, group.facing());
    }

    public record ScreenHit(java.util.UUID groupId, BlockPos master, int regionId,
            double u, double v, double distance, Direction facing) {
    }
}
