package dev.minescreen.client;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.client.ui.MineScreenAssistant;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/** NeoForge's native config editor with a small optional MineScreen decoration. */
final class MineScreenConfigurationSection extends ConfigurationScreen.ConfigurationSectionScreen {
    MineScreenConfigurationSection(Screen parent, ModConfig.Type type, ModConfig config,
            Component title) {
        super(parent, type, config, title);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (MineScreenConfig.UI_SHOW_MASCOT.get()) {
            MineScreenAssistant.drawGui(graphics, width - 34, 7, 2, 220);
        }
    }
}
