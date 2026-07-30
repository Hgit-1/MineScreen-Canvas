package dev.minescreen;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** Inventory guidance for the fixed platform traffic display workflow. */
public final class TrafficDisplayBlockItem extends BlockItem {
    public TrafficDisplayBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
            List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.minescreen.traffic_display.guide_place")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.minescreen.traffic_display.guide_open")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("item.minescreen.traffic_display.guide_bind")
                .withStyle(ChatFormatting.AQUA));
    }
}
