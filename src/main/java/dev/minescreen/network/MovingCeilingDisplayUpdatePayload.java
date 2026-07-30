package dev.minescreen.network;

import dev.minescreen.CeilingDisplayMode;
import dev.minescreen.MineScreen;
import dev.minescreen.TextDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Updates a display stored inside a moving Create contraption.
 *
 * <p>The position is local to the contraption, so it must never be handled by the ordinary
 * world-block update path.</p>
 */
public record MovingCeilingDisplayUpdatePayload(int entityId, BlockPos localPos, int side,
        boolean linkedSides, CeilingDisplayMode mode, String line, String destination,
        String nextStop, String eta, String status, String notice, String templateId,
        String overlayTemplateId, String turnaround)
        implements CustomPacketPayload {
    public static final Type<MovingCeilingDisplayUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                    "moving_ceiling_display_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MovingCeilingDisplayUpdatePayload>
            STREAM_CODEC = StreamCodec.of(MovingCeilingDisplayUpdatePayload::encode,
                    MovingCeilingDisplayUpdatePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer,
            MovingCeilingDisplayUpdatePayload payload) {
        buffer.writeVarInt(payload.entityId);
        buffer.writeBlockPos(payload.localPos);
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
    }

    private static MovingCeilingDisplayUpdatePayload decode(RegistryFriendlyByteBuf buffer) {
        return new MovingCeilingDisplayUpdatePayload(buffer.readVarInt(), buffer.readBlockPos(),
                buffer.readByte(), buffer.readBoolean(), buffer.readEnum(CeilingDisplayMode.class),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
