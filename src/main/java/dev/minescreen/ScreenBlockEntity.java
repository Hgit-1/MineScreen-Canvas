package dev.minescreen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Server-safe state for a screen anchor. Width/height are in blocks and deliberately do not
 * create child block entities; one renderer owns the whole surface and one texture is reused.
 */
public final class ScreenBlockEntity extends BlockEntity {
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 32;

    private int screenWidth = 1;
    private int screenHeight = 1;
    private UUID tileId = UUID.randomUUID();
    private boolean legacyAnchor;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(MineScreen.SCREEN_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            LoadedScreenIndex.add(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide) {
            LoadedScreenIndex.remove(this);
        }
        super.setRemoved();
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public UUID tileId() {
        return tileId;
    }

    public boolean isLegacyAnchor() {
        return legacyAnchor;
    }

    public void markLegacyAnchor() {
        legacyAnchor = true;
    }

    public void setScreenSize(int width, int height) {
        screenWidth = clampSize(width);
        screenHeight = clampSize(height);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public Direction facing() {
        return getBlockState().getValue(ScreenBlock.FACING);
    }

    /** Local lower-left point on the visible side, with a small offset to avoid z-fighting. */
    public Vec3 screenOrigin() {
        return ScreenGeometry.origin(getBlockPos(), facing()).subtract(Vec3.atLowerCornerOf(getBlockPos()));
    }

    /** Screen-right axis as seen when looking at the visible side. */
    public Vec3 screenRight() {
        return ScreenGeometry.right(facing());
    }

    public Vec3 screenUp() {
        return new Vec3(0, 1, 0);
    }

    public Vec3 screenNormal() {
        return ScreenGeometry.normal(facing());
    }

    private static int clampSize(int value) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, value));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("screen_width", screenWidth);
        tag.putInt("screen_height", screenHeight);
        tag.putUUID("tile_id", tileId);
        tag.putString("render_mode", legacyAnchor ? "legacy_anchor" : "tile");
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("screen_width", Tag.TAG_INT)) {
            screenWidth = clampSize(tag.getInt("screen_width"));
        }
        if (tag.contains("screen_height", Tag.TAG_INT)) {
            screenHeight = clampSize(tag.getInt("screen_height"));
        }
        if (tag.hasUUID("tile_id")) {
            tileId = tag.getUUID("tile_id");
        }
        if (tag.contains("render_mode", Tag.TAG_STRING)) {
            legacyAnchor = "legacy_anchor".equals(tag.getString("render_mode"));
        } else {
            // Stage 1 worlds had no mode marker. Preserve only genuinely oversized anchors.
            legacyAnchor = screenWidth > 1 || screenHeight > 1;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
