package dev.minescreen.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/** MineScreen section hosted by NeoForge's native configuration editor. */
final class MineScreenConfigurationSection extends ConfigurationScreen.ConfigurationSectionScreen {
    MineScreenConfigurationSection(Screen parent, ModConfig.Type type, ModConfig config,
            Component title) {
        super(parent, type, config, title);
    }

}
