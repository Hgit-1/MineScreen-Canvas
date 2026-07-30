package dev.minescreen.network;

import dev.minescreen.DisplayBackMode;
import dev.minescreen.MineScreen;
import dev.minescreen.StationDisplayMode;
import dev.minescreen.TextDisplayAnimation;
import dev.minescreen.TextDisplayBlockEntity;
import dev.minescreen.TrafficDisplayRole;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-authoritative settings for a regular traffic display mounted on a Create contraption. */
public record MovingTrafficDisplayUpdatePayload(int entityId, BlockPos localPos, String line,
        String destination, String currentStop, String nextStop, String eta, String status,
        String templateId, String overlayTemplateId, int textColor, int backgroundColor,
        TextDisplayAnimation animation,
        float speed, int fontSize, boolean backSide, DisplayBackMode backMode,
        TrafficDisplayRole trafficRole, StationDisplayMode stationMode, String stationBindingName,
        String stationTrainType, String stationTurnaroundName, String stationMapTemplateId)
        implements CustomPacketPayload {
    public static final Type<MovingTrafficDisplayUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                    "moving_traffic_display_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MovingTrafficDisplayUpdatePayload>
            STREAM_CODEC = StreamCodec.of(MovingTrafficDisplayUpdatePayload::encode,
                    MovingTrafficDisplayUpdatePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer,
            MovingTrafficDisplayUpdatePayload payload) {
        buffer.writeVarInt(payload.entityId);
        buffer.writeBlockPos(payload.localPos);
        buffer.writeUtf(payload.line, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.destination, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.currentStop, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.nextStop, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.eta, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.status, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.templateId, TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.overlayTemplateId,
                TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeInt(payload.textColor);
        buffer.writeInt(payload.backgroundColor);
        buffer.writeEnum(payload.animation);
        buffer.writeFloat(payload.speed);
        buffer.writeVarInt(payload.fontSize);
        buffer.writeBoolean(payload.backSide);
        buffer.writeEnum(payload.backMode);
        buffer.writeEnum(payload.trafficRole);
        buffer.writeEnum(payload.stationMode);
        buffer.writeUtf(payload.stationBindingName,
                TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.stationTrainType,
                TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.stationTurnaroundName,
                TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        buffer.writeUtf(payload.stationMapTemplateId,
                TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
    }

    private static MovingTrafficDisplayUpdatePayload decode(RegistryFriendlyByteBuf buffer) {
        return new MovingTrafficDisplayUpdatePayload(buffer.readVarInt(), buffer.readBlockPos(),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH),
                buffer.readInt(), buffer.readInt(), buffer.readEnum(TextDisplayAnimation.class),
                buffer.readFloat(), buffer.readVarInt(), buffer.readBoolean(),
                buffer.readEnum(DisplayBackMode.class), buffer.readEnum(TrafficDisplayRole.class),
                buffer.readEnum(StationDisplayMode.class),
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
