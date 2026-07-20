package dev.minescreen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Persistent power flag for the host's miniature standby preview. */
public final class ComputerBlockEntity extends BlockEntity {
    private boolean powered = true;
    private java.util.UUID clientLinkedGroupId;
    private long clientNextLinkResolveTick;

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(MineScreen.COMPUTER_BLOCK_ENTITY.get(), pos, state);
    }

    public boolean powered() {
        return powered;
    }

    public void setPowered(boolean powered) {
        if (this.powered != powered) {
            this.powered = powered;
            setChanged();
        }
    }

    public java.util.UUID clientLinkedGroupId() {
        return clientLinkedGroupId;
    }

    public long clientNextLinkResolveTick() {
        return clientNextLinkResolveTick;
    }

    public void setClientLink(java.util.UUID groupId, long nextResolveTick) {
        clientLinkedGroupId = groupId;
        clientNextLinkResolveTick = nextResolveTick;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("powered", powered);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        powered = !tag.contains("powered") || tag.getBoolean("powered");
    }
}
