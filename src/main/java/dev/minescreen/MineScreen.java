package dev.minescreen;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Common entry point. Client-only rendering classes are loaded from ClientEvents. */
@Mod(MineScreen.MOD_ID)
public final class MineScreen {
    public static final String MOD_ID = "minescreen";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MOD_ID);
    public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);

    public static final DeferredHolder<Block, ScreenBlock> SCREEN_BLOCK = BLOCKS.register(
            "screen", () -> new ScreenBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK)
                    .strength(2.0F).requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredHolder<Block, ScreenCableBlock> SCREEN_CABLE_BLOCK = BLOCKS.register(
            "screen_cable", () -> new ScreenCableBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE).strength(0.8F).noOcclusion()));
    public static final DeferredHolder<Block, FixedKeyboardBlock> FIXED_KEYBOARD_BLOCK = BLOCKS.register(
            "fixed_keyboard", () -> new FixedKeyboardBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK).strength(1.5F).noOcclusion()));
    public static final DeferredHolder<Block, ComputerBlock> COMPUTER_BLOCK = BLOCKS.register(
            "computer", () -> new ComputerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(3.0F).requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredHolder<Item, BlockItem> SCREEN_ITEM = ITEMS.register(
            "screen", () -> new BlockItem(SCREEN_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> SCREEN_CABLE_ITEM = ITEMS.register(
            "screen_cable", () -> new BlockItem(SCREEN_CABLE_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> FIXED_KEYBOARD_ITEM = ITEMS.register(
            "fixed_keyboard", () -> new BlockItem(FIXED_KEYBOARD_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> COMPUTER_ITEM = ITEMS.register(
            "computer", () -> new BlockItem(COMPUTER_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, KeyboardItem> KEYBOARD_ITEM = ITEMS.register(
            "keyboard", () -> new KeyboardItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<ScreenBlockEntity>> SCREEN_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("screen", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                    .of(ScreenBlockEntity::new, SCREEN_BLOCK.get()).build(null));
    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<ComputerBlockEntity>> COMPUTER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("computer", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                    .of(ComputerBlockEntity::new, COMPUTER_BLOCK.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_TABS.register("tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.minescreen"))
                    .icon(() -> SCREEN_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(SCREEN_ITEM.get());
                        output.accept(SCREEN_CABLE_ITEM.get());
                        output.accept(FIXED_KEYBOARD_ITEM.get());
                        output.accept(COMPUTER_ITEM.get());
                        output.accept(KEYBOARD_ITEM.get());
                    })
                    .build());
    public static final DeferredHolder<SoundEvent, SoundEvent> VIDEO_AUDIO = SOUND_EVENTS.register(
            "video_audio", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "video_audio")));

    public MineScreen(IEventBus modBus, ModContainer modContainer) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        CREATIVE_TABS.register(modBus);
        SOUND_EVENTS.register(modBus);
        modBus.addListener(this::addCreative);
        modBus.addListener(dev.minescreen.network.MineScreenNetwork::registerPayloads);
        modContainer.registerConfig(ModConfig.Type.COMMON, MineScreenConfig.SPEC);

        // MOD-bus lifecycle listeners are registered explicitly; no deprecated Bus.MOD annotation.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.minescreen.client.ClientBootstrap.register(modBus, modContainer);
        }

    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        // The custom tab already owns the item. Also expose it in the vanilla building tab.
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(SCREEN_ITEM.get());
            event.accept(SCREEN_CABLE_ITEM.get());
            event.accept(FIXED_KEYBOARD_ITEM.get());
            event.accept(COMPUTER_ITEM.get());
            event.accept(KEYBOARD_ITEM.get());
        }
    }
}
