package dev.minescreen.client;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Cheap client-tick visibility budget shared by video and Chromium backends. */
public final class ScreenVisibility {
    private ScreenVisibility() {
    }

    public static State evaluate(ScreenGroup group) {
        if (ScreenContentManager.hostKeepsAlive(group.groupId())) {
            return new State(true, true, false);
        }
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        if (minecraft.level == null || camera == null || minecraft.level.dimension() != group.dimension()) {
            return new State(false, false, true);
        }
        Vec3 eye = camera.getEyePosition(1.0F);
        Vec3 toCenter = group.bounds().getCenter().subtract(eye);
        double distanceSquared = toCenter.lengthSqr();
        double maxDistance = MineScreenConfig.MAX_RENDER_DISTANCE.get();
        boolean inRange = distanceSquared <= maxDistance * maxDistance;
        if (!inRange) {
            return new State(false, false, true);
        }

        AABB bounds = group.bounds();
        double radius = Math.sqrt(bounds.getXsize() * bounds.getXsize()
                + bounds.getYsize() * bounds.getYsize() + bounds.getZsize() * bounds.getZsize()) * 0.5D;
        double distance = Math.sqrt(distanceSquared);
        // Include large screens touching the edge of view. This deliberately stays a cheap tick-side
        // estimate; the BER still relies on Minecraft's real render frustum.
        boolean onScreen = distance < radius + 1.0D || toCenter.normalize()
                .dot(camera.getViewVector(1.0F)) > -Math.min(0.35D, radius / Math.max(1.0D, distance));
        return new State(true, onScreen, distance > maxDistance * 0.5D);
    }

    public record State(boolean inRange, boolean onScreen, boolean far) {
        public boolean active() {
            return inRange && onScreen;
        }
    }
}
