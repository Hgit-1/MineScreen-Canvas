package dev.minescreen.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import dev.minescreen.TextDisplayAnimation;
import dev.minescreen.TextDisplayBlockEntity;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.DisplayBackMode;
import dev.minescreen.StationDisplayMode;
import dev.minescreen.TrafficDisplayRole;
import dev.minescreen.client.ui.MineScreenUiRegistry;
import dev.minescreen.client.compat.MovingStructureCompat;
import dev.minescreen.client.traffic.CreateTrainScheduleService;
import dev.minescreen.client.traffic.TrafficImportResult;
import dev.minescreen.client.traffic.TrafficTemplateRepository;
import dev.minescreen.client.traffic.TrafficStationLocator;
import dev.minescreen.network.TrafficDisplayUpdatePayload;
import dev.minescreen.network.MovingTrafficDisplayUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Task-oriented traffic editor.
 *
 * <p>The first page deliberately contains everything required to make a platform board work:
 * choose its purpose, bind the physical Create station, and choose/import the visual template.
 * Manual fallback text and rarely used route/style overrides live on later pages. This order is
 * important because a user should not need to understand MineScreen's stored fields before they
 * can configure a working departure board.</p>
 */
public final class TrafficDisplayEditorScreen extends ResponsiveMineScreen {
    private static final int PANEL_HEIGHT = 292;
    private final TextDisplayBlockEntity initial;
    private final net.minecraft.core.BlockPos pos;
    private final boolean backSide;
    private final int movingEntityId;
    private final String initialTemplateId;
    private final String initialOverlayTemplateId;
    private MineScreenEditBox line;
    private MineScreenEditBox destination;
    private MineScreenEditBox current;
    private MineScreenEditBox next;
    private MineScreenEditBox eta;
    private MineScreenEditBox status;
    private MineScreenEditBox template;
    private MineScreenEditBox overlayTemplate;
    private MineScreenEditBox textColor;
    private MineScreenEditBox backgroundColor;
    private MineScreenEditBox trainType;
    private MineScreenEditBox mapTemplate;
    private MineScreenEditBox stationBinding;
    private MineScreenEditBox stationTurnaround;
    private Button animationButton;
    private Button fontButton;
    private Button importButton;
    private Button importOverlayButton;
    private Button clearOverlayButton;
    private Button stationAliasButton;
    private Button backModeButton;
    private Button routeTabButton;
    private Button styleTabButton;
    private Button stationTabButton;
    private Button manualModeButton;
    private Button nextModeButton;
    private Button mapModeButton;
    private Button bindStationButton;
    private Button clearBindingButton;
    private Button roleButton;
    private TextDisplayAnimation animation;
    private int fontSize;
    private DisplayBackMode backMode;
    private StationDisplayMode stationMode;
    private TrafficDisplayRole trafficRole;
    private int page;
    private int left;
    private int top;
    private int panelWidth;
    private Component error = Component.empty();
    private int messageColor = 0xFFFF6B6B;
    private List<StationSuggestion> stationSuggestions = List.of();
    private int selectedSuggestion;
    private MineScreenEditBox suggestionTarget;

    public TrafficDisplayEditorScreen(TextDisplayBlockEntity display) {
        this(display, false, -1);
    }

    public TrafficDisplayEditorScreen(TextDisplayBlockEntity display, boolean backSide) {
        this(display, backSide, -1);
    }

    public TrafficDisplayEditorScreen(TextDisplayBlockEntity display, boolean backSide,
            int movingEntityId) {
        super(Component.translatable("screen.minescreen.traffic.title"));
        initial = display;
        pos = display.getBlockPos().immutable();
        this.backSide = backSide;
        this.movingEntityId = movingEntityId;
        animation = display.animation(backSide);
        fontSize = display.fontSize(backSide);
        backMode = display.backMode();
        stationMode = display.stationMode(backSide);
        trafficRole = movingEntityId >= 0 ? TrafficDisplayRole.ONBOARD : display.trafficRole();
        boolean legacyTextMode = stationMode == StationDisplayMode.TEXT_SEQUENCE;
        initialTemplateId = legacyTextMode ? "builtin_transit" : display.templateId(backSide);
        initialOverlayTemplateId = legacyTextMode ? display.templateId(backSide)
                : display.overlayTemplateId(backSide);
        if (legacyTextMode) {
            stationMode = trafficRole == TrafficDisplayRole.ONBOARD
                    ? StationDisplayMode.STATION_NEXT : StationDisplayMode.MANUAL;
        } else if (trafficRole == TrafficDisplayRole.ONBOARD) {
            stationMode = StationDisplayMode.STATION_NEXT;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        configureResponsiveLayout(692, 304);
        panelWidth = Math.min(680, layoutWidth() - 12);
        left = (layoutWidth() - panelWidth) / 2;
        top = (layoutHeight() - PANEL_HEIGHT) / 2;
        int inner = panelWidth - 32;
        int half = (inner - 8) / 2;
        int contentTop = top + 79;
        line = field(left + 16, contentTop, half, "screen.minescreen.traffic.line",
                initial.trafficLine(backSide));
        destination = field(left + 24 + half, contentTop, half,
                "screen.minescreen.traffic.destination", initial.trafficDestination(backSide));
        current = field(left + 16, contentTop + 34, half, "screen.minescreen.traffic.current",
                initial.trafficCurrentStop(backSide));
        next = field(left + 24 + half, contentTop + 34, half, "screen.minescreen.traffic.next",
                initial.trafficNextStop(backSide));
        eta = field(left + 16, contentTop + 68, half, "screen.minescreen.traffic.eta",
                initial.trafficEta(backSide));
        status = field(left + 24 + half, contentTop + 68, half, "screen.minescreen.traffic.status",
                initial.trafficStatus(backSide));
        template = field(left + 16, contentTop + 72, inner - 174,
                "screen.minescreen.traffic.template",
                initialTemplateId);
        importButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.traffic.import_short"),
                button -> importTemplate(), left + panelWidth - 182, contentTop + 72, 80, 18));
        stationAliasButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.station_alias.short"),
                button -> openStationAliases(), left + panelWidth - 98, contentTop + 72, 82, 18));

        overlayTemplate = field(left + 16, contentTop + 106, inner - 174,
                "screen.minescreen.traffic.overlay_template", initialOverlayTemplateId);
        overlayTemplate.setEditable(false);
        importOverlayButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.traffic.import_txt"),
                button -> importOverlay(), left + panelWidth - 182, contentTop + 106, 80, 18));
        clearOverlayButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.traffic.clear_overlay"),
                button -> clearOverlay(), left + panelWidth - 98, contentTop + 106, 82, 18));

        int modeWidth = (inner - 8) / 3;
        manualModeButton = addRenderableWidget(MineScreenButton.create(Component.empty(),
                button -> selectStationMode(StationDisplayMode.MANUAL), left + 16, contentTop,
                modeWidth, 20));
        nextModeButton = addRenderableWidget(MineScreenButton.create(Component.empty(),
                button -> selectStationMode(StationDisplayMode.STATION_NEXT),
                left + 20 + modeWidth, contentTop, modeWidth, 20));
        mapModeButton = addRenderableWidget(MineScreenButton.create(Component.empty(),
                button -> selectStationMode(StationDisplayMode.STATION_MAP),
                left + 24 + modeWidth * 2, contentTop, inner - modeWidth * 2 - 8, 20));
        stationBinding = field(left + 16, contentTop + 38, inner - 264,
                "screen.minescreen.traffic.station_binding", initial.stationBindingName(backSide));
        bindStationButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.traffic.scan_bind"),
                button -> bindNearestStation(), left + panelWidth - 272, contentTop + 38, 166, 18));
        clearBindingButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.traffic.clear_binding"),
                button -> clearStationBinding(), left + panelWidth - 102, contentTop + 38, 86, 18));
        stationTurnaround = field(left + 24 + half, contentTop + 68, half,
                "screen.minescreen.traffic.station_turnaround",
                initial.stationTurnaroundName(backSide));
        trainType = field(left + 16, contentTop + 68, half,
                "screen.minescreen.traffic.station_train_type", initial.stationTrainType(backSide));
        mapTemplate = field(left + 16, contentTop + 102, inner,
                "screen.minescreen.traffic.station_map_template", initial.stationMapTemplateId(backSide));
        textColor = field(left + 16, contentTop, half,
                "screen.minescreen.text_display.text_color", colorString(initial.textColor(backSide)));
        backgroundColor = field(left + 24 + half, contentTop, half,
                "screen.minescreen.text_display.background_color",
                colorString(initial.backgroundColor(backSide)));
        animationButton = addRenderableWidget(MineScreenButton.create(animationLabel(),
                button -> cycleAnimation(), left + 16, contentTop + 34, half, 20));
        fontButton = addRenderableWidget(MineScreenButton.create(fontLabel(),
                button -> cycleFontSize(), left + 24 + half, contentTop + 34, half, 20));

        int tabWidth = (inner - 8) / 3;
        routeTabButton = addRenderableWidget(MineScreenButton.create(Component.empty(),
                button -> selectPage(0), left + 16, top + 40, tabWidth, 20));
        styleTabButton = addRenderableWidget(MineScreenButton.create(Component.empty(),
                button -> selectPage(1), left + 20 + tabWidth, top + 40, tabWidth, 20));
        stationTabButton = addRenderableWidget(MineScreenButton.create(Component.empty(),
                button -> selectPage(2), left + 24 + tabWidth * 2, top + 40,
                inner - tabWidth * 2 - 8, 20));
        addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.traffic.save_apply"),
                button -> save(true), left + 16, top + 260, half, 20));
        addRenderableWidget(MineScreenButton.create(Component.translatable("gui.cancel"),
                button -> onClose(), left + 24 + half, top + 260, half, 20));
        backModeButton = addRenderableWidget(MineScreenButton.create(backModeLabel(),
                button -> cycleBackMode(), left + panelWidth - 158, top + 7, 144, 20));
        backModeButton.visible = backModeButton.active = !backSide;
        roleButton = addRenderableWidget(MineScreenButton.create(roleLabel(),
                button -> cycleTrafficRole(), left + panelWidth - 318, top + 7, 152, 20));
        roleButton.active = movingEntityId < 0;
        refreshStationSuggestions();
        updateModeButtons();
        showPage();
    }

    private MineScreenEditBox field(int x, int y, int fieldWidth, String label, String value) {
        MineScreenEditBox box = new MineScreenEditBox(font, x, y, fieldWidth, 18,
                Component.translatable(label));
        box.setMaxLength(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        box.setValue(value == null ? "" : value);
        box.setHint(fieldHint(label));
        addRenderableWidget(box);
        return box;
    }

    private static Component fieldHint(String label) {
        return switch (label) {
            case "screen.minescreen.traffic.line" -> Component.translatable(
                    "screen.minescreen.traffic.hint_line");
            case "screen.minescreen.traffic.destination" -> Component.translatable(
                    "screen.minescreen.traffic.hint_destination");
            case "screen.minescreen.traffic.current" -> Component.translatable(
                    "screen.minescreen.traffic.hint_origin");
            case "screen.minescreen.traffic.next" -> Component.translatable(
                    "screen.minescreen.traffic.hint_next");
            case "screen.minescreen.traffic.eta" -> Component.translatable(
                    "screen.minescreen.traffic.hint_eta");
            case "screen.minescreen.traffic.status" -> Component.translatable(
                    "screen.minescreen.traffic.hint_status");
            case "screen.minescreen.traffic.station_binding" -> Component.translatable(
                    "screen.minescreen.traffic.hint_station_binding");
            case "screen.minescreen.traffic.station_turnaround" -> Component.translatable(
                    "screen.minescreen.traffic.hint_station_turnaround");
            case "screen.minescreen.traffic.station_train_type" -> Component.translatable(
                    "screen.minescreen.traffic.hint_train_type");
            case "screen.minescreen.traffic.station_map_template" -> Component.translatable(
                    "screen.minescreen.traffic.hint_map_template");
            case "screen.minescreen.traffic.template" -> Component.translatable(
                    "screen.minescreen.traffic.hint_template");
            default -> Component.empty();
        };
    }

    /**
     * Both station fields accept Tab completion. The bound-station field stores Create's exact
     * name, while the turnaround field prefers an imported LCD code (C5 etc.) when a mapping is
     * available. The popup makes that distinction visible instead of requiring users to guess.
     */
    private void refreshStationSuggestions() {
        MineScreenEditBox target = focusedStationField();
        if (target == null || !suggestionsVisibleOnCurrentPage(target)) {
            suggestionTarget = null;
            stationSuggestions = List.of();
            selectedSuggestion = 0;
            return;
        }
        String query = target.getValue().trim().toLowerCase(Locale.ROOT);
        Map<String, StationSuggestion> values = new LinkedHashMap<>();
        String templateId = template == null ? initial.templateId(backSide) : template.getValue();
        Map<String, String> bindings = TrafficTemplateRepository.metadata(templateId)
                .stationBindings();
        if (target == stationTurnaround) {
            for (TrafficTemplateRepository.StationProfile station :
                    TrafficTemplateRepository.stationProfiles(templateId)) {
                String code = station.code().isBlank() ? station.displayName() : station.code();
                if (code.isBlank()) continue;
                String createName = bindings.getOrDefault(station.code(), "");
                String label = station.displayName().isBlank() ? code
                        : code + "  ·  " + station.displayName();
                if (!createName.isBlank()) label += "  →  " + createName;
                addSuggestion(values, code, label);
            }
        }
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            if (target == stationBinding) {
                addSuggestion(values, binding.getValue(),
                        binding.getValue() + "  ←  " + binding.getKey());
            } else {
                addSuggestion(values, binding.getKey(),
                        binding.getKey() + "  →  " + binding.getValue());
            }
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            for (TrafficStationLocator.StationMatch match : TrafficStationLocator.nearbyStations(
                    minecraft.level, pos, MineScreenConfig.CREATE_STATION_SCAN_RADIUS.get())) {
                String label = Component.translatable(
                        "screen.minescreen.traffic.station_suggestion_nearby",
                        match.createStationName(), Math.round(match.distance())).getString();
                addSuggestion(values, match.createStationName(), label);
            }
            if (target == stationTurnaround) {
                CreateTrainScheduleService.Snapshot schedule =
                        CreateTrainScheduleService.snapshotForDisplay(minecraft.level, pos,
                                templateId, stationBinding.getValue(),
                                initial.trafficCurrentStop(backSide), trainType.getValue(), "",
                                8).snapshot();
                for (CreateTrainScheduleService.Departure departure : schedule.departures()) {
                    addSuggestion(values, departure.origin(), departure.origin());
                    addSuggestion(values, departure.destination(), departure.destination());
                }
            }
        }
        if (movingEntityId >= 0 && target == stationTurnaround) {
            MovingStructureCompat.CarriageTrainStatus train =
                    MovingStructureCompat.carriageTrainStatus(initial.getLevel());
            if (train != null) {
                addSuggestion(values, train.stationName(), train.stationName());
                addSuggestion(values, train.terminalStation(), train.terminalStation());
                for (String stop : train.upcomingStops()) addSuggestion(values, stop, stop);
            }
        }
        List<StationSuggestion> ranked = new ArrayList<>();
        for (StationSuggestion suggestion : values.values()) {
            String haystack = (suggestion.value() + " " + suggestion.label())
                    .toLowerCase(Locale.ROOT);
            if (query.isBlank() || haystack.contains(query)) ranked.add(suggestion);
        }
        ranked.sort(java.util.Comparator
                .comparingInt((StationSuggestion suggestion) -> matchRank(suggestion, query))
                .thenComparing(StationSuggestion::label, String.CASE_INSENSITIVE_ORDER));
        suggestionTarget = target;
        stationSuggestions = List.copyOf(ranked.stream().limit(6).toList());
        selectedSuggestion = Math.max(0,
                Math.min(selectedSuggestion, Math.max(0, stationSuggestions.size() - 1)));
    }

    private static int matchRank(StationSuggestion suggestion, String query) {
        if (query.isBlank()) return 2;
        String value = suggestion.value().toLowerCase(Locale.ROOT);
        String label = suggestion.label().toLowerCase(Locale.ROOT);
        if (value.equals(query)) return 0;
        if (value.startsWith(query) || label.startsWith(query)) return 1;
        return 2;
    }

    private static void addSuggestion(Map<String, StationSuggestion> values, String value,
            String label) {
        String safe = value == null ? "" : value.trim();
        if (safe.isBlank()) return;
        values.putIfAbsent(safe.toLowerCase(Locale.ROOT),
                new StationSuggestion(safe, label == null ? safe : label));
    }

    private MineScreenEditBox focusedStationField() {
        if (stationBinding != null && stationBinding.isFocused()) return stationBinding;
        if (stationTurnaround != null && stationTurnaround.isFocused()) return stationTurnaround;
        return null;
    }

    private boolean suggestionsVisibleOnCurrentPage(MineScreenEditBox target) {
        if (target == stationBinding) {
            return trafficRole == TrafficDisplayRole.PLATFORM && page == 0;
        }
        return target == stationTurnaround && page == 2;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        MineScreenEditBox target = focusedStationField();
        if (target != null && suggestionsVisibleOnCurrentPage(target)) {
            refreshStationSuggestions();
            if (keyCode == GLFW.GLFW_KEY_TAB && !stationSuggestions.isEmpty()) {
                applySuggestion(target);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN && !stationSuggestions.isEmpty()) {
                selectedSuggestion = (selectedSuggestion + 1) % stationSuggestions.size();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP && !stationSuggestions.isEmpty()) {
                selectedSuggestion = Math.floorMod(selectedSuggestion - 1,
                        stationSuggestions.size());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER && !stationSuggestions.isEmpty()) {
                applySuggestion(target);
                return true;
            }
        }
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        refreshStationSuggestions();
        return handled;
    }

    private void applySuggestion(MineScreenEditBox target) {
        StationSuggestion suggestion = stationSuggestions.get(selectedSuggestion);
        target.setValue(suggestion.value());
        target.setCursorPosition(suggestion.value().length());
        error = Component.translatable("screen.minescreen.traffic.station_suggestion_selected",
                suggestion.label());
        messageColor = 0xFF62E6A7;
        refreshStationSuggestions();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double logicalX = logicalMouseX(mouseX);
        double logicalY = logicalMouseY(mouseY);
        refreshStationSuggestions();
        if (suggestionTarget != null && suggestionsVisibleOnCurrentPage(suggestionTarget)
                && !stationSuggestions.isEmpty()) {
            int popupY = suggestionTarget.getY() + suggestionTarget.getHeight() + 1;
            int popupHeight = stationSuggestions.size() * 14;
            if (logicalX >= suggestionTarget.getX()
                    && logicalX < suggestionTarget.getX() + suggestionTarget.getWidth()
                    && logicalY >= popupY && logicalY < popupY + popupHeight) {
                selectedSuggestion = Math.max(0, Math.min(stationSuggestions.size() - 1,
                        ((int) logicalY - popupY) / 14));
                applySuggestion(suggestionTarget);
                return true;
            }
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        refreshStationSuggestions();
        return handled;
    }

    private void showPage() {
        boolean setupPage = page == 0;
        boolean contentPage = page == 1;
        boolean advancedPage = page == 2;
        boolean platform = trafficRole == TrafficDisplayRole.PLATFORM;
        boolean needsStation = requiresPlatformStation();
        line.visible = destination.visible = current.visible = next.visible = eta.visible =
                status.visible = contentPage;
        template.visible = setupPage;
        importButton.visible = importButton.active = setupPage;
        stationAliasButton.visible = setupPage;
        stationAliasButton.active = setupPage
                && !TrafficTemplateRepository.stationProfiles(template.getValue()).isEmpty();
        stationAliasButton.setMessage(aliasButtonLabel());
        int inner = panelWidth - 32;
        if (platform) {
            int modeWidth = (inner - 8) / 3;
            manualModeButton.setX(left + 16);
            manualModeButton.setWidth(modeWidth);
            nextModeButton.setX(left + 20 + modeWidth);
            nextModeButton.setWidth(modeWidth);
            mapModeButton.setX(left + 24 + modeWidth * 2);
            mapModeButton.setWidth(inner - modeWidth * 2 - 8);
        } else {
            nextModeButton.setX(left + 16);
            nextModeButton.setWidth(inner);
        }
        manualModeButton.visible = mapModeButton.visible = setupPage && platform;
        manualModeButton.active = mapModeButton.active = setupPage && platform;
        nextModeButton.visible = nextModeButton.active = setupPage;
        overlayTemplate.visible = contentPage;
        importOverlayButton.visible = importOverlayButton.active = contentPage;
        clearOverlayButton.visible = contentPage;
        clearOverlayButton.active = contentPage && !overlayTemplate.getValue().isBlank();
        stationBinding.visible = setupPage && needsStation;
        stationBinding.setEditable(needsStation);
        bindStationButton.visible = bindStationButton.active = setupPage;
        bindStationButton.setMessage(setupActionLabel());
        bindStationButton.setX(needsStation ? left + panelWidth - 272 : left + 16);
        bindStationButton.setWidth(needsStation ? 166 : panelWidth - 32);
        bindStationButton.active = setupPage && needsStation;
        clearBindingButton.visible = setupPage && needsStation;
        clearBindingButton.active = setupPage && needsStation
                && !stationBinding.getValue().isBlank();
        stationTurnaround.visible = advancedPage;
        stationTurnaround.setX(platform ? left + 24 + (panelWidth - 40) / 2 : left + 16);
        stationTurnaround.setWidth(platform ? (panelWidth - 40) / 2 : panelWidth - 32);
        trainType.visible = mapTemplate.visible = advancedPage && platform;
        textColor.visible = backgroundColor.visible = advancedPage;
        animationButton.visible = fontButton.visible = advancedPage;
        routeTabButton.setMessage(tabLabel(0, "screen.minescreen.traffic.tab_quick"));
        styleTabButton.setMessage(tabLabel(1, "screen.minescreen.traffic.tab_manual"));
        stationTabButton.setMessage(tabLabel(2, "screen.minescreen.traffic.tab_advanced"));
        updateModeButtons();
        setInitialFocus(setupPage ? platform ? manualModeButton : nextModeButton
                : contentPage ? destination : platform ? trainType : stationTurnaround);
    }

    @Override
    public void tick() {
        super.tick();
        if (page == 0) {
            boolean needsStation = requiresPlatformStation();
            clearBindingButton.active = needsStation && !stationBinding.getValue().isBlank();
            bindStationButton.setMessage(setupActionLabel());
            stationAliasButton.active =
                    !TrafficTemplateRepository.stationProfiles(template.getValue()).isEmpty();
            stationAliasButton.setMessage(aliasButtonLabel());
        }
        if (page == 1) {
            clearOverlayButton.active = !overlayTemplate.getValue().isBlank();
        }
        refreshStationSuggestions();
    }

    private void selectPage(int requestedPage) {
        page = Math.max(0, Math.min(2, requestedPage));
        error = Component.empty();
        messageColor = 0xFFFF6B6B;
        showPage();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        renderResponsive(graphics, mouseX, mouseY, partialTick,
                (logicalX, logicalY, tick) -> renderLayer(graphics, logicalX, logicalY, tick));
    }

    private void renderLayer(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int inner = panelWidth - 32;
        int half = (inner - 8) / 2;
        graphics.fillGradient(left, top, left + panelWidth, top + PANEL_HEIGHT,
                0xFF1A202B, 0xFF0F141D);
        graphics.fill(left, top, left + 4, top + PANEL_HEIGHT, 0xFFFFA726);
        graphics.fill(left + 4, top + 32, left + panelWidth, top + 33, 0xFF394657);
        graphics.drawString(font, title, left + 16, top + 11, 0xFFF4F7FB, false);
        if (backSide) {
            graphics.drawString(font, Component.translatable("screen.minescreen.back.editing"),
                    left + 170, top + 11, 0xFFFFD43B, false);
        }
        if (page == 0) {
            drawLabel(graphics, "screen.minescreen.traffic.station_mode", left + 16, top + 68);
            drawLabel(graphics, requiresPlatformStation()
                    ? "screen.minescreen.traffic.station_binding"
                    : "screen.minescreen.traffic.content_source", left + 16, top + 106);
            drawLabel(graphics, "screen.minescreen.traffic.template", left + 16, top + 140);
            renderQuickSetupGuide(graphics, inner);
        } else if (page == 1) {
            drawLabel(graphics, "screen.minescreen.traffic.line", left + 16, top + 68);
            drawLabel(graphics, "screen.minescreen.traffic.destination", left + 24 + half,
                    top + 68);
            drawLabel(graphics, "screen.minescreen.traffic.current", left + 16, top + 102);
            drawLabel(graphics, "screen.minescreen.traffic.next", left + 24 + half, top + 102);
            drawLabel(graphics, "screen.minescreen.traffic.eta", left + 16, top + 136);
            drawLabel(graphics, "screen.minescreen.traffic.status", left + 24 + half, top + 136);
            drawLabel(graphics, "screen.minescreen.traffic.overlay_template",
                    left + 16, top + 170);
            renderManualPageHelp(graphics, inner);
        } else {
            drawLabel(graphics, "screen.minescreen.text_display.text_color", left + 16, top + 68);
            drawLabel(graphics, "screen.minescreen.text_display.background_color", left + 24 + half,
                    top + 68);
            if (trafficRole == TrafficDisplayRole.PLATFORM) {
                drawLabel(graphics, "screen.minescreen.traffic.station_train_type",
                        left + 16, top + 136);
            }
            drawLabel(graphics, "screen.minescreen.traffic.station_turnaround",
                    trafficRole == TrafficDisplayRole.PLATFORM ? left + 24 + half : left + 16,
                    top + 136);
            if (trafficRole == TrafficDisplayRole.PLATFORM) {
                drawLabel(graphics, "screen.minescreen.traffic.station_map_template",
                        left + 16, top + 170);
            }
            renderAdvancedHelp(graphics, inner);
        }
        if (!error.getString().isEmpty()) {
            drawWrapped(graphics, error, left + 16, top + 232, inner, messageColor, 2);
        }
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children()) {
            if (child instanceof net.minecraft.client.gui.components.Renderable renderable) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        renderStationSuggestions(graphics, mouseX, mouseY);
        graphics.flush();
    }

    private void renderStationSuggestions(GuiGraphics graphics, int mouseX, int mouseY) {
        refreshStationSuggestions();
        if (suggestionTarget == null || !suggestionsVisibleOnCurrentPage(suggestionTarget)
                || stationSuggestions.isEmpty()) return;
        int popupX = suggestionTarget.getX();
        int popupY = suggestionTarget.getY() + suggestionTarget.getHeight() + 1;
        int popupWidth = suggestionTarget.getWidth();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        graphics.fill(popupX - 1, popupY - 1, popupX + popupWidth + 1,
                popupY + stationSuggestions.size() * 14 + 1, 0xFF0A0F16);
        for (int index = 0; index < stationSuggestions.size(); index++) {
            int rowY = popupY + index * 14;
            boolean hovered = mouseX >= popupX && mouseX < popupX + popupWidth
                    && mouseY >= rowY && mouseY < rowY + 14;
            int background = index == selectedSuggestion ? 0xFF294765
                    : hovered ? 0xFF203247 : 0xFF121B26;
            graphics.fill(popupX, rowY, popupX + popupWidth, rowY + 13, background);
            String label = font.plainSubstrByWidth(stationSuggestions.get(index).label(),
                    popupWidth - 10);
            graphics.drawString(font, label, popupX + 5, rowY + 3,
                    index == selectedSuggestion ? 0xFFF4F7FB : 0xFFC4D0DE, false);
        }
        graphics.pose().popPose();
    }

    private void drawLabel(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(key), x, y, 0xFF9EB0C4, false);
    }

    private void renderQuickSetupGuide(GuiGraphics graphics, int inner) {
        int cardTop = top + 177;
        int cardBottom = top + 229;
        graphics.fill(left + 16, cardTop, left + 16 + inner, cardBottom, 0xFF151D28);
        graphics.fill(left + 16, cardTop, left + 20, cardBottom, workflowColor());
        drawWrapped(graphics, Component.translatable("screen.minescreen.traffic.quick_mode_status",
                        modeDescription()),
                left + 27, cardTop + 4, inner - 22, 0xFFF4F7FB, 1);
        Component stationState;
        if (trafficRole == TrafficDisplayRole.ONBOARD) {
            stationState = Component.translatable("screen.minescreen.traffic.quick_carriage_source");
        } else if (stationMode == StationDisplayMode.MANUAL) {
            stationState = Component.translatable("screen.minescreen.traffic.quick_station_unused");
        } else if (stationBinding.getValue().isBlank()) {
            stationState = Component.translatable("screen.minescreen.traffic.quick_station_action");
        } else {
            stationState = Component.translatable("screen.minescreen.traffic.station_binding_current",
                    stationBinding.getValue());
        }
        drawWrapped(graphics, stationState, left + 27, cardTop + 17, inner - 22,
                trafficRole == TrafficDisplayRole.ONBOARD
                        || stationMode == StationDisplayMode.MANUAL
                        || !stationBinding.getValue().isBlank()
                        ? 0xFF62E6A7 : 0xFFFFC26B, 1);
        int stationCount = TrafficTemplateRepository.stationProfiles(template.getValue()).size();
        Component templateState = stationCount == 0
                ? Component.translatable("screen.minescreen.traffic.quick_template_builtin",
                        template.getValue())
                : Component.translatable("screen.minescreen.traffic.quick_template_imported",
                        template.getValue(), stationCount);
        drawWrapped(graphics, templateState, left + 27, cardTop + 30, inner - 22,
                0xFF9EB0C4, 1);
        drawWrapped(graphics, nextAction(), left + 27, cardTop + 43, inner - 22,
                workflowReady() ? 0xFF62E6A7 : 0xFFFFD166, 1);
    }

    private void renderManualPageHelp(GuiGraphics graphics, int inner) {
        Component help = !overlayTemplate.getValue().isBlank()
                ? Component.translatable("screen.minescreen.traffic.overlay_active_help")
                : trafficRole == TrafficDisplayRole.ONBOARD
                ? Component.translatable("screen.minescreen.traffic.onboard_fallback_help")
                : stationMode == StationDisplayMode.MANUAL
                ? Component.translatable("screen.minescreen.traffic.manual_active_help")
                : Component.translatable("screen.minescreen.traffic.manual_fallback_help");
        drawWrapped(graphics, help, left + 16, top + 218, inner,
                !overlayTemplate.getValue().isBlank()
                        || trafficRole == TrafficDisplayRole.ONBOARD
                        || stationMode == StationDisplayMode.MANUAL
                                ? 0xFF62E6A7 : 0xFF9EB0C4, 1);
    }

    private void renderAdvancedHelp(GuiGraphics graphics, int inner) {
        int cardTop = top + 207;
        graphics.fill(left + 16, cardTop, left + 16 + inner, top + 229, 0xFF151D28);
        graphics.fill(left + 16, cardTop, left + 20, top + 229, 0xFF8C6DFF);
        drawWrapped(graphics, Component.translatable(trafficRole == TrafficDisplayRole.ONBOARD
                        ? "screen.minescreen.traffic.onboard_advanced_help"
                        : "screen.minescreen.traffic.advanced_help"),
                left + 27, cardTop + 4, inner - 22, 0xFFC7D3E1, 2);
    }

    private boolean workflowReady() {
        return trafficRole == TrafficDisplayRole.ONBOARD
                || stationMode == StationDisplayMode.MANUAL
                || TrafficStationLocator.available() && !stationBinding.getValue().isBlank();
    }

    private int workflowColor() {
        if (workflowReady()) return 0xFF42C98A;
        if (!TrafficStationLocator.available()) return 0xFFFF6B6B;
        return 0xFFFFB74D;
    }

    private Component modeDescription() {
        if (trafficRole == TrafficDisplayRole.ONBOARD) {
            return Component.translatable("screen.minescreen.traffic.mode_onboard_auto");
        }
        return switch (stationMode) {
            case MANUAL -> Component.translatable("screen.minescreen.traffic.mode_manual");
            case STATION_NEXT -> Component.translatable("screen.minescreen.traffic.mode_next");
            case STATION_MAP -> Component.translatable("screen.minescreen.traffic.mode_map");
            case TEXT_SEQUENCE -> Component.translatable("screen.minescreen.traffic.mode_manual");
        };
    }

    private Component nextAction() {
        if (trafficRole == TrafficDisplayRole.ONBOARD) {
            return Component.translatable("screen.minescreen.traffic.quick_ready_carriage");
        }
        if (stationMode == StationDisplayMode.MANUAL) {
            return Component.translatable("screen.minescreen.traffic.quick_next_manual");
        }
        if (!TrafficStationLocator.available()) {
            return Component.translatable("screen.minescreen.traffic.quick_next_create_missing");
        }
        if (stationBinding.getValue().isBlank()) {
            return Component.translatable("screen.minescreen.traffic.quick_next_bind");
        }
        return Component.translatable("screen.minescreen.traffic.quick_ready");
    }

    private void drawWrapped(GuiGraphics graphics, Component text, int x, int y, int maximumWidth,
            int color, int maximumLines) {
        var lines = font.split(text, Math.max(1, maximumWidth));
        int count = Math.min(Math.max(1, maximumLines), lines.size());
        for (int index = 0; index < count; index++) {
            graphics.drawString(font, lines.get(index), x, y + index * font.lineHeight, color,
                    false);
        }
    }

    private void cycleAnimation() {
        TextDisplayAnimation[] values = {TextDisplayAnimation.STATIC, TextDisplayAnimation.MARQUEE,
                TextDisplayAnimation.PULSE, TextDisplayAnimation.ALERT};
        animation = values[(indexOf(values, animation) + 1) % values.length];
        animationButton.setMessage(animationLabel());
    }

    private void importTemplate() {
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(5);
            filters.put(stack.UTF8("*.json"));
            filters.put(stack.UTF8("*.rmp"));
            filters.put(stack.UTF8("*.js"));
            filters.put(stack.UTF8("*.png"));
            filters.put(stack.UTF8("*.txt")).flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("screen.minescreen.traffic.import_template").getString(),
                    "", filters, "RMP / JSON / JavaScript / PNG / dynamic UTF-8 TXT", false);
        }
        if (selected == null || selected.isBlank()) return;
        try {
            TrafficImportResult result = TrafficTemplateRepository.importTemplate(Path.of(selected));
            if (result.sourceKind().equals("text")) {
                overlayTemplate.setValue(result.templateId());
                error = Component.translatable(
                        "screen.minescreen.traffic.import_overlay_success", result.lineCount());
            } else {
                template.setValue(result.templateId());
                applyImportedDefaults(result.defaults());
                error = Component.translatable("screen.minescreen.traffic.import_success",
                        result.sourceKind(), result.stationCount(), result.lineCount());
            }
            messageColor = 0xFF62E6A7;
            showPage();
        } catch (Exception exception) {
            error = Component.translatable("screen.minescreen.traffic.import_failed",
                    exception.getMessage() == null ? "invalid manifest" : exception.getMessage());
            messageColor = 0xFFFF6B6B;
        }
    }

    private void importOverlay() {
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.txt")).flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable(
                            "screen.minescreen.traffic.import_overlay").getString(),
                    "", filters, "UTF-8 dynamic text document", false);
        }
        if (selected == null || selected.isBlank()) return;
        try {
            TrafficImportResult result =
                    TrafficTemplateRepository.importTemplate(Path.of(selected));
            if (!result.sourceKind().equals("text")) {
                throw new java.io.IOException("Please select a UTF-8 .txt document");
            }
            overlayTemplate.setValue(result.templateId());
            error = Component.translatable("screen.minescreen.traffic.import_overlay_success",
                    result.lineCount());
            messageColor = 0xFF62E6A7;
            showPage();
        } catch (Exception exception) {
            error = Component.translatable("screen.minescreen.traffic.import_failed",
                    exception.getMessage() == null ? "invalid TXT document"
                            : exception.getMessage());
            messageColor = 0xFFFF6B6B;
        }
    }

    private void clearOverlay() {
        overlayTemplate.setValue("");
        error = Component.translatable("screen.minescreen.traffic.overlay_cleared");
        messageColor = 0xFF62E6A7;
        clearOverlayButton.active = false;
    }

    private void openStationAliases() {
        Minecraft minecraft = Minecraft.getInstance();
        if (TrafficTemplateRepository.stationProfiles(template.getValue()).isEmpty()) {
            error = Component.translatable("screen.minescreen.station_alias.no_stations");
            messageColor = 0xFFFFC26B;
            return;
        }
        minecraft.setScreen(new StationAliasEditorScreen(template.getValue(), minecraft.level,
                pos, this));
    }

    private void applyImportedDefaults(TrafficImportResult.Defaults defaults) {
        if (!defaults.present()) return;
        line.setValue(defaults.line());
        destination.setValue(defaults.destination());
        current.setValue(defaults.currentStop());
        next.setValue(defaults.nextStop());
        eta.setValue(defaults.eta());
        status.setValue(defaults.status());
    }

    private void cycleFontSize() {
        fontSize = switch (fontSize) {
            case 25 -> 50; case 50 -> 75; case 75 -> 100; case 100 -> 125;
            case 125 -> 150; case 150 -> 200; default -> 25;
        };
        fontButton.setMessage(fontLabel());
    }

    private void cycleBackMode() {
        backMode = backMode.next();
        backModeButton.setMessage(backModeLabel());
    }

    private void cycleTrafficRole() {
        if (movingEntityId >= 0) return;
        trafficRole = trafficRole == TrafficDisplayRole.PLATFORM
                ? TrafficDisplayRole.ONBOARD : TrafficDisplayRole.PLATFORM;
        if (trafficRole == TrafficDisplayRole.ONBOARD) {
            stationMode = StationDisplayMode.STATION_NEXT;
            error = Component.translatable("screen.minescreen.traffic.role_changed_onboard");
        } else {
            error = Component.translatable("screen.minescreen.traffic.role_changed_platform");
        }
        messageColor = 0xFF4AA8FF;
        roleButton.setMessage(roleLabel());
        showPage();
    }

    private void selectStationMode(StationDisplayMode selected) {
        stationMode = selected == null ? StationDisplayMode.MANUAL : selected;
        if (stationMode == StationDisplayMode.STATION_MAP && mapTemplate.getValue().isBlank()) {
            mapTemplate.setValue("builtin_transit");
        }
        updateModeButtons();
        error = stationMode == StationDisplayMode.MANUAL
                ? Component.translatable("screen.minescreen.traffic.mode_changed_manual")
                : Component.translatable("screen.minescreen.traffic.mode_changed_live");
        messageColor = stationMode == StationDisplayMode.MANUAL ? 0xFF62E6A7 : 0xFF4AA8FF;
        showPage();
    }

    private void updateModeButtons() {
        if (manualModeButton == null) return;
        manualModeButton.setMessage(modeLabel(StationDisplayMode.MANUAL,
                "screen.minescreen.traffic.mode_manual"));
        nextModeButton.setMessage(modeLabel(StationDisplayMode.STATION_NEXT,
                "screen.minescreen.traffic.mode_next"));
        mapModeButton.setMessage(modeLabel(StationDisplayMode.STATION_MAP,
                "screen.minescreen.traffic.mode_map"));
        nextModeButton.setMessage(modeLabel(StationDisplayMode.STATION_NEXT,
                trafficRole == TrafficDisplayRole.ONBOARD
                        ? "screen.minescreen.traffic.mode_onboard_auto"
                        : "screen.minescreen.traffic.mode_next"));
    }

    private void bindNearestStation() {
        if (trafficRole == TrafficDisplayRole.ONBOARD) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        if (!TrafficStationLocator.available()) {
            error = Component.translatable("screen.minescreen.traffic.create_missing");
            messageColor = 0xFFFF6B6B;
            return;
        }
        TrafficStationLocator.StationMatch match = TrafficStationLocator.resolveNearby(
                minecraft.level, pos, "", MineScreenConfig.CREATE_STATION_SCAN_RADIUS.get());
        if (match == null) {
            error = Component.translatable("screen.minescreen.traffic.auto_bind_not_found");
            messageColor = 0xFFFF6B6B;
            return;
        }
        stationBinding.setValue(match.createStationName());
        clearBindingButton.active = true;
        // The common platform-board workflow should take one click. A newly placed/manual board
        // is switched to live departures automatically; users who selected the map mode retain it.
        if (stationMode == StationDisplayMode.MANUAL) {
            selectStationMode(StationDisplayMode.STATION_NEXT);
        }
        CreateTrainScheduleService.Snapshot snapshot = CreateTrainScheduleService.snapshot(
                minecraft.level, match.position(), trainType.getValue(), 4);
        if (snapshot.stationName().isBlank()) {
            error = Component.translatable("screen.minescreen.traffic.auto_bind_syncing",
                    match.createStationName());
            messageColor = 0xFF4AA8FF;
        } else if (!snapshot.departures().isEmpty()) {
            error = Component.translatable("screen.minescreen.traffic.auto_bind_test_success",
                    match.createStationName(), snapshot.departures().size());
            messageColor = 0xFF62E6A7;
        } else {
            error = Component.translatable("screen.minescreen.traffic.auto_bind_test_empty",
                    match.createStationName()).append(" ").append(departureDiagnostic(snapshot));
            messageColor = 0xFFFFC26B;
        }
    }

    private void clearStationBinding() {
        stationBinding.setValue("");
        clearBindingButton.active = false;
        error = Component.translatable("screen.minescreen.traffic.binding_cleared");
        messageColor = 0xFFFFC26B;
        refreshStationSuggestions();
    }

    private Component departureDiagnostic(CreateTrainScheduleService.Snapshot snapshot) {
        return switch (snapshot.departureState(trainType.getValue())) {
            case NO_TRAINS -> Component.translatable("screen.minescreen.station.no_trains");
            case NO_ACTIVE_SCHEDULES -> Component.translatable(
                    "screen.minescreen.station.no_active_schedules");
            case FILTER_NO_MATCH -> Component.translatable(
                    "screen.minescreen.station.filter_no_match", trainType.getValue());
            case NO_STATION_PREDICTIONS, AVAILABLE -> Component.translatable(
                    "screen.minescreen.station.no_station_predictions");
        };
    }

    private boolean save(boolean close) {
        if (!prepareWorkflowForSave()) return false;
        if (!valid(line) || !valid(destination) || !valid(current) || !valid(next) || !valid(eta)
                || !valid(status) || !valid(template) || !valid(overlayTemplate)
                || !valid(trainType)
                || !valid(mapTemplate) || !valid(stationBinding)
                || !valid(stationTurnaround)) {
            error = Component.translatable("screen.minescreen.traffic.error_field");
            messageColor = 0xFFFF6B6B;
            return false;
        }
        Integer foreground = parseColorStrict(textColor.getValue());
        Integer background = parseColorStrict(backgroundColor.getValue());
        if (foreground == null || background == null) {
            error = Component.translatable("screen.minescreen.text_display.error_color");
            messageColor = 0xFFFF6B6B;
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return false;
        if (movingEntityId >= 0) {
            PacketDistributor.sendToServer(new MovingTrafficDisplayUpdatePayload(movingEntityId,
                    pos, line.getValue(), destination.getValue(), current.getValue(),
                    next.getValue(), eta.getValue(), status.getValue(), template.getValue(),
                    overlayTemplate.getValue(), foreground, background, animation, 1.0F,
                    fontSize, backSide, backMode,
                    trafficRole, stationMode, savedStationBinding(), trainType.getValue(),
                    stationTurnaround.getValue(), mapTemplate.getValue()));
        } else {
            PacketDistributor.sendToServer(new TrafficDisplayUpdatePayload(
                    minecraft.level.dimension().location(), pos, line.getValue(),
                    destination.getValue(), current.getValue(), next.getValue(), eta.getValue(),
                    status.getValue(), template.getValue(), overlayTemplate.getValue(),
                    foreground, background, animation, 1.0F, fontSize, backSide, backMode,
                    trafficRole, stationMode,
                    savedStationBinding(),
                    trainType.getValue(), stationTurnaround.getValue(), mapTemplate.getValue()));
        }
        if (close) onClose();
        return true;
    }

    /**
     * Live fixed boards are made deterministic at save time. Leaving the station field blank is a
     * useful shorthand, but persisting an explicit nearby Create name prevents a board from
     * switching stations later when another platform is built inside the scan radius.
     */
    private boolean prepareWorkflowForSave() {
        if (template.getValue().isBlank()) template.setValue("builtin_transit");
        if (stationMode == StationDisplayMode.TEXT_SEQUENCE) {
            stationMode = trafficRole == TrafficDisplayRole.ONBOARD
                    ? StationDisplayMode.STATION_NEXT : StationDisplayMode.MANUAL;
        }
        if (trafficRole == TrafficDisplayRole.ONBOARD
                || stationMode == StationDisplayMode.MANUAL) return true;
        if (!TrafficStationLocator.available()) {
            page = 0;
            showPage();
            error = Component.translatable("screen.minescreen.traffic.quick_next_create_missing");
            messageColor = 0xFFFF6B6B;
            return false;
        }
        if (!stationBinding.getValue().isBlank()) return true;
        Minecraft minecraft = Minecraft.getInstance();
        TrafficStationLocator.StationMatch match = minecraft.level == null ? null
                : TrafficStationLocator.resolveNearby(minecraft.level, pos, "",
                        MineScreenConfig.CREATE_STATION_SCAN_RADIUS.get());
        if (match == null) {
            page = 0;
            showPage();
            error = Component.translatable("screen.minescreen.traffic.save_needs_station");
            messageColor = 0xFFFF6B6B;
            return false;
        }
        stationBinding.setValue(match.createStationName());
        return true;
    }

    private boolean valid(MineScreenEditBox box) {
        return TextDisplayBlockEntity.validTrafficField(box.getValue());
    }

    private Component animationLabel() {
        return Component.translatable("screen.minescreen.text_display.animation",
                Component.translatable("screen.minescreen.text_display.animation."
                        + animation.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private Component fontLabel() {
        return Component.translatable("screen.minescreen.text_display.font_size", fontSize);
    }

    private Component tabLabel(int tab, String key) {
        return Component.literal(page == tab ? "▶ " : "").append(Component.translatable(key));
    }

    private Component modeLabel(StationDisplayMode mode, String key) {
        return Component.literal(stationMode == mode ? "✓ " : "").append(Component.translatable(key));
    }

    private Component aliasButtonLabel() {
        int count = TrafficTemplateRepository.stationProfiles(template.getValue()).size();
        return count == 0
                ? Component.translatable("screen.minescreen.station_alias.short")
                : Component.translatable("screen.minescreen.station_alias.short_count", count);
    }

    private Component setupActionLabel() {
        if (stationMode == StationDisplayMode.MANUAL) {
            return Component.translatable("screen.minescreen.traffic.manual_no_binding");
        }
        return Component.translatable(trafficRole == TrafficDisplayRole.ONBOARD
                ? "screen.minescreen.traffic.onboard_auto_status"
                : "screen.minescreen.traffic.scan_bind");
    }

    private boolean requiresPlatformStation() {
        return trafficRole == TrafficDisplayRole.PLATFORM
                && (stationMode == StationDisplayMode.STATION_NEXT
                        || stationMode == StationDisplayMode.STATION_MAP);
    }

    private String savedStationBinding() {
        return trafficRole == TrafficDisplayRole.ONBOARD ? "" : stationBinding.getValue();
    }

    private Component roleLabel() {
        Component role = Component.translatable(trafficRole == TrafficDisplayRole.ONBOARD
                ? "screen.minescreen.traffic.role.onboard"
                : "screen.minescreen.traffic.role.platform");
        return Component.translatable(movingEntityId >= 0
                ? "screen.minescreen.traffic.role_locked"
                : "screen.minescreen.traffic.role", role);
    }

    private Component backModeLabel() {
        return Component.translatable("screen.minescreen.back.mode",
                Component.translatable("screen.minescreen.back.mode."
                        + backMode.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private static int indexOf(TextDisplayAnimation[] values, TextDisplayAnimation target) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == target) return index;
        }
        return 0;
    }

    private record StationSuggestion(String value, String label) {
    }

    private static String colorString(int color) { return String.format("#%08X", color); }
    private static int parseColor(String value, int fallback) {
        Integer parsed = parseColorStrict(value);
        return parsed == null ? fallback : parsed;
    }
    private static Integer parseColorStrict(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("#")) normalized = normalized.substring(1);
        if (normalized.length() == 6) normalized = "FF" + normalized;
        if (normalized.length() != 8) return null;
        try { return (int) Long.parseLong(normalized, 16); }
        catch (NumberFormatException ignored) { return null; }
    }
}
