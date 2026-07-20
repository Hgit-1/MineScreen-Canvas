package dev.minescreen.network;

import java.util.UUID;

import dev.minescreen.MineScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client request to update shared metadata. It never contains pixels, passwords or local paths. */
public record ScreenStateUpdatePayload(UUID screenId, ResourceLocation dimension, BlockPos master,
        int columns, int rows, String contentType, String sourceReference, long positionMs,
        boolean paused, boolean loop, float volume, ScreenAccess access) implements CustomPacketPayload {
    public static final Type<ScreenStateUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "screen_state_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenStateUpdatePayload> STREAM_CODEC =
            StreamCodec.of(ScreenStateUpdatePayload::encode, ScreenStateUpdatePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ScreenStateUpdatePayload payload) {
        buffer.writeUUID(payload.screenId);
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeBlockPos(payload.master);
        buffer.writeVarInt(payload.columns);
        buffer.writeVarInt(payload.rows);
        buffer.writeUtf(payload.contentType, 16);
        buffer.writeUtf(payload.sourceReference, 2048);
        buffer.writeVarLong(payload.positionMs);
        buffer.writeBoolean(payload.paused);
        buffer.writeBoolean(payload.loop);
        buffer.writeFloat(payload.volume);
        buffer.writeEnum(payload.access);
    }

    private static ScreenStateUpdatePayload decode(RegistryFriendlyByteBuf buffer) {
        return new ScreenStateUpdatePayload(buffer.readUUID(), buffer.readResourceLocation(),
                buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(16),
                buffer.readUtf(2048), buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readFloat(), buffer.readEnum(ScreenAccess.class));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
