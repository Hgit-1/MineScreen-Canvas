package dev.minescreen.client;

import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import dev.minescreen.MineScreen;

public final class ClientEvents {
    private ClientEvents() {
    }

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(MineScreen.SCREEN_BLOCK_ENTITY.get(), ScreenBlockRenderer::new);
        event.registerBlockEntityRenderer(MineScreen.COMPUTER_BLOCK_ENTITY.get(), ComputerBlockRenderer::new);
        event.registerBlockEntityRenderer(MineScreen.TEXT_DISPLAY_BLOCK_ENTITY.get(),
                TextDisplayRenderer::new);
        event.registerBlockEntityRenderer(MineScreen.CEILING_DISPLAY_BLOCK_ENTITY.get(),
                CeilingDisplayRenderer::new);
        event.registerBlockEntityRenderer(MineScreen.TRAIN_LIGHT_PANEL_BLOCK_ENTITY.get(),
                TrainLightPanelRenderer::new);
    }
}
