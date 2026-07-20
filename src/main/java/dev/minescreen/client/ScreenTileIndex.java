package dev.minescreen.client;

import java.util.ArrayList;
import java.util.List;

import dev.minescreen.LoadedScreenIndex;
import dev.minescreen.MineScreen;
import dev.minescreen.ScreenBlock;
import dev.minescreen.ScreenBlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Snapshot helper for client-loaded screen tiles. */
public final class ScreenTileIndex {
    private ScreenTileIndex() {
    }

    public static List<ScreenBlockEntity> snapshot(ClientLevel level) {
        List<ScreenBlockEntity> result = new ArrayList<>();
        for (ScreenBlockEntity screen : LoadedScreenIndex.in(level)) {
            if (isLive(level, screen)) {
                result.add(screen);
            }
        }
        return result;
    }

    public static boolean isLive(ClientLevel level, ScreenBlockEntity screen) {
        return screen != null && !screen.isRemoved() && screen.getLevel() == level
                && level.getBlockEntity(screen.getBlockPos()) == screen
                && level.getBlockState(screen.getBlockPos()).is(MineScreen.SCREEN_BLOCK.get());
    }

    public static boolean isLive(ClientLevel level, BlockPos pos, Direction facing) {
        if (!level.getBlockState(pos).is(MineScreen.SCREEN_BLOCK.get())
                || level.getBlockState(pos).getValue(ScreenBlock.FACING) != facing) {
            return false;
        }
        return level.getBlockEntity(pos) instanceof ScreenBlockEntity screen
                && isLive(level, screen) && screen.facing() == facing;
    }
}
