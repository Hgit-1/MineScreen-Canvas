package dev.minescreen.network;

import dev.minescreen.MineScreen;
import dev.minescreen.DisplayBackMode;
import dev.minescreen.TextDisplayAnimation;
import dev.minescreen.TextDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client edit request; the server validates it before changing the block entity. */
public record TextDisplayUpdatePayload(ResourceLocation dimension, BlockPos pos, String text,
        int textColor, int backgroundColor, TextDisplayAnimation animation,
        float speed, int fontSize, boolean backSide, DisplayBackMode backMode)
        implements CustomPacketPayload {
    public static final Type<TextDisplayUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID, "text_display_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TextDisplayUpdatePayload> STREAM_CODEC =
            StreamCodec.of(TextDisplayUpdatePayload::encode, TextDisplayUpdatePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, TextDisplayUpdatePayload payload) {
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeBlockPos(payload.pos);
        buffer.writeUtf(payload.text, TextDisplayBlockEntity.MAX_TEXT_LENGTH);
        buffer.writeInt(payload.textColor);
        buffer.writeInt(payload.backgroundColor);
        buffer.writeEnum(payload.animation);
        buffer.writeFloat(payload.speed);
        buffer.writeVarInt(payload.fontSize);
        buffer.writeBoolean(payload.backSide);
        buffer.writeEnum(payload.backMode);
    }

    private static TextDisplayUpdatePayload decode(RegistryFriendlyByteBuf buffer) {
        return new TextDisplayUpdatePayload(buffer.readResourceLocation(), buffer.readBlockPos(),
                buffer.readUtf(TextDisplayBlockEntity.MAX_TEXT_LENGTH), buffer.readInt(),
                buffer.readInt(), buffer.readEnum(TextDisplayAnimation.class), buffer.readFloat(),
                buffer.readVarInt(), buffer.readBoolean(), buffer.readEnum(DisplayBackMode.class));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
