package dev.minescreen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.Level;

/**
 * Index of client-loaded screen anchors. Block entity lifecycle hooks maintain it, so ray picking
 * scales with screen count instead of scanning thousands of block positions every tick.
 */
public final class LoadedScreenIndex {
    private static final Set<ScreenBlockEntity> LOADED = ConcurrentHashMap.newKeySet();

    private LoadedScreenIndex() {
    }

    static void add(ScreenBlockEntity screen) {
        LOADED.add(screen);
    }

    static void remove(ScreenBlockEntity screen) {
        LOADED.remove(screen);
    }

    public static Iterable<ScreenBlockEntity> in(Object level) {
        LOADED.removeIf(screen -> !isLive(screen, level));
        return LOADED;
    }

    /**
     * Block-entity removal and client chunk packets are not guaranteed to be observed in the same
     * frame. Never let a stale lifecycle entry become world geometry: the live level must still
     * own this exact block-entity instance and the block at the position must still be a screen.
     */
    private static boolean isLive(ScreenBlockEntity screen, Object expectedLevel) {
        if (screen.isRemoved() || screen.getLevel() != expectedLevel
                || !(expectedLevel instanceof Level level)) {
            return false;
        }
        return level.getBlockEntity(screen.getBlockPos()) == screen
                && level.getBlockState(screen.getBlockPos()).is(MineScreen.SCREEN_BLOCK.get());
    }
}
