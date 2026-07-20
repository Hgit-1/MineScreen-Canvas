package dev.minescreen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/** Six-direction data conduit whose arms follow adjacent conduits/endpoints automatically. */
public final class ScreenCableBlock extends PipeBlock {
    public static final MapCodec<ScreenCableBlock> CODEC = simpleCodec(ScreenCableBlock::new);

    public ScreenCableBlock(Properties properties) {
        // Two-pixel arm radius, matching the custom cable arm model.
        super(0.125F, properties);
        BlockState state = stateDefinition.any();
        for (Direction direction : Direction.values()) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction), false);
        }
        registerDefaultState(state);
    }

    @Override
    protected MapCodec<? extends PipeBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block,
            BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        BlockState state = defaultBlockState();
        for (Direction direction : Direction.values()) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction),
                    connects(context.getLevel().getBlockState(pos.relative(direction))));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction,
            BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return state.setValue(PROPERTY_BY_DIRECTION.get(direction), connects(neighborState));
    }

    private static boolean connects(BlockState neighbor) {
        return neighbor.is(MineScreen.SCREEN_CABLE_BLOCK.get())
                || neighbor.is(MineScreen.SCREEN_BLOCK.get())
                || neighbor.is(MineScreen.COMPUTER_BLOCK.get())
                || neighbor.is(MineScreen.FIXED_KEYBOARD_BLOCK.get());
    }
}
