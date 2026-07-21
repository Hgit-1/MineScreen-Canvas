package dev.minescreen.client.audio;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Finds connected speakers without creating a second audio stream or copying decoded audio. */
public final class SpeakerLinkResolver {
    private static final int MAX_CABLE_NODES = 4096;

    private SpeakerLinkResolver() {
    }

    static Emitter bestEmitter(ScreenGroup group) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Vec3 listener = minecraft.player == null ? null : minecraft.player.getEyePosition();
        Vec3 screenCenter = group.bounds().getCenter();
        double screenRange = MineScreenConfig.DEFAULT_SCREEN_SOUND_DISTANCE.get();
        Emitter best = emitter(screenCenter, screenRange, listener);
        if (level == null || listener == null || !level.dimension().equals(group.dimension())) {
            return best;
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos tile : group.tiles()) {
            for (Direction direction : Direction.values()) {
                BlockPos next = tile.relative(direction);
                if (level.getBlockState(next).is(MineScreen.SPEAKER_BLOCK.get())) {
                    best = better(best, emitter(Vec3.atCenterOf(next),
                            MineScreenConfig.SPEAKER_SOUND_DISTANCE.get(), listener));
                } else if (level.getBlockState(next).is(MineScreen.SCREEN_CABLE_BLOCK.get())
                        && visited.add(next.immutable())) {
                    queue.addLast(next.immutable());
                }
            }
        }
        while (!queue.isEmpty() && visited.size() <= MAX_CABLE_NODES) {
            BlockPos cable = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = cable.relative(direction);
                if (level.getBlockState(next).is(MineScreen.SPEAKER_BLOCK.get())) {
                    best = better(best, emitter(Vec3.atCenterOf(next),
                            MineScreenConfig.SPEAKER_SOUND_DISTANCE.get(), listener));
                } else if (level.getBlockState(next).is(MineScreen.SCREEN_CABLE_BLOCK.get())
                        && visited.add(next.immutable())) {
                    queue.addLast(next.immutable());
                }
            }
        }
        return best;
    }

    /** Listener-relative gain shared by FFmpeg/OpenAL and off-screen Chromium media. */
    public static float gain(ScreenGroup group) {
        return bestEmitter(group).gain();
    }

    private static Emitter emitter(Vec3 position, double range, Vec3 listener) {
        if (listener == null || range <= 0.0D) {
            return new Emitter(position, 0.0F);
        }
        double distance = listener.distanceTo(position);
        float gain = (float) Math.max(0.0D, 1.0D - distance / range);
        return new Emitter(position, gain);
    }

    private static Emitter better(Emitter first, Emitter second) {
        return second.gain() > first.gain() ? second : first;
    }

    record Emitter(Vec3 position, float gain) {
    }
}
