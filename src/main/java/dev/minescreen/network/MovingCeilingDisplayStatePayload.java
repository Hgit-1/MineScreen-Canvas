package dev.minescreen.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.minescreen.CeilingDisplayMode;
import dev.minescreen.MineScreen;
import dev.minescreen.TextDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Mirrors an accepted moving-display update to every client tracking the carriage. */
public record MovingCeilingDisplayStatePayload(int entityId, List<BlockPos> localTiles, int side,
        boolean linkedSides, CeilingDisplayMode mode, String line, String destination,
        String nextStop, String eta, String status, String notice, String templateId,
        String overlayTemplateId, String turnaround, UUID editor)
        implements CustomPacketPayload {
    private static final int MAX_TILES = 256;
    public static final Type<MovingCeilingDisplayStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                    "moving_ceiling_display_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MovingCeilingDisplayStatePayload>
            STREAM_CODEC = StreamCodec.of(MovingCeilingDisplayStatePayload::encode,
                    MovingCeilingDisplayStatePayload::decode);

    public MovingCeilingDisplayStatePayload {
        localTiles = List.copyOf(localTiles == null ? List.of() : localTiles);
        if (localTiles.size() > MAX_TILES) {
            throw new IllegalArgumentException("Too many moving display tiles");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer,
            MovingCeilingDisplayStatePayload payload) {
        buffer.writeVarInt(payload.entityId);
        int count = Math.min(MAX_TILES, payload.localTiles.size());
        buffer.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            buffer.writeBlockPos(payload.localTiles.get(index));
        }
        buffer.writeByte(payload.side);
        buffer.writeBoolean(payload.linkedSides);
        buffer.writeEnum(payload.mode);
        buffer.writeUtf(payload.line, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.destination, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.nextStop, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.eta, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.status, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.notice, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.templateId, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.overlayTemplateId,
                TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.turnaround, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUUID(payload.editor);
    }

    private static MovingCeilingDisplayStatePayload decode(RegistryFriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_TILES) {
            // Never clamp a wire count: doing so leaves the discarded entries in the payload
            // buffer and shifts every following field, producing corrupt state or decoder abuse.
            throw new IllegalArgumentException("Invalid moving display tile count: " + count);
        }
        List<BlockPos> tiles = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            tiles.add(buffer.readBlockPos());
        }
        return new MovingCeilingDisplayStatePayload(entityId, tiles, buffer.readByte(),
                buffer.readBoolean(), buffer.readEnum(CeilingDisplayMode.class),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
