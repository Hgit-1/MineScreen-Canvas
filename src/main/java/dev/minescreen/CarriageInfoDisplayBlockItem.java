package dev.minescreen;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** Three-step inventory guidance for the automatic carriage information strip. */
public final class CarriageInfoDisplayBlockItem extends BlockItem {
    public CarriageInfoDisplayBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
            List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable(
                "item.minescreen.carriage_info_display.guide_place")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(
                "item.minescreen.carriage_info_display.guide_schedule")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable(
                "item.minescreen.carriage_info_display.guide_edit")
                .withStyle(ChatFormatting.YELLOW));
    }
}
