package dev.minescreen.network;

import java.util.UUID;

import dev.minescreen.MineScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Authoritative server state sent to clients; media/frame bytes are deliberately absent. */
public record ScreenStatePayload(UUID screenId, ResourceLocation dimension, BlockPos master,
        int columns, int rows, String contentType, String sourceReference, long positionMs,
        long serverGameTime, boolean paused, boolean loop, float volume, ScreenAccess access,
        UUID owner, UUID controller) implements CustomPacketPayload {
    public static final Type<ScreenStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "screen_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenStatePayload> STREAM_CODEC =
            StreamCodec.of(ScreenStatePayload::encode, ScreenStatePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ScreenStatePayload payload) {
        buffer.writeUUID(payload.screenId);
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeBlockPos(payload.master);
        buffer.writeVarInt(payload.columns);
        buffer.writeVarInt(payload.rows);
        buffer.writeUtf(payload.contentType, 16);
        buffer.writeUtf(payload.sourceReference, 2048);
        buffer.writeVarLong(payload.positionMs);
        buffer.writeVarLong(payload.serverGameTime);
        buffer.writeBoolean(payload.paused);
        buffer.writeBoolean(payload.loop);
        buffer.writeFloat(payload.volume);
        buffer.writeEnum(payload.access);
        buffer.writeUUID(payload.owner);
        buffer.writeBoolean(payload.controller != null);
        if (payload.controller != null) {
            buffer.writeUUID(payload.controller);
        }
    }

    private static ScreenStatePayload decode(RegistryFriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        ResourceLocation dimension = buffer.readResourceLocation();
        BlockPos master = buffer.readBlockPos();
        int columns = buffer.readVarInt();
        int rows = buffer.readVarInt();
        String contentType = buffer.readUtf(16);
        String source = buffer.readUtf(2048);
        long position = buffer.readVarLong();
        long gameTime = buffer.readVarLong();
        boolean paused = buffer.readBoolean();
        boolean loop = buffer.readBoolean();
        float volume = buffer.readFloat();
        ScreenAccess access = buffer.readEnum(ScreenAccess.class);
        UUID owner = buffer.readUUID();
        UUID controller = buffer.readBoolean() ? buffer.readUUID() : null;
        return new ScreenStatePayload(id, dimension, master, columns, rows, contentType, source,
                position, gameTime, paused, loop, volume, access, owner, controller);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
