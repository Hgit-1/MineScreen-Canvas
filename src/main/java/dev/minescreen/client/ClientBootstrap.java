package dev.minescreen.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only MOD-bus registration, invoked only after the physical side check. */
public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void register(IEventBus modBus, ModContainer modContainer) {
        modBus.addListener(ScreenTextureManager::onClientSetup);
        modBus.addListener(ClientEvents::onRegisterRenderers);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> new ConfigurationScreen(container, parent));
    }
}
