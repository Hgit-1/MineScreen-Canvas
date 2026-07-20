package dev.minescreen.client.ui;

import java.util.Comparator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;
import dev.minescreen.MineScreenConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;

/** Public registration point for optional Otyacraft/Cloth/Architectury adapter modules. */
public final class MineScreenUiRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, MineScreenUiProvider> PROVIDERS = new ConcurrentHashMap<>();
    private static boolean servicesLoaded;

    private MineScreenUiRegistry() {
    }

    public static void register(MineScreenUiProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new IllegalArgumentException("MineScreen UI provider must have a non-empty id");
        }
        PROVIDERS.put(provider.id().toLowerCase(java.util.Locale.ROOT), provider);
    }

    public static void render(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
            float partialTick, Runnable builtInLayer) {
        loadServices();
        String selection = MineScreenConfig.UI_PROVIDER.get().trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (selection.equals("vanilla")) {
            builtInLayer.run();
            return;
        }
        MineScreenUiProvider provider = selection.equals("auto")
                ? PROVIDERS.values().stream().filter(MineScreenUiProvider::available)
                        .max(Comparator.comparingInt(MineScreenUiProvider::priority)).orElse(null)
                : PROVIDERS.get(selection);
        if (provider != null && provider.available()) {
            try {
                provider.render(screen, graphics, mouseX, mouseY, partialTick, builtInLayer);
                return;
            } catch (RuntimeException exception) {
                LOGGER.error("MineScreen UI provider {} failed; using built-in compositor",
                        provider.id(), exception);
            }
        }
        if (MineScreenConfig.COMPOSITE_UI_LAYER.get()) {
            UiLayerCompositor.compose(graphics, builtInLayer);
        } else {
            builtInLayer.run();
        }
    }

    private static synchronized void loadServices() {
        if (servicesLoaded) {
            return;
        }
        servicesLoaded = true;
        ServiceLoader.load(MineScreenUiProvider.class).forEach(MineScreenUiRegistry::register);
    }
}
