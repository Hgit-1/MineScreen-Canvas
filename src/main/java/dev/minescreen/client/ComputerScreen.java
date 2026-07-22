package dev.minescreen.client;

import java.net.URI;
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
import dev.minescreen.MineScreenConfig;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenResolution;
import dev.minescreen.client.content.WebSplitLayout;
import dev.minescreen.client.content.ScreenRegionLayout;
import dev.minescreen.client.content.HostSurfaceLayout;
import dev.minescreen.client.vnc.RfbEndpoint;
import dev.minescreen.client.vnc.VncCredentialStore;
import dev.minescreen.client.vnc.VncRefreshRate;
import dev.minescreen.client.web.BrowserRequestPolicy;
import dev.minescreen.client.video.VideoSource;
import dev.minescreen.client.web.BrowserSession;
import dev.minescreen.client.ui.MineScreenUiRegistry;
import dev.minescreen.client.ui.CustomUiArtwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Enlarged host console with info, content, and live mouse-enabled preview tabs. */
public final class ComputerScreen extends Screen {
    private static final int[] RESOLUTIONS = {100, 75, 50, 33, 25};
    private static final int MAX_TILE_PAGE_COLUMNS = 16;
    private static final int MAX_TILE_PAGE_ROWS = 8;
    private final BlockPos computerPos;
    private ScreenGroup group;
    private ScreenHostNetworkManager.HostNetwork hostNetwork;
    private HostTab tab = HostTab.PREVIEW;
    private final Map<Button, BlockPos> tileButtons = new HashMap<>();
    private int tilePage;
    private Button tilePagePreviousButton;
    private Button tilePageLabelButton;
    private Button tilePageNextButton;
    private final BrowserTabButton[] browserTabs = new BrowserTabButton[6];
    private final int[] displayedTabs = {-1, -1, -1, -1, -1, -1};
    private Button infoButton;
    private Button contentButton;
    private Button previewButton;
    private Button surfaceButton;
    private Button surfaceLayoutButton;
    private Button surfaceOrderPreviousButton;
    private Button surfaceOrderNextButton;
    private Button surfaceMoveLeftButton;
    private Button surfaceMoveUpButton;
    private Button surfaceMoveDownButton;
    private Button surfaceMoveRightButton;
    private Button surfaceRotateButton;
    private Button resolutionButton;
    private Button volumeDownButton;
    private Button volumeUpButton;
    private Button regionSelectButton;
    private Button regionEditButton;
    private final Button[] contentModeButtons = new Button[ScreenContentType.values().length];
    private EditBox contentSourceBox;
    private EditBox contentPasswordBox;
    private EditBox contentMediaIdBox;
    private Button contentApplyButton;
    private Button contentChooseButton;
    private Button contentBackButton;
    private Button contentForwardButton;
    private Button contentReloadButton;
    private Button contentSplitButton;
    private Button contentVncFpsButton;
    private Button contentVncFpsDownButton;
    private Button contentVncFpsUpButton;
    private Button contentVideoPlayButton;
    private Button contentVideoLoopButton;
    private ClientScreenProfile contentDraft;
    private java.util.UUID contentDraftGroupId;
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
    private int previewPointerX;
    private int previewPointerY;
    private int pressedPreviewButton = -1;
    private ScreenInputTarget previewKeyboardTarget;
    private int previewKeyboardRegionId = -1;
    private java.util.UUID previewKeyboardGroupId;
    private final java.util.Map<Integer, PressedPreviewKey> pressedPreviewKeys =
            new java.util.HashMap<>();
    private int selectedRegion;
    private java.util.UUID selectedSurfaceId;
    private int observedContentWebTab = -1;
    private String pendingContentWebAddress;
    private long pendingContentWebAddressUntilNanos;

    public ComputerScreen(BlockPos computerPos, ScreenGroup group) {
        super(Component.translatable("screen.minescreen.computer.title"));
        this.computerPos = computerPos.immutable();
        this.group = group;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        captureContentDraft();
        resolveHostNetwork();
        tileButtons.clear();
        tilePagePreviousButton = null;
        tilePageLabelButton = null;
        tilePageNextButton = null;
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
        surfaceButton = addRenderableWidget(MineScreenButton.create(surfaceLabel(),
                button -> cycleSurface(), tabLeft, panelTop + 128, 120, 20));
        surfaceLayoutButton = addRenderableWidget(MineScreenButton.create(surfaceLayoutLabel(),
                button -> cycleSurfaceLayout(), tabLeft, panelTop + 154, 120, 20));
        surfaceOrderPreviousButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.computer.order_previous"),
                button -> moveSurfaceOrder(-1),
                tabLeft, panelTop + 180, 58, 20));
        surfaceOrderNextButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.computer.order_next"),
                button -> moveSurfaceOrder(1),
                tabLeft + 62, panelTop + 180, 58, 20));
        surfaceMoveLeftButton = addRenderableWidget(MineScreenButton.create(
                Component.literal("←"), button -> moveSurface(-1, 0),
                tabLeft, panelTop + 206, 27, 20));
        surfaceMoveUpButton = addRenderableWidget(MineScreenButton.create(
                Component.literal("↑"), button -> moveSurface(0, -1),
                tabLeft + 31, panelTop + 206, 27, 20));
        surfaceMoveDownButton = addRenderableWidget(MineScreenButton.create(
                Component.literal("↓"), button -> moveSurface(0, 1),
                tabLeft + 62, panelTop + 206, 27, 20));
        surfaceMoveRightButton = addRenderableWidget(MineScreenButton.create(
                Component.literal("→"), button -> moveSurface(1, 0),
                tabLeft + 93, panelTop + 206, 27, 20));
        surfaceRotateButton = addRenderableWidget(MineScreenButton.create(surfaceRotationLabel(),
                button -> rotateSurface(), tabLeft, panelTop + 232, 120, 20));

        resolutionButton = addRenderableWidget(MineScreenButton.create(resolutionLabel(),
                button -> cycleResolution(), panelLeft + panelWidth - 190, panelTop + 42, 176, 20));
        regionSelectButton = addRenderableWidget(MineScreenButton.create(regionSelectLabel(),
                button -> cycleSelectedRegion(), panelLeft + panelWidth - 190,
                panelTop + 68, 86, 20));
        regionEditButton = addRenderableWidget(MineScreenButton.create(
                regionActionLabel(),
                button -> editSelectedRegion(), panelLeft + panelWidth - 98,
                panelTop + 68, 84, 20));
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
        if (contentDraft == null || !group.groupId().equals(contentDraftGroupId)) {
            contentDraft = ScreenContentManager.profile(group.groupId()).copy();
            contentDraft.normalizeSources();
            contentDraftGroupId = group.groupId();
        }
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
        contentSourceBox.setMaxLength(VideoSource.MAX_INPUT_LENGTH);
        contentSourceBox.setValue(contentDraft.sourceFor(contentDraft.contentType));
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
        contentSplitButton = addRenderableWidget(MineScreenButton.create(contentSplitLabel(),
                button -> cycleContentSplit(), left, panelTop + 171, formWidth, 20));
        int vncFpsTop = panelTop + 184;
        int vncFpsStepWidth = 28;
        int vncFpsGap = 4;
        int vncFpsValueWidth = formWidth - vncFpsStepWidth * 2 - vncFpsGap * 2;
        contentVncFpsDownButton = addRenderableWidget(MineScreenButton.create(Component.literal("−"),
                button -> adjustContentVncFps(-1), left, vncFpsTop, vncFpsStepWidth, 20));
        contentVncFpsButton = addRenderableWidget(MineScreenButton.create(contentVncFpsLabel(),
                button -> resetContentVncFps(), left + vncFpsStepWidth + vncFpsGap, vncFpsTop,
                vncFpsValueWidth, 20));
        contentVncFpsUpButton = addRenderableWidget(MineScreenButton.create(Component.literal("+"),
                button -> adjustContentVncFps(1), left + formWidth - vncFpsStepWidth,
                vncFpsTop, vncFpsStepWidth, 20));
        int videoControlGap = 4;
        int videoControlWidth = (formWidth - videoControlGap) / 2;
        contentVideoPlayButton = addRenderableWidget(MineScreenButton.create(
                contentVideoPlayLabel(), button -> toggleContentVideoPaused(), left,
                vncFpsTop, videoControlWidth, 20));
        contentVideoLoopButton = addRenderableWidget(MineScreenButton.create(
                contentVideoLoopLabel(), button -> toggleContentVideoLoop(),
                left + videoControlWidth + videoControlGap, vncFpsTop,
                formWidth - videoControlWidth - videoControlGap, 20));
        contentApplyButton = addRenderableWidget(MineScreenButton.create(
                contentApplyLabel(), button -> applyContentDraft(),
                left, panelTop + 210, 132, 20));
        contentPreviewLeft = left + formWidth + 14;
        contentPreviewTop = panelTop + 70;
        contentPreviewWidth = Math.max(96, available - formWidth - 14);
        contentPreviewHeight = Math.min(210, Math.max(96, panelHeight - 150));
        updateContentControlVisibility();
    }

    /**
     * Native file dialogs and host topology changes can make Minecraft rebuild this Screen. Keep
     * the unsaved mode/path draft instead of silently restoring the persisted IDLE state.
     */
    private void captureContentDraft() {
        if (contentDraft == null || contentDraftGroupId == null || group == null
                || !contentDraftGroupId.equals(group.groupId())) {
            return;
        }
        if (contentSourceBox != null) {
            contentDraft.setSourceFor(contentDraft.contentType, contentSourceBox.getValue());
        }
        if (contentMediaIdBox != null) {
            contentDraft.mediaId = contentMediaIdBox.getValue();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft.level == null) {
            onClose();
            return;
        }
        ScreenHostNetworkManager.HostNetwork previous = hostNetwork;
        resolveHostNetwork();
        if (hostNetwork == null) {
            onClose();
            return;
        }
        if (previous == null || previous.signature() != hostNetwork.signature()) {
            rebuildWidgets();
            return;
        }
        updateBrowserTabs();
        updateContentControlVisibility();
        if (previewKeyboardTarget != null) {
            ScreenContentSession current = previewKeyboardGroupId == null ? null
                    : ScreenContentManager.session(previewKeyboardGroupId,
                    previewKeyboardRegionId);
            if (tab != HostTab.PREVIEW || current != previewKeyboardTarget) {
                releasePreviewKeyboard();
            }
        }
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
        CustomUiArtwork.drawPanel(graphics, panelLeft, panelTop, panelWidth, panelHeight);
        graphics.drawString(font, title, panelLeft + 34, panelTop + 13, 0xFFF3F7FC, false);
        ScreenGroup powerGroup = hostNetwork == null ? group : hostNetwork.rootGroup();
        if (powerGroup != null && !ScreenPowerManager.isPowered(powerGroup)) {
            Component warning = Component.translatable("screen.minescreen.power.off");
            int right = panelLeft + panelWidth - 14;
            graphics.drawString(font, warning, right - font.width(warning), panelTop + 13,
                    0xFFFF6B6B, false);
        }
        if (hostNetwork != null && hostNetwork.groups().size() > 1) {
                    graphics.drawWordWrap(font, Component.translatable(
                            "screen.minescreen.computer.surface_adjust_help"),
                    panelLeft + 10, panelTop + 260, 120, 0xFF96A9BF);
        }
        keepHostNetworkAlive();
        if (tab == HostTab.PREVIEW) {
            graphics.fill(previewLeft - 2, previewTop - 2, previewLeft + previewWidth + 2,
                    previewTop + previewHeight + 2, 0xFF455267);
            drawHostPreview(graphics, previewLeft, previewTop, previewWidth, previewHeight);
            ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
            graphics.drawString(font, Component.translatable("screen.minescreen.computer.volume",
                    Math.round(profile.volume * 100.0F)), panelLeft + panelWidth - 132,
                    panelTop + 92, 0xFFD9E4F0, false);
            graphics.drawWordWrap(font, Component.translatable("screen.minescreen.computer.mouse_help"),
                    panelLeft + panelWidth - 132, panelTop + 145, 118, 0xFF96A9BF);
            graphics.drawWordWrap(font, Component.translatable(previewKeyboardTarget == null
                            ? "screen.minescreen.computer.keyboard_idle"
                            : "screen.minescreen.computer.keyboard_active"),
                    panelLeft + panelWidth - 132, panelTop + 194, 118,
                    previewKeyboardTarget == null ? 0xFF96A9BF : 0xFFFFD43B);
        } else if (tab == HostTab.INFO) {
            ScreenGroup displayGroup = hostNetwork != null && hostNetwork.panoramic()
                    ? hostNetwork.canvas() : group;
            java.util.UUID profileId = hostNetwork != null && hostNetwork.panoramic()
                    ? hostNetwork.rootGroupId() : group.groupId();
            int[] size = ScreenResolution.dimensions(displayGroup,
                    ScreenContentManager.profile(profileId));
            graphics.drawString(font, Component.translatable("screen.minescreen.group_summary",
                    displayGroup.columns(), displayGroup.rows(),
                    profileId.toString().substring(0, 8)),
                    panelLeft + 146, panelTop + 46, 0xFFDCE6F2, false);
            if (hostNetwork != null) {
                graphics.drawString(font, Component.translatable(
                        "screen.minescreen.computer.surface_count", hostNetwork.groups().size()),
                        panelLeft + 146, panelTop + 70, 0xFF96A9BF, false);
                ScreenGroup selected = selectedSurface();
                ScreenHostNetworkManager.Surface surface = hostNetwork.surface(selected.groupId());
                graphics.drawString(font, Component.translatable(
                                "screen.minescreen.computer.surface_position",
                                selected.columns(), selected.rows(),
                                surface == null ? 0 : surface.column(),
                                surface == null ? 0 : surface.row()),
                        panelLeft + 146, panelTop + 82, 0xFFFFD43B, false);
            }
            graphics.drawString(font, Component.translatable("screen.minescreen.computer.actual_resolution",
                    size[0], size[1]), panelLeft + 146, panelTop + 58, 0xFF96A9BF, false);
            graphics.drawString(font, Component.translatable(hostNetwork != null
                            && hostNetwork.panoramic()
                            ? "screen.minescreen.computer.joined_split_help"
                            : "screen.minescreen.computer.tile_help"),
                    panelLeft + 146, panelTop + 100, 0xFF96A9BF, false);
        } else {
            graphics.fill(contentPreviewLeft - 2, contentPreviewTop - 2,
                    contentPreviewLeft + contentPreviewWidth + 2,
                    contentPreviewTop + contentPreviewHeight + 2, 0xFF455267);
            // The media image is part of the CONTENT page itself, not a different Screen/layer.
            drawContentPreview(graphics, contentPreviewLeft, contentPreviewTop,
                    contentPreviewWidth, contentPreviewHeight);
            Component backendStatus = contentBackendStatus();
            if (backendStatus != null) {
                graphics.fill(contentPreviewLeft + 4, contentPreviewTop + 4,
                        contentPreviewLeft + contentPreviewWidth - 4,
                        contentPreviewTop + 24, 0xCC111823);
                graphics.drawString(font, backendStatus, contentPreviewLeft + 8,
                        contentPreviewTop + 10,
                        ScreenContentManager.error(group.groupId()) == null
                                ? 0xFFFFD43B : 0xFFFF7777, false);
            }
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
                        panelTop + 211, contentStatusColor, false);
            }
            graphics.drawWordWrap(font, Component.translatable("screen.minescreen.computer.content_help"),
                    panelLeft + 146, panelTop + 242,
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
            PreviewPointer pointer = previewPointer(mouseX, mouseY);
            if (pointer != null) {
                focusPreviewKeyboard(pointer.target(), pointer.groupId(), pointer.regionId());
                pointer.target().mouseMove(pointer.x(), pointer.y());
                pointer.target().mousePress(pointer.x(), pointer.y(), button);
                previewTarget = pointer.target();
                previewPointerX = pointer.x();
                previewPointerY = pointer.y();
                pressedPreviewButton = button;
                return true;
            }
        } else if (previewKeyboardTarget != null) {
            releasePreviewKeyboard();
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (previewTarget != null && pressedPreviewButton == button) {
            PreviewPointer pointer = previewPointer(mouseX, mouseY);
            int x = pointer != null && pointer.target() == previewTarget
                    ? pointer.x() : previewPointerX;
            int y = pointer != null && pointer.target() == previewTarget
                    ? pointer.y() : previewPointerY;
            previewTarget.mouseRelease(x, y, button);
            previewTarget = null;
            pressedPreviewButton = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (tab == HostTab.PREVIEW && insidePreview(mouseX, mouseY)) {
            PreviewPointer pointer = previewPointer(mouseX, mouseY);
            if (pointer != null) {
                pointer.target().mouseWheel(pointer.x(), pointer.y(), deltaY, 0);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (tab == HostTab.PREVIEW && previewKeyboardTarget != null) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                releasePreviewKeyboard();
                return true;
            }
            previewKeyboardTarget.keyPress(keyCode, scanCode, modifiers);
            pressedPreviewKeys.putIfAbsent(keyCode,
                    new PressedPreviewKey(scanCode, modifiers));
            return true;
        }
        if (tab == HostTab.CONTENT && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                && contentSourceBox != null && contentSourceBox.isFocused()) {
            applyContentDraft();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (previewKeyboardTarget != null) {
            previewKeyboardTarget.keyRelease(keyCode, scanCode, modifiers);
            pressedPreviewKeys.remove(keyCode);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (tab == HostTab.PREVIEW && previewKeyboardTarget != null) {
            previewKeyboardTarget.keyTyped(character, modifiers);
            return true;
        }
        return super.charTyped(character, modifiers);
    }

    /** Called by the KeyboardHandler HEAD mixin so game/mod hotkeys never see host input. */
    boolean interceptBuiltInKeyboardKey(int keyCode, int scanCode, int action, int modifiers) {
        if (tab != HostTab.PREVIEW || previewKeyboardTarget == null) {
            return false;
        }
        if (action == org.lwjgl.glfw.GLFW.GLFW_RELEASE) {
            return keyReleased(keyCode, scanCode, modifiers);
        }
        if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || action == org.lwjgl.glfw.GLFW.GLFW_REPEAT) {
            return keyPressed(keyCode, scanCode, modifiers);
        }
        return true;
    }

    /** Unicode companion to {@link #interceptBuiltInKeyboardKey}; handles surrogate pairs. */
    boolean interceptBuiltInKeyboardChar(int codePoint, int modifiers) {
        if (tab != HostTab.PREVIEW || previewKeyboardTarget == null
                || !Character.isValidCodePoint(codePoint)) {
            return false;
        }
        for (char character : Character.toChars(codePoint)) {
            charTyped(character, modifiers);
        }
        return true;
    }

    @Override
    public void removed() {
        if (previewTarget != null && pressedPreviewButton >= 0) {
            previewTarget.mouseRelease(0, 0, pressedPreviewButton);
        }
        releasePreviewKeyboard();
        super.removed();
    }

    private void selectTab(HostTab next) {
        if (next != HostTab.PREVIEW) {
            releasePreviewKeyboard();
        }
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
        boolean multiSurface = hostNetwork != null && hostNetwork.groups().size() > 1;
        surfaceButton.visible = true;
        surfaceButton.active = multiSurface;
        surfaceLayoutButton.visible = true;
        surfaceLayoutButton.active = multiSurface;
        boolean joined = multiSurface && hostNetwork.layout() != HostSurfaceLayout.FREE;
        boolean automatic = joined && hostNetwork.layout() != HostSurfaceLayout.CUSTOM;
        surfaceOrderPreviousButton.visible = surfaceOrderNextButton.visible = multiSurface;
        surfaceOrderPreviousButton.active = surfaceOrderNextButton.active = automatic;
        surfaceMoveLeftButton.visible = surfaceMoveUpButton.visible = multiSurface;
        surfaceMoveDownButton.visible = surfaceMoveRightButton.visible = multiSurface;
        surfaceMoveLeftButton.active = surfaceMoveUpButton.active = joined;
        surfaceMoveDownButton.active = surfaceMoveRightButton.active = joined;
        surfaceRotateButton.visible = surfaceRotateButton.active = hostNetwork != null;
        resolutionButton.visible = resolutionButton.active = tab == HostTab.INFO;
        regionSelectButton.visible = regionSelectButton.active = tab == HostTab.INFO && !joined;
        regionEditButton.visible = regionEditButton.active = tab == HostTab.INFO;
        volumeDownButton.visible = volumeDownButton.active = tab == HostTab.PREVIEW;
        volumeUpButton.visible = volumeUpButton.active = tab == HostTab.PREVIEW;
        tileButtons.keySet().forEach(button ->
                button.visible = button.active = tab == HostTab.INFO && !joined);
        boolean tilePaging = tab == HostTab.INFO && !joined && tilePageCount() > 1;
        if (tilePagePreviousButton != null) {
            tilePagePreviousButton.visible = tilePaging;
            tilePagePreviousButton.active = tilePaging && tilePage > 0;
        }
        if (tilePageLabelButton != null) {
            tilePageLabelButton.visible = tilePaging;
            tilePageLabelButton.active = false;
            tilePageLabelButton.setMessage(tilePageLabel());
        }
        if (tilePageNextButton != null) {
            tilePageNextButton.visible = tilePaging;
            tilePageNextButton.active = tilePaging && tilePage + 1 < tilePageCount();
        }
        updateContentControlVisibility();
        updateBrowserTabs();
    }

    private void selectContentMode(ScreenContentType mode) {
        contentDraft.switchContentType(mode, contentSourceBox.getValue());
        contentSourceBox.setValue(contentDraft.sourceFor(mode));
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
        contentSourceBox.setMaxLength(VideoSource.MAX_INPUT_LENGTH);
        contentSourceBox.setEditable(content && !idle);
        contentPasswordBox.visible = content && vnc;
        contentPasswordBox.setEditable(content && vnc);
        contentMediaIdBox.visible = content && video;
        contentMediaIdBox.setEditable(content && video);
        contentChooseButton.visible = contentChooseButton.active = content && video;
        contentBackButton.visible = content && web;
        contentForwardButton.visible = content && web;
        contentReloadButton.visible = contentReloadButton.active = content && web;
        contentSplitButton.visible = contentSplitButton.active = content && web;
        contentVncFpsButton.visible = contentVncFpsButton.active = content && vnc;
        contentVncFpsDownButton.visible = contentVncFpsDownButton.active = content && vnc;
        contentVncFpsUpButton.visible = contentVncFpsUpButton.active = content && vnc;
        contentVideoPlayButton.visible = contentVideoPlayButton.active = content && video;
        contentVideoLoopButton.visible = contentVideoLoopButton.active = content && video;
        contentVideoPlayButton.setMessage(contentVideoPlayLabel());
        contentVideoLoopButton.setMessage(contentVideoLoopLabel());
        contentApplyButton.visible = contentApplyButton.active = content;
        contentApplyButton.setMessage(contentApplyLabel());
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

    private Component contentApplyLabel() {
        return Component.translatable(contentDraft != null
                && contentDraft.contentType == ScreenContentType.WEB
                ? "screen.minescreen.web.go" : "screen.minescreen.save");
    }

    private void toggleContentVideoPaused() {
        contentDraft.paused = !contentDraft.paused;
        contentVideoPlayButton.setMessage(contentVideoPlayLabel());
        ScreenContentSession session = ScreenContentManager.session(group.groupId());
        if (session != null && session.type() == ScreenContentType.VIDEO) {
            session.setPaused(contentDraft.paused);
        }
        contentStatus = Component.translatable("screen.minescreen.status.video_state_changed");
        contentStatusColor = 0xFF8FCBFF;
    }

    private void toggleContentVideoLoop() {
        contentDraft.loop = !contentDraft.loop;
        contentVideoLoopButton.setMessage(contentVideoLoopLabel());
        contentStatus = Component.translatable("screen.minescreen.status.video_state_changed");
        contentStatusColor = 0xFF8FCBFF;
    }

    private Component contentVideoPlayLabel() {
        return Component.translatable(contentDraft != null && contentDraft.paused
                ? "screen.minescreen.video.play" : "screen.minescreen.video.pause");
    }

    private Component contentVideoLoopLabel() {
        return Component.translatable("screen.minescreen.video.loop",
                Component.translatable(contentDraft != null && contentDraft.loop
                        ? "options.on" : "options.off"));
    }

    private Component contentBackendStatus() {
        if (contentDraft == null || contentDraft.contentType != ScreenContentType.VIDEO) {
            return null;
        }
        String error = ScreenContentManager.error(group.groupId());
        if (error != null && !error.isBlank()) {
            return Component.translatable("screen.minescreen.backend_error", error);
        }
        ScreenContentSession session = ScreenContentManager.session(group.groupId());
        if (session == null) {
            return Component.translatable("screen.minescreen.video.loading");
        }
        if (session instanceof AsyncContentSession async) {
            ScreenContentSession loaded = async.loadedDelegate();
            if (loaded == null) {
                return Component.translatable("screen.minescreen.video.loading");
            }
            session = loaded;
        }
        String directError = session.errorMessage();
        if (directError != null && !directError.isBlank()) {
            return Component.translatable("screen.minescreen.backend_error", directError);
        }
        if (session instanceof dev.minescreen.client.video.VideoPlaybackSession video
                && !video.hasDecodedFrame()) {
            if (contentDraft.paused) {
                return Component.translatable("screen.minescreen.video.waiting_paused");
            }
            return Component.translatable("screen.minescreen.video.stage."
                    + video.decoderStage().name().toLowerCase(java.util.Locale.ROOT));
        }
        return null;
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

    private void cycleContentSplit() {
        contentDraft.webSplitLayout = (contentDraft.webSplitLayout == null
                ? WebSplitLayout.SINGLE : contentDraft.webSplitLayout).next();
        ScreenContentManager.updateWebSplitLayout(group.groupId(), contentDraft.webSplitLayout);
        contentSplitButton.setMessage(contentSplitLabel());
        contentStatus = Component.translatable("screen.minescreen.status.split_changed");
        contentStatusColor = 0xFF91E5A4;
    }

    private Component contentSplitLabel() {
        WebSplitLayout layout = contentDraft == null || contentDraft.webSplitLayout == null
                ? WebSplitLayout.SINGLE : contentDraft.webSplitLayout;
        return Component.translatable("screen.minescreen.web.split",
                Component.translatable("screen.minescreen.web.split."
                        + layout.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private void adjustContentVncFps(int direction) {
        contentDraft.vncFps = direction < 0 ? VncRefreshRate.previous(contentDraft.vncFps)
                : VncRefreshRate.next(contentDraft.vncFps);
        contentVncFpsButton.setMessage(contentVncFpsLabel());
    }

    private void resetContentVncFps() {
        contentDraft.vncFps = 0;
        contentVncFpsButton.setMessage(contentVncFpsLabel());
    }

    private Component contentVncFpsLabel() {
        int fps = contentDraft == null ? dev.minescreen.MineScreenConfig.VNC_MAX_FPS.get()
                : VncRefreshRate.resolve(contentDraft);
        return Component.translatable(contentDraft == null || contentDraft.vncFps <= 0
                ? "screen.minescreen.vnc.fps_default" : "screen.minescreen.vnc.fps", fps);
    }

    private void applyContentDraft() {
        String source = contentSourceBox.getValue().trim();
        boolean reusedBrowser = false;
        switch (contentDraft.contentType) {
            case IDLE -> source = "";
            case VIDEO -> {
                try {
                    VideoSource.resolve(source);
                } catch (VideoSource.ValidationException invalidSource) {
                    contentError(videoErrorKey(invalidSource.problem()));
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
                if (contentDraft.isVideoSource(source)) {
                    contentError("screen.minescreen.error.web_uses_video_source");
                    contentSourceBox.setValue(contentDraft.sourceFor(ScreenContentType.WEB));
                    return;
                }
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
                BrowserSession browser = browserSession();
                if (browser != null) {
                    boolean openNewTab = Screen.hasShiftDown();
                    String previousActive = browser.restorableActiveUrl();
                    boolean navigated = openNewTab ? browser.openTab(source, true)
                            : browser.navigate(source);
                    if (navigated) {
                        reusedBrowser = true;
                        java.util.LinkedHashSet<String> background = new java.util.LinkedHashSet<>();
                        for (String url : browser.restorableUrls()) {
                            if (url != null && !url.isBlank()
                                    && !url.equalsIgnoreCase("about:blank")
                                    && !url.equals(source)) {
                                if (!openNewTab && url.equals(previousActive)) {
                                    continue;
                                }
                                background.add(url);
                            }
                        }
                        contentDraft.webTabs = new java.util.ArrayList<>(background);
                        pendingContentWebAddress = source;
                        pendingContentWebAddressUntilNanos = System.nanoTime()
                                + java.util.concurrent.TimeUnit.SECONDS.toNanos(3L);
                    }
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
        contentDraft.setSourceFor(contentDraft.contentType, source);
        if (reusedBrowser) {
            // Address changes and Shift+Save tab creation are in-place browser operations. Saving
            // local metadata must not close Chromium and throw away the remaining tab set.
            ScreenContentManager.saveLocalProfile(group.groupId(), contentDraft);
        } else {
            ScreenContentManager.updateProfile(group.groupId(), contentDraft);
            ScreenContentManager.sourceFor(group);
        }
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

    private static String videoErrorKey(VideoSource.Problem problem) {
        return switch (problem) {
            case BLOCKED_FILE -> "screen.minescreen.error.local_files_blocked";
            case INVALID_FILE -> "screen.minescreen.error.video_file";
            case EMPTY -> "screen.minescreen.error.video_source";
            case TOO_LONG -> "screen.minescreen.error.video_source_too_long";
            case INVALID_URL -> "screen.minescreen.error.video_url";
            case BLOCKED_URL -> "screen.minescreen.error.video_blocked";
            case UNSUPPORTED_SCHEME -> "screen.minescreen.error.video_scheme";
        };
    }

    private static String normalizeWebUrl(String value) {
        String trimmed = value.trim();
        return trimmed.contains("://") ? trimmed : "https://" + trimmed;
    }

    private void resolveHostNetwork() {
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        java.util.UUID previousRootId = hostNetwork == null ? null : hostNetwork.rootGroupId();
        ScreenHostNetworkManager.HostNetwork next =
                ScreenHostNetworkManager.networkAt(minecraft.level, computerPos);
        if (next == null) {
            hostNetwork = null;
            return;
        }
        boolean selectedStillPresent = next.groups().stream()
                .anyMatch(member -> member.groupId().equals(group.groupId()));
        hostNetwork = next;
        if (contentDraft != null && previousRootId != null
                && previousRootId.equals(contentDraftGroupId)
                && !previousRootId.equals(next.rootGroupId())) {
            // A cable/surface topology revision may elect a replacement root. Keep the unsaved
            // VIDEO path/mode attached to the host instead of silently restoring IDLE/WEB.
            contentDraftGroupId = next.rootGroupId();
        }
        if (selectedSurfaceId == null || next.groups().stream()
                .noneMatch(member -> member.groupId().equals(selectedSurfaceId))) {
            selectedSurfaceId = next.groups().getFirst().groupId();
        }
        if (!selectedStillPresent || next.panoramic()) {
            group = next.rootGroup();
        } else {
            group = next.groups().stream().filter(member -> member.groupId().equals(group.groupId()))
                    .findFirst().orElse(next.rootGroup());
        }
    }

    private void cycleSurface() {
        if (hostNetwork == null || hostNetwork.groups().size() < 2) {
            return;
        }
        if (hostNetwork.panoramic()) {
            ScreenGroup selected = selectedSurface();
            int index = Math.max(0, hostNetwork.groups().indexOf(selected));
            selectedSurfaceId = hostNetwork.groups()
                    .get((index + 1) % hostNetwork.groups().size()).groupId();
            surfaceButton.setMessage(surfaceLabel());
            return;
        }
        int index = 0;
        for (int i = 0; i < hostNetwork.groups().size(); i++) {
            if (hostNetwork.groups().get(i).groupId().equals(group.groupId())) {
                index = i;
                break;
            }
        }
        releasePreviewKeyboard();
        group = hostNetwork.groups().get((index + 1) % hostNetwork.groups().size());
        selectedSurfaceId = group.groupId();
        rebuildWidgets();
    }

    private void moveSurfaceOrder(int delta) {
        ScreenGroup selected = selectedSurface();
        if (hostNetwork == null || selected == null || hostNetwork.layout() == HostSurfaceLayout.FREE
                || hostNetwork.layout() == HostSurfaceLayout.CUSTOM) {
            return;
        }
        ScreenContentManager.moveHostSurfaceOrder(hostNetwork.rootGroupId(),
                selected.groupId(), delta);
        resolveHostNetwork();
        rebuildWidgets();
    }

    private void moveSurface(int deltaX, int deltaY) {
        ScreenGroup selected = selectedSurface();
        if (hostNetwork == null || selected == null || hostNetwork.layout() == HostSurfaceLayout.FREE) {
            return;
        }
        ScreenContentManager.moveHostSurfacePosition(hostNetwork.rootGroupId(),
                selected.groupId(), deltaX, deltaY);
        resolveHostNetwork();
        rebuildWidgets();
    }

    private void rotateSurface() {
        ScreenGroup selected = selectedSurface();
        if (hostNetwork == null || selected == null) {
            return;
        }
        ScreenContentManager.rotateHostSurface(hostNetwork.rootGroupId(), selected.groupId());
        resolveHostNetwork();
        rebuildWidgets();
    }

    private ScreenGroup selectedSurface() {
        if (hostNetwork == null || hostNetwork.groups().isEmpty()) {
            return group;
        }
        return hostNetwork.groups().stream()
                .filter(member -> member.groupId().equals(selectedSurfaceId))
                .findFirst().orElse(hostNetwork.groups().getFirst());
    }

    private void cycleSurfaceLayout() {
        if (hostNetwork == null || hostNetwork.groups().size() < 2) {
            return;
        }
        releasePreviewKeyboard();
        ScreenContentManager.setHostSurfaceLayout(hostNetwork.rootGroupId(),
                hostNetwork.layout().next());
        resolveHostNetwork();
        rebuildWidgets();
    }

    private Component surfaceLabel() {
        if (hostNetwork == null) {
            return Component.translatable("screen.minescreen.computer.surface_none");
        }
        ScreenGroup displayed = hostNetwork.panoramic() ? selectedSurface() : group;
        int index = 0;
        for (int i = 0; i < hostNetwork.groups().size(); i++) {
            if (hostNetwork.groups().get(i).groupId().equals(displayed.groupId())) {
                index = i;
                break;
            }
        }
        return Component.translatable("screen.minescreen.computer.surface_detailed",
                index + 1, hostNetwork.groups().size(),
                Component.translatable("direction.minecraft." + displayed.facing().getName()),
                displayed.columns(), displayed.rows());
    }

    private Component surfaceLayoutLabel() {
        HostSurfaceLayout layout = hostNetwork == null
                ? HostSurfaceLayout.FREE : hostNetwork.layout();
        return Component.translatable("screen.minescreen.computer.surface_layout",
                Component.translatable("screen.minescreen.computer.surface_layout."
                        + layout.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private Component surfaceRotationLabel() {
        ScreenGroup selected = selectedSurface();
        ScreenHostNetworkManager.Surface surface = hostNetwork == null || selected == null ? null
                : hostNetwork.surface(selected.groupId());
        int degrees = surface == null ? 0 : surface.rotation() * 90;
        return Component.translatable("screen.minescreen.computer.surface_rotation", degrees);
    }

    private void keepHostNetworkAlive() {
        if (hostNetwork == null) {
            ScreenContentManager.requestHostKeepAlive(group.groupId());
            return;
        }
        if (hostNetwork.panoramic()) {
            ScreenContentManager.PanoramaRender panorama =
                    ScreenContentManager.panoramaFor(hostNetwork);
            if (panorama != null) {
                ScreenContentManager.requestHostKeepAlive(hostNetwork.canvas().groupId());
            }
            return;
        }
        for (ScreenGroup member : hostNetwork.groups()) {
            ScreenContentManager.requestHostKeepAlive(member.groupId());
            ScreenContentManager.sourceFor(member);
        }
    }

    private void drawHostPreview(GuiGraphics graphics, int left, int top, int width, int height) {
        if (hostNetwork != null && hostNetwork.panoramic()) {
            ScreenContentManager.PanoramaRender panorama =
                    ScreenContentManager.panoramaFor(hostNetwork);
            if (panorama != null) {
                ScreenPreviewRenderer.drawSource(graphics, panorama.source(), left, top, width, height);
                drawJoinedSurfaceOutlines(graphics, left, top, width, height);
                return;
            }
        }
        if (hostNetwork == null || hostNetwork.groups().size() <= 1) {
            ScreenPreviewRenderer.draw(graphics, group, left, top, width, height);
            return;
        }
        for (PreviewCell cell : previewCells(left, top, width, height)) {
            graphics.fill(cell.left() - 1, cell.top() - 1, cell.right() + 1,
                    cell.bottom() + 1, cell.group().groupId().equals(group.groupId())
                            ? 0xFFFFD43B : 0xFF344154);
            ScreenPreviewRenderer.draw(graphics, cell.group(), cell.left(), cell.top(),
                    cell.width(), cell.height());
            drawSurfaceLabel(graphics, cell.group(), cell.left(), cell.top(),
                    cell.width(), cell.height(), cell.group().groupId().equals(selectedSurfaceId));
        }
    }

    private void drawJoinedSurfaceOutlines(GuiGraphics graphics, int left, int top,
            int width, int height) {
        if (hostNetwork == null) {
            return;
        }
        for (int index = 0; index < hostNetwork.groups().size(); index++) {
            ScreenGroup member = hostNetwork.groups().get(index);
            ScreenHostNetworkManager.Surface surface = hostNetwork.surface(member.groupId());
            if (surface == null) {
                continue;
            }
            int x0 = left + Math.round(surface.left() * width);
            int y0 = top + Math.round(surface.top() * height);
            int x1 = left + Math.round(surface.right() * width);
            int y1 = top + Math.round(surface.bottom() * height);
            boolean selected = member.groupId().equals(selectedSurfaceId);
            int color = selected ? 0xFFFFD43B : 0xFF40DCEB;
            graphics.fill(x0, y0, x1, Math.min(y1, y0 + 2), color);
            graphics.fill(x0, Math.max(y0, y1 - 2), x1, y1, color);
            graphics.fill(x0, y0, Math.min(x1, x0 + 2), y1, color);
            graphics.fill(Math.max(x0, x1 - 2), y0, x1, y1, color);
            if (x1 - x0 >= 30 && y1 - y0 >= 13) {
                Component label = Component.literal("#" + (index + 1) + " "
                        + member.columns() + "×" + member.rows() + " "
                        + member.facing().getName().toUpperCase(java.util.Locale.ROOT));
                graphics.drawString(font, label, x0 + 4, y0 + 4, color, true);
            }
        }
    }

    private void drawSurfaceLabel(GuiGraphics graphics, ScreenGroup member, int left, int top,
            int width, int height, boolean selected) {
        if (width < 30 || height < 13) {
            return;
        }
        Component label = Component.literal(member.columns() + "×" + member.rows() + " "
                + member.facing().getName().toUpperCase(java.util.Locale.ROOT));
        graphics.drawString(font, label, left + 3, top + 3,
                selected ? 0xFFFFD43B : 0xFF40DCEB, true);
    }

    private void drawContentPreview(GuiGraphics graphics, int left, int top, int width, int height) {
        if (hostNetwork != null && hostNetwork.panoramic()) {
            ScreenContentManager.PanoramaRender panorama =
                    ScreenContentManager.panoramaFor(hostNetwork);
            if (panorama != null) {
                ScreenPreviewRenderer.drawSource(graphics, panorama.source(), left, top, width, height);
                return;
            }
        }
        ScreenPreviewRenderer.draw(graphics, group, left, top, width, height);
    }

    private List<PreviewCell> previewCells(int left, int top, int width, int height) {
        List<ScreenGroup> groups = hostNetwork == null ? List.of(group) : hostNetwork.groups();
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(groups.size())));
        int rows = Math.max(1, (groups.size() + columns - 1) / columns);
        int gap = 4;
        int cellWidth = Math.max(1, (width - gap * (columns - 1)) / columns);
        int cellHeight = Math.max(1, (height - gap * (rows - 1)) / rows);
        List<PreviewCell> result = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            int cellLeft = left + column * (cellWidth + gap);
            int cellTop = top + row * (cellHeight + gap);
            result.add(new PreviewCell(groups.get(i), cellLeft, cellTop,
                    Math.min(left + width, cellLeft + cellWidth),
                    Math.min(top + height, cellTop + cellHeight)));
        }
        return result;
    }

    private void createTileButtons() {
        if (hostNetwork != null && hostNetwork.panoramic()) {
            return;
        }
        List<BlockPos> tiles = new ArrayList<>(group.tiles());
        net.minecraft.core.Direction right = dev.minescreen.ScreenGeometry
                .rightDirection(group.facing());
        net.minecraft.core.Direction up = dev.minescreen.ScreenGeometry.upDirection(group.facing());
        int originHorizontal = dev.minescreen.ScreenGeometry.coordinate(group.origin(), right);
        int originVertical = dev.minescreen.ScreenGeometry.coordinate(group.origin(), up);
        tiles.sort(Comparator
                .comparingInt((BlockPos pos) -> group.rows() - 1
                        - (dev.minescreen.ScreenGeometry.coordinate(pos, up) - originVertical))
                .thenComparingInt(pos -> dev.minescreen.ScreenGeometry.coordinate(pos, right)
                        - originHorizontal));
        int pageColumns = tilePageColumnCapacity();
        int pageRows = tilePageRowCapacity();
        int horizontalPages = Math.max(1, (group.columns() + pageColumns - 1) / pageColumns);
        int pages = tilePageCount();
        tilePage = Math.max(0, Math.min(tilePage, pages - 1));
        int pageColumn = tilePage % horizontalPages;
        int pageRow = tilePage / horizontalPages;
        int minimumColumn = pageColumn * pageColumns;
        int minimumVisualRow = pageRow * pageRows;
        int cellWidth = Math.max(18, Math.min(34,
                (panelWidth - 160) / Math.max(1, pageColumns)));
        int gridTop = panelTop + 130;
        for (BlockPos tile : tiles) {
            int column = dev.minescreen.ScreenGeometry.coordinate(tile, right) - originHorizontal;
            int logicalRow = dev.minescreen.ScreenGeometry.coordinate(tile, up) - originVertical;
            int visualRow = group.rows() - 1 - logicalRow;
            if (column < minimumColumn || column >= minimumColumn + pageColumns
                    || visualRow < minimumVisualRow
                    || visualRow >= minimumVisualRow + pageRows) {
                continue;
            }
            int localColumn = column - minimumColumn;
            int localRow = visualRow - minimumVisualRow;
            Button button = addRenderableWidget(MineScreenButton.create(tileLabel(tile),
                    clicked -> assignOrToggleTile(clicked, tile),
                    panelLeft + 146 + localColumn * cellWidth,
                    gridTop + localRow * 24, Math.max(14, cellWidth - 4), 20));
            tileButtons.put(button, tile);
        }
        if (pages > 1) {
            int pagerTop = gridTop + pageRows * 24 + 4;
            tilePagePreviousButton = addRenderableWidget(MineScreenButton.create(
                    Component.literal("◀"), button -> changeTilePage(-1),
                    panelLeft + 146, pagerTop, 28, 20));
            tilePageLabelButton = addRenderableWidget(MineScreenButton.create(tilePageLabel(),
                    button -> { }, panelLeft + 178, pagerTop, 196, 20));
            tilePageNextButton = addRenderableWidget(MineScreenButton.create(
                    Component.literal("▶"), button -> changeTilePage(1),
                    panelLeft + 378, pagerTop, 28, 20));
        }
    }

    private int tilePageColumnCapacity() {
        return Math.max(4, Math.min(MAX_TILE_PAGE_COLUMNS,
                Math.max(1, panelWidth - 160) / 26));
    }

    private int tilePageRowCapacity() {
        return Math.max(3, Math.min(MAX_TILE_PAGE_ROWS,
                Math.max(1, panelHeight - 180) / 24));
    }

    private int tilePageCount() {
        if (group == null || hostNetwork != null && hostNetwork.panoramic()) {
            return 1;
        }
        int horizontal = Math.max(1,
                (group.columns() + tilePageColumnCapacity() - 1) / tilePageColumnCapacity());
        int vertical = Math.max(1,
                (group.rows() + tilePageRowCapacity() - 1) / tilePageRowCapacity());
        return horizontal * vertical;
    }

    private void changeTilePage(int delta) {
        int next = Math.max(0, Math.min(tilePageCount() - 1, tilePage + delta));
        if (next == tilePage) {
            return;
        }
        tilePage = next;
        rebuildWidgets();
    }

    private Component tilePageLabel() {
        int columns = tilePageColumnCapacity();
        int rows = tilePageRowCapacity();
        int horizontal = Math.max(1, (group.columns() + columns - 1) / columns);
        int pageColumn = tilePage % horizontal;
        int pageRow = tilePage / horizontal;
        int firstColumn = pageColumn * columns + 1;
        int lastColumn = Math.min(group.columns(), firstColumn + columns - 1);
        int firstRow = pageRow * rows + 1;
        int lastRow = Math.min(group.rows(), firstRow + rows - 1);
        return Component.translatable("screen.minescreen.computer.tile_page",
                tilePage + 1, tilePageCount(), firstColumn, lastColumn, firstRow, lastRow);
    }

    private void assignOrToggleTile(Button button, BlockPos tile) {
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
        if (Screen.hasShiftDown()) {
            boolean enable = profile.disabledTiles.contains(tile.asLong());
            ScreenContentManager.setTileEnabled(group.groupId(), tile, enable);
        } else {
            ScreenContentManager.assignTile(group.groupId(), tile, selectedRegion);
        }
        ClientScreenProfile latest = ScreenContentManager.profile(group.groupId()).copy();
        contentDraft.disabledTiles = latest.disabledTiles;
        contentDraft.tileRegions = latest.tileRegions;
        contentDraft.regions = latest.regions;
        button.setMessage(tileLabel(tile));
    }

    private Component tileLabel(BlockPos tile) {
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
        if (profile.disabledTiles.contains(tile.asLong())) {
            return Component.literal("×");
        }
        int region = ScreenRegionLayout.regionAt(profile, tile);
        return Component.literal(region == 0 ? "M" : Integer.toString(region));
    }

    private void cycleSelectedRegion() {
        selectedRegion = (selectedRegion + 1) % ScreenRegionLayout.MAX_REGIONS;
        regionSelectButton.setMessage(regionSelectLabel());
    }

    private Component regionSelectLabel() {
        return Component.translatable("screen.minescreen.region.selected",
                selectedRegion == 0 ? Component.translatable("screen.minescreen.region.main")
                        : Component.translatable("screen.minescreen.region.number", selectedRegion));
    }

    private void editSelectedRegion() {
        if (hostNetwork != null && hostNetwork.panoramic()) {
            ScreenGroup selected = selectedSurface();
            group = selected;
            selectedSurfaceId = selected.groupId();
            ScreenContentManager.setHostSurfaceLayout(hostNetwork.rootGroupId(),
                    HostSurfaceLayout.FREE);
            resolveHostNetwork();
            contentStatus = Component.translatable(
                    "screen.minescreen.status.independent_split_enabled");
            contentStatusColor = 0xFF91E5A4;
            rebuildWidgets();
            return;
        }
        ScreenRegionLayout.Canvas canvas = ScreenRegionLayout.canvas(group,
                ScreenContentManager.profile(group.groupId()), selectedRegion);
        if (canvas == null) {
            contentStatus = Component.translatable("screen.minescreen.region.empty");
            contentStatusColor = 0xFFFF7777;
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(contentStatus, true);
            }
            return;
        }
        minecraft.setScreen(new ScreenEditorScreen(group, selectedRegion, this));
    }

    private Component regionActionLabel() {
        return Component.translatable(hostNetwork != null && hostNetwork.panoramic()
                ? "screen.minescreen.computer.enable_independent_split"
                : "screen.minescreen.region.edit");
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
        if (contentDraft != null) {
            contentDraft.volume = profile.volume;
        }
        ScreenContentManager.updateVolume(group.groupId(), profile.volume);
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
        syncContentWebAddress(browser, false);
    }

    private void selectBrowserTab(int slot) {
        BrowserSession browser = browserSession();
        int index = displayedTabs[slot];
        if (browser == null || index < 0) {
            return;
        }
        if (Screen.hasShiftDown()) {
            browser.closeTab(index);
        } else {
            browser.activateTab(index);
        }
        pendingContentWebAddress = null;
        ScreenContentManager.saveBrowserState(group.groupId(), selectedRegion, browser);
        syncContentWebAddress(browser, true);
        updateBrowserTabs();
    }

    private void closeBrowserTab(int slot) {
        BrowserSession browser = browserSession();
        int index = displayedTabs[slot];
        if (browser == null || index < 0) {
            return;
        }
        browser.closeTab(index);
        pendingContentWebAddress = null;
        ScreenContentManager.saveBrowserState(group.groupId(), selectedRegion, browser);
        syncContentWebAddress(browser, true);
        updateBrowserTabs();
    }

    private void syncContentWebAddress(BrowserSession browser, boolean force) {
        if (browser == null || contentDraft == null
                || contentDraft.contentType != ScreenContentType.WEB || contentSourceBox == null) {
            observedContentWebTab = -1;
            return;
        }
        int active = browser.activeTabIndex();
        boolean tabChanged = active != observedContentWebTab;
        if (!force && !tabChanged && contentSourceBox.isFocused()) {
            return;
        }
        String url = browser.currentUrl();
        if (url == null || url.isBlank() || url.equalsIgnoreCase("about:blank")) {
            return;
        }
        if (pendingContentWebAddress != null) {
            if (url.equals(pendingContentWebAddress)) {
                pendingContentWebAddress = null;
            } else if (!tabChanged && System.nanoTime() < pendingContentWebAddressUntilNanos) {
                return;
            } else {
                pendingContentWebAddress = null;
            }
        }
        observedContentWebTab = active;
        if (!url.equals(contentSourceBox.getValue())) {
            contentSourceBox.setValue(url);
        }
        contentDraft.setSourceFor(ScreenContentType.WEB, url);
        if (tabChanged) {
            ScreenContentManager.saveBrowserState(group.groupId(), selectedRegion, browser);
        }
    }

    private BrowserSession browserSession() {
        if (ScreenContentManager.profile(group.groupId()).contentType != ScreenContentType.WEB) {
            return null;
        }
        ScreenContentManager.sourceFor(group);
        return ScreenContentManager.session(group.groupId()) instanceof BrowserSession browser
                ? browser : null;
    }

    private PreviewPointer previewPointer(double mouseX, double mouseY) {
        if (hostNetwork != null && hostNetwork.panoramic()) {
            ClientScreenProfile root = ScreenContentManager.profile(hostNetwork.rootGroupId());
            if (root.contentType != ScreenContentType.WEB
                    && (root.contentType != ScreenContentType.VNC || root.vncReadOnly)) {
                return null;
            }
            ScreenContentManager.sourceFor(hostNetwork.rootGroup());
            if (!(ScreenContentManager.session(hostNetwork.rootGroupId())
                    instanceof ScreenInputTarget target)) {
                return null;
            }
            double u = Math.max(0.0D, Math.min(0.999999D,
                    (mouseX - previewLeft) / Math.max(1.0D, previewWidth)));
            double v = Math.max(0.0D, Math.min(0.999999D,
                    (mouseY - previewTop) / Math.max(1.0D, previewHeight)));
            return new PreviewPointer(hostNetwork.rootGroupId(), 0, target,
                    clamp((int) Math.floor(u * target.inputWidth()), target.inputWidth()),
                    clamp((int) Math.floor(v * target.inputHeight()), target.inputHeight()));
        }
        if (hostNetwork != null && hostNetwork.groups().size() > 1) {
            for (PreviewCell cell : previewCells(previewLeft, previewTop,
                    previewWidth, previewHeight)) {
                if (cell.contains(mouseX, mouseY)) {
                    return previewPointer(cell.group(), mouseX, mouseY, cell.left(), cell.top(),
                            cell.width(), cell.height());
                }
            }
            return null;
        }
        return previewPointer(group, mouseX, mouseY, previewLeft, previewTop,
                previewWidth, previewHeight);
    }

    private PreviewPointer previewPointer(ScreenGroup targetGroup, double mouseX, double mouseY,
            int areaLeft, int areaTop, int areaWidth, int areaHeight) {
        double globalU = Math.max(0.0D, Math.min(0.999999D,
                (mouseX - areaLeft) / Math.max(1.0D, areaWidth)));
        double globalV = Math.max(0.0D, Math.min(0.999999D,
                (mouseY - areaTop) / Math.max(1.0D, areaHeight)));
        if (targetGroup.legacyAnchor()) {
            ClientScreenProfile content = ScreenContentManager.profile(targetGroup.groupId());
            if (content.contentType != ScreenContentType.WEB
                    && (content.contentType != ScreenContentType.VNC || content.vncReadOnly)) {
                return null;
            }
            ScreenContentManager.sourceFor(targetGroup);
            if (!(ScreenContentManager.session(targetGroup.groupId())
                    instanceof ScreenInputTarget target)) {
                return null;
            }
            return new PreviewPointer(targetGroup.groupId(), 0, target,
                    clamp((int) Math.floor(globalU * target.inputWidth()), target.inputWidth()),
                    clamp((int) Math.floor(globalV * target.inputHeight()), target.inputHeight()));
        }
        double horizontal = globalU * targetGroup.columns();
        double vertical = (1.0D - globalV) * targetGroup.rows();
        int column = Math.max(0, Math.min(targetGroup.columns() - 1, (int) Math.floor(horizontal)));
        int row = Math.max(0, Math.min(targetGroup.rows() - 1, (int) Math.floor(vertical)));
        BlockPos tile = targetGroup.origin().relative(dev.minescreen.ScreenGeometry
                .rightDirection(targetGroup.facing()), column).relative(dev.minescreen.ScreenGeometry
                        .upDirection(targetGroup.facing()), row);
        ClientScreenProfile root = ScreenContentManager.profile(targetGroup.groupId());
        if (!targetGroup.tiles().contains(tile) || root.disabledTiles.contains(tile.asLong())) {
            return null;
        }
        int regionId = ScreenRegionLayout.regionAt(root, tile);
        ClientScreenProfile content = ScreenContentManager.profile(targetGroup.groupId(), regionId);
        if (content.contentType != ScreenContentType.WEB
                && (content.contentType != ScreenContentType.VNC || content.vncReadOnly)) {
            return null;
        }
        ScreenRegionLayout.Canvas canvas = ScreenRegionLayout.canvas(targetGroup, root, regionId);
        if (canvas == null) {
            return null;
        }
        ScreenContentManager.sourceFor(targetGroup, regionId);
        if (!(ScreenContentManager.session(targetGroup.groupId(), regionId)
                instanceof ScreenInputTarget target)) {
            return null;
        }
        double localU = (horizontal - canvas.minColumn()) / canvas.columns();
        double localV = 1.0D - (vertical - canvas.minRow()) / canvas.rows();
        int x = clamp((int) Math.floor(localU * target.inputWidth()), target.inputWidth());
        int y = clamp((int) Math.floor(localV * target.inputHeight()), target.inputHeight());
        return new PreviewPointer(targetGroup.groupId(), regionId, target, x, y);
    }

    private void focusPreviewKeyboard(ScreenInputTarget target, java.util.UUID groupId, int regionId) {
        if (previewKeyboardTarget == target && previewKeyboardRegionId == regionId
                && java.util.Objects.equals(previewKeyboardGroupId, groupId)) {
            return;
        }
        releasePreviewKeyboard();
        previewKeyboardTarget = target;
        previewKeyboardGroupId = groupId;
        previewKeyboardRegionId = regionId;
        target.focus(true);
    }

    private void releasePreviewKeyboard() {
        ScreenInputTarget target = previewKeyboardTarget;
        if (target == null) {
            pressedPreviewKeys.clear();
            return;
        }
        for (java.util.Map.Entry<Integer, PressedPreviewKey> entry
                : java.util.List.copyOf(pressedPreviewKeys.entrySet())) {
            PressedPreviewKey key = entry.getValue();
            target.keyRelease(entry.getKey(), key.scanCode(), key.modifiers());
        }
        pressedPreviewKeys.clear();
        target.focus(false);
        previewKeyboardTarget = null;
        previewKeyboardGroupId = null;
        previewKeyboardRegionId = -1;
    }

    private boolean insidePreview(double mouseX, double mouseY) {
        return mouseX >= previewLeft && mouseX < previewLeft + previewWidth
                && mouseY >= previewTop && mouseY < previewTop + previewHeight;
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

    private record PressedPreviewKey(int scanCode, int modifiers) {
    }

    private record PreviewPointer(java.util.UUID groupId, int regionId,
            ScreenInputTarget target, int x, int y) {
    }

    private record PreviewCell(ScreenGroup group, int left, int top, int right, int bottom) {
        int width() {
            return Math.max(1, right - left);
        }

        int height() {
            return Math.max(1, bottom - top);
        }

        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }
}
