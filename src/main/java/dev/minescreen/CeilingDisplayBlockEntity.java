package dev.minescreen;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Shared settings copied across one horizontal prism component by the server handler. */
public final class CeilingDisplayBlockEntity extends BlockEntity {
    private UUID tileId = UUID.randomUUID();
    @Nullable private UUID owner;
    private boolean linkedSides = true;
    // Carriage displays are passenger-information devices by default. Once assembled on a Create
    // train the renderer replaces these placeholders with the owning train's live timetable;
    // users only need to enter the editor when deliberately switching a face to media mode.
    private final CeilingDisplayMode[] modes = {CeilingDisplayMode.TRAFFIC,
            CeilingDisplayMode.TRAFFIC};
    private final String[] line = {"MINS", "MINS"};
    private final String[] destination = {"Destination", "Destination"};
    private final String[] nextStop = {"Next stop", "Next stop"};
    private final String[] eta = {"--", "--"};
    private final String[] status = {"", ""};
    /** Optional passenger notice, e.g. “A limited express ticket is required”. */
    private final String[] notice = {"", ""};
    private final String[] templateId = {"builtin_transit", "builtin_transit"};
    private final String[] overlayTemplateId = {"", ""};
    /** Optional exact Create station name/code that divides outward and return workings. */
    private final String[] turnaround = {"", ""};

    public CeilingDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(MineScreen.CEILING_DISPLAY_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID tileId() { return tileId; }
    @Nullable public UUID owner() { return owner; }
    public boolean linkedSides() { return linkedSides; }
    public CeilingDisplayMode mode(int side) { return modes[safeSide(side)]; }
    public String line(int side) { return line[safeSide(side)]; }
    public String destination(int side) { return destination[safeSide(side)]; }
    public String nextStop(int side) { return nextStop[safeSide(side)]; }
    public String eta(int side) { return eta[safeSide(side)]; }
    public String status(int side) { return status[safeSide(side)]; }
    public String notice(int side) { return notice[safeSide(side)]; }
    public String templateId(int side) { return templateId[safeSide(side)]; }
    public String overlayTemplateId(int side) { return overlayTemplateId[safeSide(side)]; }
    public String turnaround(int side) { return turnaround[safeSide(side)]; }

    public void setOwnerIfAbsent(UUID playerId) {
        if (owner == null) { owner = playerId; setChanged(); }
    }

    public boolean mayEdit(UUID playerId) { return owner == null || owner.equals(playerId); }

    public void applyServerUpdate(int side, boolean linkedSides, CeilingDisplayMode mode,
            String line, String destination, String nextStop, String eta, String status,
            String notice, String templateId, String overlayTemplateId, String turnaround,
            UUID editor) {
        int safe = safeSide(side);
        this.linkedSides = linkedSides;
        setSide(safe, mode, line, destination, nextStop, eta, status, notice, templateId,
                overlayTemplateId, turnaround);
        if (linkedSides) {
            setSide(1 - safe, mode, line, destination, nextStop, eta, status, notice, templateId,
                    overlayTemplateId, turnaround);
        }
        setOwnerIfAbsent(editor);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(),
                    Block.UPDATE_CLIENTS);
        }
    }

    private void setSide(int side, CeilingDisplayMode mode, String line, String destination,
            String nextStop, String eta, String status, String notice, String templateId,
            String overlayTemplateId, String turnaround) {
        modes[side] = mode == null ? CeilingDisplayMode.MEDIA : mode;
        this.line[side] = line;
        this.destination[side] = destination;
        this.nextStop[side] = nextStop;
        this.eta[side] = eta;
        this.status[side] = status;
        this.notice[side] = notice;
        this.templateId[side] = templateId;
        this.overlayTemplateId[side] = overlayTemplateId == null ? "" : overlayTemplateId;
        this.turnaround[side] = turnaround == null ? "" : turnaround;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("tile_id", tileId);
        if (owner != null) tag.putUUID("owner", owner);
        tag.putBoolean("linked_sides", linkedSides);
        for (int side = 0; side < 2; side++) {
            String key = "side_" + side + "_";
            tag.putString(key + "mode", modes[side].name());
            tag.putString(key + "line", line[side]);
            tag.putString(key + "destination", destination[side]);
            tag.putString(key + "next", nextStop[side]);
            tag.putString(key + "eta", eta[side]);
            tag.putString(key + "status", status[side]);
            tag.putString(key + "notice", notice[side]);
            tag.putString(key + "template", templateId[side]);
            tag.putString(key + "overlay_template", overlayTemplateId[side]);
            tag.putString(key + "turnaround", turnaround[side]);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("tile_id")) tileId = tag.getUUID("tile_id");
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        linkedSides = !tag.contains("linked_sides") || tag.getBoolean("linked_sides");
        for (int side = 0; side < 2; side++) {
            String key = "side_" + side + "_";
            try { modes[side] = CeilingDisplayMode.valueOf(tag.getString(key + "mode")); }
            catch (IllegalArgumentException ignored) { modes[side] = CeilingDisplayMode.MEDIA; }
            line[side] = readField(tag, key + "line", line[side]);
            destination[side] = readField(tag, key + "destination", destination[side]);
            nextStop[side] = readField(tag, key + "next", nextStop[side]);
            eta[side] = readField(tag, key + "eta", eta[side]);
            status[side] = readField(tag, key + "status", status[side]);
            notice[side] = readField(tag, key + "notice", notice[side]);
            templateId[side] = readField(tag, key + "template", templateId[side]);
            overlayTemplateId[side] = readField(tag, key + "overlay_template",
                    overlayTemplateId[side]);
            turnaround[side] = readField(tag, key + "turnaround", turnaround[side]);
        }
    }

    private static String readField(CompoundTag tag, String key, String fallback) {
        if (!tag.contains(key, Tag.TAG_STRING)) return fallback;
        String value = tag.getString(key);
        return TextDisplayBlockEntity.validTrafficField(value) ? value : fallback;
    }

    private static int safeSide(int side) { return side == 1 ? 1 : 0; }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
