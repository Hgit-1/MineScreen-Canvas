package dev.minescreen;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Diagnostic light-emitting panel for Create carriage lighting tests.
 *
 * <p>The block state emits vanilla level-15 block light. Create copies that state into its
 * VirtualRenderWorld and runs the virtual light engine when a carriage is assembled. The client
 * renderer separately keeps the white face full-bright, even when a renderer does not consume the
 * virtual light field.</p>
 */
public final class TrainLightPanelBlock extends BaseEntityBlock {
    public static final MapCodec<TrainLightPanelBlock> CODEC =
            simpleCodec(TrainLightPanelBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public TrainLightPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TrainLightPanelBlockEntity(pos, state);
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> box(0, 0, 0, 16, 16, 3);
            case SOUTH -> box(0, 0, 13, 16, 16, 16);
            case EAST -> box(13, 0, 0, 16, 16, 16);
            case WEST -> box(0, 0, 0, 3, 16, 16);
            case UP -> box(0, 13, 0, 16, 16, 16);
            case DOWN -> box(0, 0, 0, 16, 3, 16);
        };
    }

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level,
            BlockPos pos) {
        if (!player.isCreative() && !player.getMainHandItem().isCorrectToolForDrops(state)) {
            return 0.0F;
        }
        return super.getDestroyProgress(state, player, level, pos);
    }
}
