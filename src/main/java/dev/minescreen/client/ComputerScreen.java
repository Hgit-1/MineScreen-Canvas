package dev.minescreen.client;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenResolution;
import dev.minescreen.client.vnc.RfbEndpoint;
import dev.minescreen.client.vnc.VncCredentialStore;
import dev.minescreen.client.web.BrowserRequestPolicy;
import dev.minescreen.client.web.BrowserSession;
import dev.minescreen.client.ui.MineScreenUiRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Enlarged host console with info, content, and live mouse-enabled preview tabs. */
public final class ComputerScreen extends Screen {
    private static final int[] RESOLUTIONS = {100, 75, 50, 33, 25};
    private final BlockPos computerPos;
    private ScreenGroup group;
    private HostTab tab = HostTab.PREVIEW;
    private final Map<Button, BlockPos> tileButtons = new HashMap<>();
    private final BrowserTabButton[] browserTabs = new BrowserTabButton[6];
    private final int[] displayedTabs = {-1, -1, -1, -1, -1, -1};
    private Button infoButton;
    private Button contentButton;
    private Button previewButton;
    private Button resolutionButton;
    private Button volumeDownButton;
    private Button volumeUpButton;
    private final Button[] contentModeButtons = new Button[ScreenContentType.values().length];
    private EditBox contentSourceBox;
    private EditBox contentPasswordBox;
    private EditBox contentMediaIdBox;
    private Button contentApplyButton;
    private Button contentChooseButton;
    private Button contentBackButton;
    private Button contentForwardButton;
    private Button contentReloadButton;
    private ClientScreenProfile contentDraft;
    private Component contentStatus = Component.empty();
    private int contentStatusColor = 0xFF9FB1C5;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int previewLeft;
    private int previewTop;
    private int previewWidth;
    private int previewHeight;
    private int contentPreviewLeft;
    private int contentPreviewTop;
    private int contentPreviewWidth;
    private int contentPreviewHeight;
    private ScreenInputTarget previewTarget;
    private int pressedPreviewButton = -1;

    public ComputerScreen(BlockPos computerPos, ScreenGroup group) {
        super(Component.translatable("screen.minescreen.computer.title"));
        this.computerPos = computerPos.immutable();
        this.group = group;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(760, width - 12);
        panelHeight = Math.min(430, height - 12);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        addRenderableWidget(MineScreenButton.create(Component.literal("×"), button -> onClose(),
                panelLeft + 7, panelTop + 7, 20, 20));
        int tabLeft = panelLeft + 10;
        infoButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.computer.info"),
                button -> selectTab(HostTab.INFO), tabLeft, panelTop + 44, 120, 20));
        contentButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.computer.content"),
                button -> selectTab(HostTab.CONTENT), tabLeft, panelTop + 70, 120, 20));
        previewButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.computer.preview"),
                button -> selectTab(HostTab.PREVIEW), tabLeft, panelTop + 96, 120, 20));

        resolutionButton = addRenderableWidget(MineScreenButton.create(resolutionLabel(),
                button -> cycleResolution(), panelLeft + panelWidth - 190, panelTop + 42, 176, 20));
        volumeDownButton = addRenderableWidget(MineScreenButton.create(Component.literal("−"),
                button -> adjustVolume(-0.1F), panelLeft + panelWidth - 112,
                panelTop + 112, 42, 20));
        volumeUpButton = addRenderableWidget(MineScreenButton.create(Component.literal("+"),
                button -> adjustVolume(0.1F), panelLeft + panelWidth - 62,
                panelTop + 112, 42, 20));

        int browserTabWidth = Math.max(50, (panelWidth - 174 - (browserTabs.length - 1) * 3)
                / browserTabs.length);
        for (int i = 0; i < browserTabs.length; i++) {
            final int slot = i;
            BrowserTabButton browserTab = new BrowserTabButton(
                    panelLeft + 146 + i * (browserTabWidth + 3), panelTop + 42,
                    browserTabWidth, 20, () -> selectBrowserTab(slot),
                    () -> closeBrowserTab(slot));
            browserTabs[i] = addRenderableWidget(browserTab);
        }
        createContentControls();
        createTileButtons();
        previewLeft = panelLeft + 146;
        previewTop = panelTop + 68;
        previewWidth = Math.max(120, panelWidth - 294);
        previewHeight = Math.max(80, panelHeight - 98);
        updateVisibility();
    }

    /**
     * Content controls are real children of this ComputerScreen. They are not rendered by a child
     * Screen or a translated PoseStack, so panel chrome, labels, EditBox backgrounds/text/carets
     * and buttons all participate in the same GUI pass and coordinate layer.
     */
    private void createContentControls() {
        contentDraft = ScreenContentManager.profile(group.groupId()).copy();
        int left = panelLeft + 146;
        int available = panelWidth - 160;
        int formWidth = Math.min(286, Math.max(220, available / 2));
        int gap = 4;
        int modeWidth = (formWidth - gap * 3) / 4;
        ScreenContentType[] modes = ScreenContentType.values();
        for (int i = 0; i < modes.length; i++) {
            ScreenContentType mode = modes[i];
            contentModeButtons[i] = addRenderableWidget(MineScreenButton.create(
                    Component.translatable("screen.minescreen.mode."
                            + mode.name().toLowerCase(java.util.Locale.ROOT)),
                    button -> selectContentMode(mode), left + i * (modeWidth + gap),
                    panelTop + 70, modeWidth, 20));
        }

        contentSourceBox = new MineScreenEditBox(font, left, panelTop + 112, formWidth, 20,
                Component.translatable("screen.minescreen.source"));
        contentSourceBox.setMaxLength(4096);
        contentSourceBox.setValue(contentDraft.source == null ? "" : contentDraft.source);
        addRenderableWidget(contentSourceBox);

        contentPasswordBox = new MineScreenEditBox(font, left, panelTop + 158,
                Math.min(164, formWidth), 20,
                Component.translatable("screen.minescreen.vnc_password"));
        contentPasswordBox.setMaxLength(256);
        contentPasswordBox.setValue(VncCredentialStore.get(group.groupId()).password());
        addRenderableWidget(contentPasswordBox);

        contentMediaIdBox = new MineScreenEditBox(font, left, panelTop + 158,
                Math.min(164, formWidth), 20,
                Component.translatable("screen.minescreen.media_id"));
        contentMediaIdBox.setMaxLength(128);
        contentMediaIdBox.setValue(contentDraft.mediaId == null ? "" : contentDraft.mediaId);
        addRenderableWidget(contentMediaIdBox);

        contentChooseButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.choose_file"), button -> chooseContentVideo(),
                left + Math.max(0, formWidth - 108), panelTop + 158, 108, 20));
        contentBackButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.web.back"), button -> browserNavigate(-1),
                left, panelTop + 145, 70, 20));
        contentForwardButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.web.forward"), button -> browserNavigate(1),
                left + 76, panelTop + 145, 70, 20));
        contentReloadButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.web.reload"), button -> browserNavigate(0),
                left + 152, panelTop + 145, 70, 20));
        contentApplyButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.save"), button -> applyContentDraft(),
                left, panelTop + 190, 132, 20));
        contentPreviewLeft = left + formWidth + 14;
        contentPreviewTop = panelTop + 70;
        contentPreviewWidth = Math.max(96, available - formWidth - 14);
        contentPreviewHeight = Math.min(210, Math.max(96, panelHeight - 150));
        updateContentControlVisibility();
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft.level == null) {
            onClose();
            return;
        }
        ScreenGroup linked = ScreenLinkResolver.findGroup(minecraft.level, computerPos);
        if (linked != null) {
            group = linked;
        }
        updateBrowserTabs();
        updateContentControlVisibility();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        MineScreenUiRegistry.render(this, graphics, mouseX, mouseY, partialTick,
                () -> renderMineScreenLayer(graphics, mouseX, mouseY, partialTick));
    }

    private void renderMineScreenLayer(GuiGraphics graphics, int mouseX, int mouseY,
            float partialTick) {
        // Host chrome, preview, labels and widgets all render on the same default GUI layer.
        graphics.fillGradient(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight,
                0xFF151B24, 0xFF080C12);
        graphics.fill(panelLeft, panelTop + 33, panelLeft + panelWidth, panelTop + 35, 0xFFFFD43B);
        graphics.drawString(font, title, panelLeft + 34, panelTop + 13, 0xFFF3F7FC, false);
        ScreenContentManager.requestHostKeepAlive(group.groupId());
        if (tab == HostTab.PREVIEW) {
            graphics.fill(previewLeft - 2, previewTop - 2, previewLeft + previewWidth + 2,
                    previewTop + previewHeight + 2, 0xFF455267);
            ScreenPreviewRenderer.draw(graphics, group, previewLeft, previewTop, previewWidth, previewHeight);
            ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
            graphics.drawString(font, Component.translatable("screen.minescreen.computer.volume",
                    Math.round(profile.volume * 100.0F)), panelLeft + panelWidth - 132,
                    panelTop + 92, 0xFFD9E4F0, false);
            graphics.drawWordWrap(font, Component.translatable("screen.minescreen.computer.mouse_help"),
                    panelLeft + panelWidth - 132, panelTop + 145, 118, 0xFF96A9BF);
        } else if (tab == HostTab.INFO) {
            int[] size = ScreenResolution.dimensions(group, ScreenContentManager.profile(group.groupId()));
            graphics.drawString(font, Component.translatable("screen.minescreen.group_summary",
                    group.columns(), group.rows(), group.groupId().toString().substring(0, 8)),
                    panelLeft + 146, panelTop + 46, 0xFFDCE6F2, false);
            graphics.drawString(font, Component.translatable("screen.minescreen.computer.actual_resolution",
                    size[0], size[1]), panelLeft + 146, panelTop + 58, 0xFF96A9BF, false);
            graphics.drawString(font, Component.translatable("screen.minescreen.computer.tile_help"),
                    panelLeft + 146, panelTop + 86, 0xFF96A9BF, false);
        } else {
            graphics.fill(contentPreviewLeft - 2, contentPreviewTop - 2,
                    contentPreviewLeft + contentPreviewWidth + 2,
                    contentPreviewTop + contentPreviewHeight + 2, 0xFF455267);
            // The media image is part of the CONTENT page itself, not a different Screen/layer.
            ScreenPreviewRenderer.draw(graphics, group, contentPreviewLeft, contentPreviewTop,
                    contentPreviewWidth, contentPreviewHeight);
            if (contentSourceBox.visible) {
                graphics.drawString(font, contentSourceLabel(), panelLeft + 146,
                        panelTop + 101, 0xFFB9C8D8, false);
            }
            if (contentPasswordBox.visible) {
                graphics.drawString(font, Component.translatable("screen.minescreen.vnc_password"),
                        panelLeft + 146, panelTop + 147, 0xFFB9C8D8, false);
            }
            if (contentMediaIdBox.visible) {
                graphics.drawString(font, Component.translatable("screen.minescreen.media_id"),
                        panelLeft + 146, panelTop + 147, 0xFFB9C8D8, false);
            }
            if (!contentStatus.getString().isBlank()) {
                graphics.drawString(font, contentStatus, panelLeft + 286,
                        panelTop + 196, contentStatusColor, false);
            }
            graphics.drawWordWrap(font, Component.translatable("screen.minescreen.computer.content_help"),
                    panelLeft + 146, panelTop + 224,
                    Math.max(200, contentPreviewLeft - panelLeft - 160), 0xFF96A9BF);
        }
        renderWidgetsDirect(graphics, mouseX, mouseY, partialTick);
    }

    /** Bypasses UI-overhaul mixins on Screen.render and flattens every child in this panel pass. */
    private void renderWidgetsDirect(GuiGraphics graphics, int mouseX, int mouseY,
            float partialTick) {
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children()) {
            if (child instanceof net.minecraft.client.gui.components.Renderable renderable) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.flush();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab == HostTab.PREVIEW && insidePreview(mouseX, mouseY)) {
            ScreenInputTarget target = previewTarget();
            if (target != null) {
                int x = previewX(target, mouseX);
                int y = previewY(target, mouseY);
                target.mouseMove(x, y);
                target.mousePress(x, y, button);
                previewTarget = target;
                pressedPreviewButton = button;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (previewTarget != null && pressedPreviewButton == button) {
            previewTarget.mouseRelease(previewX(previewTarget, mouseX),
                    previewY(previewTarget, mouseY), button);
            previewTarget = null;
            pressedPreviewButton = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (tab == HostTab.PREVIEW && insidePreview(mouseX, mouseY)) {
            ScreenInputTarget target = previewTarget();
            if (target != null) {
                target.mouseWheel(previewX(target, mouseX), previewY(target, mouseY), deltaY, 0);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (tab == HostTab.CONTENT && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                && contentSourceBox != null && contentSourceBox.isFocused()) {
            applyContentDraft();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        if (previewTarget != null && pressedPreviewButton >= 0) {
            previewTarget.mouseRelease(0, 0, pressedPreviewButton);
        }
        super.removed();
    }

    private void selectTab(HostTab next) {
        tab = next;
        updateVisibility();
    }

    private void updateVisibility() {
        if (infoButton == null) {
            return;
        }
        infoButton.active = tab != HostTab.INFO;
        contentButton.active = tab != HostTab.CONTENT;
        previewButton.active = tab != HostTab.PREVIEW;
        resolutionButton.visible = resolutionButton.active = tab == HostTab.INFO;
        volumeDownButton.visible = volumeDownButton.active = tab == HostTab.PREVIEW;
        volumeUpButton.visible = volumeUpButton.active = tab == HostTab.PREVIEW;
        tileButtons.keySet().forEach(button -> button.visible = button.active = tab == HostTab.INFO);
        updateContentControlVisibility();
        updateBrowserTabs();
    }

    private void selectContentMode(ScreenContentType mode) {
        contentDraft.contentType = mode;
        contentStatus = Component.translatable("screen.minescreen.status.mode_changed");
        contentStatusColor = 0xFF8FCBFF;
        updateContentControlVisibility();
    }

    private void updateContentControlVisibility() {
        if (contentSourceBox == null || contentDraft == null) {
            return;
        }
        boolean content = tab == HostTab.CONTENT;
        boolean idle = contentDraft.contentType == ScreenContentType.IDLE;
        boolean video = contentDraft.contentType == ScreenContentType.VIDEO;
        boolean web = contentDraft.contentType == ScreenContentType.WEB;
        boolean vnc = contentDraft.contentType == ScreenContentType.VNC;
        for (int i = 0; i < contentModeButtons.length; i++) {
            Button button = contentModeButtons[i];
            button.visible = content;
            button.active = content && ScreenContentType.values()[i] != contentDraft.contentType;
        }
        contentSourceBox.visible = content && !idle;
        contentSourceBox.setEditable(content && !idle);
        contentPasswordBox.visible = content && vnc;
        contentPasswordBox.setEditable(content && vnc);
        contentMediaIdBox.visible = content && video;
        contentMediaIdBox.setEditable(content && video);
        contentChooseButton.visible = contentChooseButton.active = content && video;
        contentBackButton.visible = content && web;
        contentForwardButton.visible = content && web;
        contentReloadButton.visible = contentReloadButton.active = content && web;
        contentApplyButton.visible = contentApplyButton.active = content;
        BrowserSession browser = browserSession();
        contentBackButton.active = content && web && browser != null && browser.canGoBack();
        contentForwardButton.active = content && web && browser != null && browser.canGoForward();
    }

    private Component contentSourceLabel() {
        return Component.translatable(switch (contentDraft.contentType) {
            case VIDEO -> "screen.minescreen.label.video_source";
            case WEB -> "screen.minescreen.label.web_source";
            case VNC -> "screen.minescreen.label.vnc_source";
            case IDLE -> "screen.minescreen.source";
        });
    }

    private void chooseContentVideo() {
        if (!ClientSecurityPolicy.localFilesAllowed()) {
            contentError("screen.minescreen.error.local_files_blocked");
            return;
        }
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.mp4")).flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("screen.minescreen.choose_file").getString(),
                    contentSourceBox.getValue(), filters, "MP4 video", false);
        }
        if (selected != null && !selected.isBlank()) {
            contentSourceBox.setValue(Path.of(selected).toAbsolutePath().normalize().toString());
            contentStatus = Component.translatable("screen.minescreen.status.file_selected");
            contentStatusColor = 0xFF91E5A4;
        }
    }

    private void browserNavigate(int operation) {
        BrowserSession browser = browserSession();
        if (browser == null) {
            contentError("screen.minescreen.error.browser_not_running");
            return;
        }
        if (operation < 0) {
            browser.goBack();
        } else if (operation > 0) {
            browser.goForward();
        } else {
            browser.reload();
        }
    }

    private void applyContentDraft() {
        String source = contentSourceBox.getValue().trim();
        switch (contentDraft.contentType) {
            case IDLE -> source = "";
            case VIDEO -> {
                if (!ClientSecurityPolicy.localFilesAllowed()) {
                    contentError("screen.minescreen.error.local_files_blocked");
                    return;
                }
                try {
                    Path path = Path.of(source);
                    if (!source.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")
                            || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                        contentError("screen.minescreen.error.video_file");
                        return;
                    }
                } catch (RuntimeException invalidPath) {
                    contentError("screen.minescreen.error.video_file");
                    return;
                }
                String mediaId = contentMediaIdBox.getValue().trim();
                if (!mediaId.isEmpty() && !mediaId.matches("[A-Za-z0-9._-]{1,128}")) {
                    contentError("screen.minescreen.error.media_id");
                    return;
                }
                contentDraft.mediaId = mediaId;
            }
            case WEB -> {
                source = normalizeWebUrl(source);
                contentSourceBox.setValue(source);
                try {
                    URI uri = URI.create(source);
                    if (uri.getScheme() == null || uri.getHost() == null) {
                        contentError("screen.minescreen.error.web_format");
                        return;
                    }
                } catch (RuntimeException invalidUri) {
                    contentError("screen.minescreen.error.web_format");
                    return;
                }
                if (!BrowserRequestPolicy.isAllowed(source)) {
                    contentError("screen.minescreen.error.web_blocked");
                    return;
                }
            }
            case VNC -> {
                try {
                    RfbEndpoint endpoint = RfbEndpoint.parse(source);
                    if (!BrowserRequestPolicy.isAllowed(endpoint.policyUrl())) {
                        contentError("screen.minescreen.error.vnc_blocked");
                        return;
                    }
                } catch (RuntimeException invalidEndpoint) {
                    contentError("screen.minescreen.error.vnc_format");
                    return;
                }
                VncCredentialStore.put(group.groupId(),
                        new VncCredentialStore.Credential("", contentPasswordBox.getValue()));
            }
        }
        contentDraft.source = source;
        ScreenContentManager.updateProfile(group.groupId(), contentDraft);
        ScreenContentManager.sourceFor(group);
        contentStatus = Component.translatable(contentDraft.contentType == ScreenContentType.WEB
                ? "screen.minescreen.status.web_loaded" : "screen.minescreen.save");
        contentStatusColor = 0xFF91E5A4;
        updateBrowserTabs();
        updateContentControlVisibility();
    }

    private void contentError(String key) {
        contentStatus = Component.translatable(key);
        contentStatusColor = 0xFFFF7777;
    }

    private static String normalizeWebUrl(String value) {
        String trimmed = value.trim();
        return trimmed.contains("://") ? trimmed : "https://" + trimmed;
    }

    private void createTileButtons() {
        List<BlockPos> tiles = new ArrayList<>(group.tiles());
        net.minecraft.core.Direction right = dev.minescreen.ScreenGeometry
                .rightDirection(group.facing());
        int originHorizontal = horizontalCoordinate(group.origin(), right);
        tiles.sort(Comparator
                .comparingInt((BlockPos pos) -> group.rows() - 1
                        - (pos.getY() - group.origin().getY()))
                .thenComparingInt(pos -> horizontalCoordinate(pos, right) - originHorizontal));
        int cellWidth = Math.max(18, Math.min(34,
                (panelWidth - 160) / Math.max(1, Math.min(16, group.columns()))));
        for (int i = 0; i < Math.min(tiles.size(), 64); i++) {
            BlockPos tile = tiles.get(i);
            int column = horizontalCoordinate(tile, right) - originHorizontal;
            int logicalRow = tile.getY() - group.origin().getY();
            int visualRow = group.rows() - 1 - logicalRow;
            Button button = addRenderableWidget(MineScreenButton.create(tileLabel(tile),
                    clicked -> toggleTile(clicked, tile), panelLeft + 146 + column * cellWidth,
                    panelTop + 102 + visualRow * 24, Math.max(14, cellWidth - 4), 20));
            tileButtons.put(button, tile);
        }
    }

    private static int horizontalCoordinate(BlockPos pos, net.minecraft.core.Direction right) {
        return pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
    }

    private void toggleTile(Button button, BlockPos tile) {
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
        boolean enable = profile.disabledTiles.contains(tile.asLong());
        ScreenContentManager.setTileEnabled(group.groupId(), tile, enable);
        button.setMessage(tileLabel(tile));
    }

    private Component tileLabel(BlockPos tile) {
        boolean enabled = !ScreenContentManager.profile(group.groupId()).disabledTiles.contains(tile.asLong());
        return Component.literal(enabled ? "■" : "×");
    }

    private void cycleResolution() {
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId()).copy();
        int current = ScreenResolution.percent(profile);
        int next = RESOLUTIONS[0];
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (RESOLUTIONS[i] == current) {
                next = RESOLUTIONS[(i + 1) % RESOLUTIONS.length];
                break;
            }
        }
        profile.resolutionPercent = next;
        ScreenContentManager.updateProfile(group.groupId(), profile);
        resolutionButton.setMessage(resolutionLabel());
    }

    private Component resolutionLabel() {
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        return Component.translatable("screen.minescreen.resolution", ScreenResolution.percent(profile),
                dimensions[0], dimensions[1]);
    }

    private void adjustVolume(float delta) {
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId()).copy();
        profile.volume = Math.max(0.0F, Math.min(1.0F, profile.volume + delta));
        ScreenContentManager.updateProfile(group.groupId(), profile);
    }

    private void updateBrowserTabs() {
        java.util.Arrays.fill(displayedTabs, -1);
        BrowserSession browser = browserSession();
        boolean visible = tab == HostTab.CONTENT && browser != null;
        List<BrowserSession.TabInfo> tabs = browser == null ? List.of() : browser.tabs();
        int start = browser == null ? 0 : Math.max(0, Math.min(browser.activeTabIndex(),
                Math.max(0, tabs.size() - browserTabs.length)));
        for (int slot = 0; slot < browserTabs.length; slot++) {
            BrowserTabButton button = browserTabs[slot];
            int index = start + slot;
            if (!visible || index >= tabs.size()) {
                button.visible = false;
                continue;
            }
            BrowserSession.TabInfo info = tabs.get(index);
            displayedTabs[slot] = index;
            button.visible = true;
            button.active = true;
            button.setSelected(info.active());
            button.setMessage(Component.literal(elide(info.title(), 18)));
        }
    }

    private void selectBrowserTab(int slot) {
        BrowserSession browser = browserSession();
        int index = displayedTabs[slot];
        if (browser == null || index < 0) {
            return;
        }
        if (Screen.hasShiftDown()) {
            browser.closeTab(index);
            ClientScreenProfile profile = ScreenContentManager.profile(group.groupId()).copy();
            profile.webTabs = browser.tabs().stream().skip(1).map(BrowserSession.TabInfo::url)
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            ScreenContentManager.saveLocalProfile(group.groupId(), profile);
        } else {
            browser.activateTab(index);
        }
        updateBrowserTabs();
    }

    private void closeBrowserTab(int slot) {
        BrowserSession browser = browserSession();
        int index = displayedTabs[slot];
        if (browser == null || index < 0) {
            return;
        }
        browser.closeTab(index);
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId()).copy();
        profile.webTabs = browser.tabs().stream().skip(1).map(BrowserSession.TabInfo::url)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        ScreenContentManager.saveLocalProfile(group.groupId(), profile);
        updateBrowserTabs();
    }

    private BrowserSession browserSession() {
        if (ScreenContentManager.profile(group.groupId()).contentType != ScreenContentType.WEB) {
            return null;
        }
        ScreenContentManager.sourceFor(group);
        return ScreenContentManager.session(group.groupId()) instanceof BrowserSession browser
                ? browser : null;
    }

    private ScreenInputTarget previewTarget() {
        ScreenContentManager.sourceFor(group);
        return ScreenContentManager.session(group.groupId()) instanceof ScreenInputTarget target
                ? target : null;
    }

    private boolean insidePreview(double mouseX, double mouseY) {
        return mouseX >= previewLeft && mouseX < previewLeft + previewWidth
                && mouseY >= previewTop && mouseY < previewTop + previewHeight;
    }

    private int previewX(ScreenInputTarget target, double mouseX) {
        return clamp((int) ((mouseX - previewLeft) / previewWidth * target.inputWidth()), target.inputWidth());
    }

    private int previewY(ScreenInputTarget target, double mouseY) {
        return clamp((int) ((mouseY - previewTop) / previewHeight * target.inputHeight()), target.inputHeight());
    }

    private static int clamp(int value, int extent) {
        return Math.max(0, Math.min(Math.max(0, extent - 1), value));
    }

    private static String elide(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private enum HostTab {
        INFO,
        CONTENT,
        PREVIEW
    }
}
