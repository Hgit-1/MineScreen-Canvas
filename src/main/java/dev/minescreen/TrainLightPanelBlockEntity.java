package dev.minescreen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Stateless BE retained inside Create's virtual carriage render world. */
public final class TrainLightPanelBlockEntity extends BlockEntity {
    public TrainLightPanelBlockEntity(BlockPos pos, BlockState state) {
        super(MineScreen.TRAIN_LIGHT_PANEL_BLOCK_ENTITY.get(), pos, state);
    }
}
