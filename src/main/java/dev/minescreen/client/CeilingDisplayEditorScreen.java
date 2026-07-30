package dev.minescreen.client;

import java.nio.file.Path;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import dev.minescreen.CeilingDisplayBlockEntity;
import dev.minescreen.CeilingDisplayGroup;
import dev.minescreen.CeilingDisplayMode;
import dev.minescreen.DoorLcdGroup;
import dev.minescreen.ScreenGroup;
import dev.minescreen.TextDisplayBlockEntity;
import dev.minescreen.client.ui.MineScreenUiRegistry;
import dev.minescreen.client.traffic.TrafficImportResult;
import dev.minescreen.client.traffic.TrafficTemplateRepository;
import dev.minescreen.network.CeilingDisplayUpdatePayload;
import dev.minescreen.network.MovingCeilingDisplayUpdatePayload;
import dev.minescreen.client.compat.MovingStructureCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Device settings plus a launcher for the existing VIDEO/WEB/VNC editor on either prism face. */
public final class CeilingDisplayEditorScreen extends ResponsiveMineScreen {
    private final CeilingDisplayBlockEntity display;
    private final CeilingDisplayGroup group;
    private final DoorLcdGroup doorGroup;
    private final boolean singleSided;
    private final boolean trafficOnly;
    private final int movingEntityId;
    private int side;
    private boolean linked;
    private CeilingDisplayMode mode;
    private MineScreenEditBox line;
    private MineScreenEditBox destination;
    private MineScreenEditBox nextStop;
    private MineScreenEditBox eta;
    private MineScreenEditBox status;
    private MineScreenEditBox notice;
    private MineScreenEditBox template;
    private MineScreenEditBox overlayTemplate;
    private MineScreenEditBox turnaround;
    private Button sideButton;
    private Button linkedButton;
    private Button modeButton;
    private Button mediaButton;
    private Button importButton;
    private Button importOverlayButton;
    private Button clearOverlayButton;
    private Button stationAliasButton;
    private int left;
    private int top;
    private int panelWidth;
    private Component error = Component.empty();
    private int messageColor = 0xFFFF6B6B;

    public CeilingDisplayEditorScreen(CeilingDisplayBlockEntity display,
            CeilingDisplayGroup group, int side) {
        this(display, group, side, -1);
    }

    public CeilingDisplayEditorScreen(CeilingDisplayBlockEntity display,
            CeilingDisplayGroup group, int side, int movingEntityId) {
        super(Component.translatable("screen.minescreen.ceiling.title"));
        this.display = display;
        this.group = group;
        this.doorGroup = null;
        this.singleSided = false;
        this.trafficOnly = false;
        this.movingEntityId = movingEntityId;
        this.side = side == 1 ? 1 : 0;
        linked = display.linkedSides();
        loadSide();
    }

    public CeilingDisplayEditorScreen(CeilingDisplayBlockEntity display, DoorLcdGroup group) {
        this(display, group, -1);
    }

    public CeilingDisplayEditorScreen(CeilingDisplayBlockEntity display, DoorLcdGroup group,
            int movingEntityId) {
        super(Component.translatable(display.getBlockState()
                .is(dev.minescreen.MineScreen.CARRIAGE_INFO_DISPLAY_BLOCK.get())
                        ? "screen.minescreen.carriage_info.title"
                        : "screen.minescreen.ceiling.title"));
        this.display = display;
        this.group = null;
        this.doorGroup = group;
        this.singleSided = true;
        this.trafficOnly = display.getBlockState()
                .is(dev.minescreen.MineScreen.CARRIAGE_INFO_DISPLAY_BLOCK.get());
        this.movingEntityId = movingEntityId;
        this.side = 0;
        linked = true;
        loadSide();
    }

    private void loadSide() {
        mode = trafficOnly ? CeilingDisplayMode.TRAFFIC : display.mode(linked ? 0 : side);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        configureResponsiveLayout(632, 334);
        panelWidth = Math.min(620, layoutWidth() - 12);
        left = (layoutWidth() - panelWidth) / 2;
        top = Math.max(6, (layoutHeight() - 322) / 2);
        int inner = panelWidth - 28;
        int third = (inner - 8) / 3;
        sideButton = addRenderableWidget(MineScreenButton.create(sideLabel(), b -> cycleSide(),
                left + 14, top + 38, third, 20));
        linkedButton = addRenderableWidget(MineScreenButton.create(linkedLabel(), b -> toggleLinked(),
                left + 18 + third, top + 38, third, 20));
        modeButton = addRenderableWidget(MineScreenButton.create(modeLabel(), b -> toggleMode(),
                left + 22 + third * 2, top + 38, third, 20));
        int half = (inner - 6) / 2;
        line = field(left + 14, top + 82, half,
                editable(display.line(linked ? 0 : side), "MINS"));
        destination = field(left + 20 + half, top + 82, half,
                editable(display.destination(linked ? 0 : side), "Destination"));
        nextStop = field(left + 14, top + 122, half,
                editable(display.nextStop(linked ? 0 : side), "Next stop"));
        eta = field(left + 20 + half, top + 122, half,
                editable(display.eta(linked ? 0 : side), "--"));
        status = field(left + 14, top + 162, half, display.status(linked ? 0 : side));
        int dataSide = linked ? 0 : side;
        String storedTemplate = display.templateId(dataSide);
        String storedOverlay = display.overlayTemplateId(dataSide);
        boolean legacyTextTemplate = storedOverlay.isBlank()
                && TrafficTemplateRepository.resolve(storedTemplate).textSequence().present();
        template = field(left + 20 + half, top + 162, half,
                legacyTextTemplate ? "builtin_transit" : storedTemplate);
        notice = field(left + 14, top + 202, half, display.notice(linked ? 0 : side));
        turnaround = field(left + 20 + half, top + 202, half,
                display.turnaround(linked ? 0 : side));
        overlayTemplate = field(left + 14, top + 202, inner - 172,
                legacyTextTemplate ? storedTemplate : storedOverlay);
        overlayTemplate.setEditable(false);
        configureHints();
        mediaButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.ceiling.edit_media"),
                b -> editMedia(), left + 14, top + 230, inner, 20));
        importButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.traffic.import_template"),
                b -> importTemplate(), left + 14, top + 230, half, 20));
        stationAliasButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.station_alias.open"),
                b -> openStationAliases(), left + 20 + half, top + 230, half, 20));
        importOverlayButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.traffic.import_txt"),
                b -> importOverlay(), left + panelWidth - 172, top + 202, 76, 18));
        clearOverlayButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.traffic.clear_overlay"),
                b -> clearOverlay(), left + panelWidth - 92, top + 202, 78, 18));
        template.setResponder(value -> updateStationAliasButton());
        addRenderableWidget(MineScreenButton.create(Component.translatable("screen.minescreen.save"),
                b -> save(false), left + 14, top + 294, half, 20));
        addRenderableWidget(MineScreenButton.create(Component.translatable("gui.cancel"),
                b -> onClose(), left + 20 + half, top + 294, half, 20));
        updateControls();
    }

    private MineScreenEditBox field(int x, int y, int width, String value) {
        MineScreenEditBox box = new MineScreenEditBox(font, x, y, width, 18, Component.empty());
        box.setMaxLength(TextDisplayBlockEntity.MAX_TRAFFIC_FIELD_LENGTH);
        box.setValue(value == null ? "" : value);
        addRenderableWidget(box);
        return box;
    }

    private void configureHints() {
        line.setHint(Component.translatable("screen.minescreen.ceiling.hint_train_name"));
        destination.setHint(Component.translatable(
                "screen.minescreen.ceiling.hint_manual_terminal"));
        nextStop.setHint(Component.translatable("screen.minescreen.ceiling.hint_next_stop"));
        eta.setHint(Component.translatable("screen.minescreen.ceiling.hint_eta"));
        status.setHint(Component.translatable("screen.minescreen.ceiling.hint_status"));
        template.setHint(Component.translatable("screen.minescreen.ceiling.hint_template"));
        overlayTemplate.setHint(Component.translatable(
                "screen.minescreen.traffic.hint_overlay_template"));
        notice.setHint(Component.translatable("screen.minescreen.ceiling.hint_notice"));
        turnaround.setHint(Component.translatable(
                "screen.minescreen.traffic.hint_station_turnaround"));
    }

    private static String editable(String value, String placeholder) {
        return value == null || value.equalsIgnoreCase(placeholder) ? "" : value;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        renderResponsive(graphics, mouseX, mouseY, partialTick,
                (logicalX, logicalY, tick) -> renderLayer(graphics, logicalX, logicalY, tick));
    }

    private void renderLayer(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(left, top, left + panelWidth, top + 322, 0xFF1A202B, 0xFF0F141D);
        graphics.fill(left, top, left + 4, top + 322, 0xFFFFA726);
        graphics.drawString(font, title, left + 14, top + 12, 0xFFF4F7FB, false);
        if (singleSided) {
            graphics.drawString(font, Component.translatable(trafficOnly
                            ? "screen.minescreen.carriage_info.auto_mode"
                            : "screen.minescreen.ceiling.single_side"),
                    left + 190, top + 12, 0xFF62E6A7, false);
        }
        if (mode == CeilingDisplayMode.TRAFFIC) {
            label(graphics, "screen.minescreen.ceiling.manual_terminal", destination.getX(),
                    top + 70);
            label(graphics, "screen.minescreen.traffic.template", template.getX(), top + 70);
            label(graphics, "screen.minescreen.ceiling.notice", notice.getX(), top + 110);
            label(graphics, "screen.minescreen.traffic.station_turnaround",
                    turnaround.getX(), top + 110);
            label(graphics, "screen.minescreen.traffic.overlay_template",
                    overlayTemplate.getX(), top + 190);
            renderOnboardSource(graphics);
        }
        if (!error.getString().isEmpty()) {
            graphics.drawString(font, error, left + 14, top + 264, messageColor, false);
        } else if (mode == CeilingDisplayMode.TRAFFIC) {
            MovingStructureCompat.CarriageTrainStatus live =
                    MovingStructureCompat.carriageTrainStatus(display.getLevel(),
                            TrafficTemplateRepository.resolvedCreateStationName(
                                    display.templateId(linked ? 0 : side),
                                    display.turnaround(linked ? 0 : side)));
            Component help = live != null && !live.trainName().isBlank()
                    ? Component.translatable("screen.minescreen.ceiling.create_auto_connected",
                            live.trainName(), live.carriageCount(),
                            live.terminalStation().isBlank()
                                    ? Component.translatable(
                                            "screen.minescreen.station.eta_unknown")
                                    : live.terminalStation(),
                            live.stationName().isBlank()
                                    ? Component.translatable(
                                            "screen.minescreen.ceiling.awaiting_route")
                                    : live.stationName())
                    : Component.translatable("screen.minescreen.ceiling.create_auto_help");
            var helpLines = font.split(help,
                    panelWidth - 28);
            for (int index = 0; index < Math.min(1, helpLines.size()); index++) {
                graphics.drawString(font, helpLines.get(index), left + 14,
                        top + 264 + index * font.lineHeight, 0xFF9EB0C4, false);
            }
        }
        for (var child : children()) if (child instanceof net.minecraft.client.gui.components.Renderable r)
            r.render(graphics, mouseX, mouseY, partialTick);
        graphics.flush();
    }

    private void label(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(key), x, y, 0xFF9EB0C4, false);
    }

    private void renderOnboardSource(GuiGraphics graphics) {
        int cardTop = top + 158;
        graphics.fill(left + 14, cardTop, left + panelWidth - 14, top + 190, 0xFF153027);
        graphics.fill(left + 14, cardTop, left + 18, top + 190, 0xFF42C98A);
        graphics.drawString(font, Component.translatable(
                        "screen.minescreen.ceiling.onboard_source_title"),
                left + 26, cardTop + 5, 0xFFE8FFF4, false);
        MovingStructureCompat.CarriageTrainStatus live =
                MovingStructureCompat.carriageTrainStatus(display.getLevel(),
                        TrafficTemplateRepository.resolvedCreateStationName(
                                template.getValue(), turnaround.getValue()));
        Component source = live == null || live.trainName().isBlank()
                ? Component.translatable("screen.minescreen.ceiling.onboard_source_waiting")
                : Component.translatable("screen.minescreen.ceiling.onboard_source_connected",
                        live.trainName());
        graphics.drawString(font, font.plainSubstrByWidth(source.getString(),
                        panelWidth - 52), left + 26, cardTop + 18,
                live == null ? 0xFFFFC26B : 0xFF62E6A7, false);
    }

    private void cycleSide() {
        side = 1 - side;
        loadSide();
        refreshValues();
    }

    private void toggleLinked() { linked = !linked; updateControls(); }
    private void toggleMode() {
        if (trafficOnly) return;
        mode = mode == CeilingDisplayMode.MEDIA
                ? CeilingDisplayMode.TRAFFIC : CeilingDisplayMode.MEDIA;
        updateControls();
    }

    private void refreshValues() {
        int dataSide = linked ? 0 : side;
        line.setValue(editable(display.line(dataSide), "MINS"));
        destination.setValue(editable(display.destination(dataSide), "Destination"));
        nextStop.setValue(editable(display.nextStop(dataSide), "Next stop"));
        eta.setValue(editable(display.eta(dataSide), "--"));
        status.setValue(display.status(dataSide));
        String storedTemplate = display.templateId(dataSide);
        String storedOverlay = display.overlayTemplateId(dataSide);
        boolean legacyTextTemplate = storedOverlay.isBlank()
                && TrafficTemplateRepository.resolve(storedTemplate).textSequence().present();
        template.setValue(legacyTextTemplate ? "builtin_transit" : storedTemplate);
        overlayTemplate.setValue(legacyTextTemplate ? storedTemplate : storedOverlay);
        notice.setValue(display.notice(dataSide));
        turnaround.setValue(display.turnaround(dataSide));
        sideButton.setMessage(sideLabel()); modeButton.setMessage(modeLabel()); updateControls();
    }

    private void updateControls() {
        boolean traffic = mode == CeilingDisplayMode.TRAFFIC;
        // Live train name, next stop, ETA and status are runtime values. Keeping editable boxes
        // for them beside the automatic data source made users believe they had to configure both
        // systems. Only actual onboard overrides remain visible.
        line.visible = nextStop.visible = eta.visible = status.visible = false;
        destination.visible = notice.visible = template.visible = turnaround.visible = traffic;
        overlayTemplate.visible = traffic;
        int inner = panelWidth - 28;
        int half = (inner - 6) / 2;
        destination.setX(left + 14);
        destination.setY(top + 82);
        destination.setWidth(half);
        template.setX(left + 20 + half);
        template.setY(top + 82);
        template.setWidth(half);
        notice.setX(left + 14);
        notice.setY(top + 122);
        notice.setWidth(half);
        turnaround.setX(left + 20 + half);
        turnaround.setY(top + 122);
        turnaround.setWidth(half);
        importButton.setX(left + 14);
        overlayTemplate.setX(left + 14);
        overlayTemplate.setY(top + 202);
        overlayTemplate.setWidth(inner - 172);
        importOverlayButton.setX(left + panelWidth - 172);
        importOverlayButton.setY(top + 202);
        importOverlayButton.setWidth(76);
        clearOverlayButton.setX(left + panelWidth - 92);
        clearOverlayButton.setY(top + 202);
        clearOverlayButton.setWidth(78);
        importButton.setY(top + 230);
        importButton.setWidth(half);
        stationAliasButton.setX(left + 20 + half);
        stationAliasButton.setY(top + 230);
        stationAliasButton.setWidth(half);
        mediaButton.setX(left + 14);
        mediaButton.setY(top + 82);
        mediaButton.setWidth(inner);
        mediaButton.visible = mediaButton.active = !traffic;
        importButton.visible = importButton.active = traffic;
        stationAliasButton.visible = stationAliasButton.active = traffic;
        importOverlayButton.visible = importOverlayButton.active = traffic;
        clearOverlayButton.visible = traffic;
        clearOverlayButton.active = traffic && !overlayTemplate.getValue().isBlank();
        updateStationAliasButton();
        sideButton.setMessage(sideLabel()); linkedButton.setMessage(linkedLabel());
        modeButton.setMessage(modeLabel());
        modeButton.visible = modeButton.active = !trafficOnly;
        sideButton.visible = sideButton.active = !singleSided;
        linkedButton.visible = linkedButton.active = !singleSided;
    }

    private void updateStationAliasButton() {
        if (stationAliasButton == null || template == null) return;
        int total = TrafficTemplateRepository.stationProfiles(template.getValue()).size();
        int mapped = TrafficTemplateRepository.metadata(template.getValue())
                .stationBindings().size();
        stationAliasButton.setMessage(total > 0
                ? Component.translatable("screen.minescreen.station_alias.progress",
                        Math.min(mapped, total), total)
                : Component.translatable("screen.minescreen.station_alias.import_first"));
        stationAliasButton.active = mode == CeilingDisplayMode.TRAFFIC && total > 0;
    }

    private void editMedia() {
        if (!save(true)) return;
        ScreenGroup selectedGroup = singleSided ? doorGroup.screenGroup()
                : group.sideGroup(linked ? 0 : side);
        minecraft.setScreen(new ScreenEditorScreen(selectedGroup, this));
    }

    private void importTemplate() {
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(3);
            filters.put(stack.UTF8("*.json"));
            filters.put(stack.UTF8("*.rmp"));
            filters.put(stack.UTF8("*.js")).flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("screen.minescreen.traffic.import_template").getString(),
                    "", filters, "RMP / MineScreen JSON / JavaScript", false);
        }
        if (selected == null || selected.isBlank()) return;
        try {
            TrafficImportResult result = TrafficTemplateRepository.importTemplate(Path.of(selected));
            template.setValue(result.templateId());
            TrafficImportResult.Defaults defaults = result.defaults();
            if (defaults.present()) {
                line.setValue(defaults.line());
                destination.setValue(defaults.destination());
                nextStop.setValue(defaults.nextStop());
                eta.setValue(defaults.eta());
                status.setValue(defaults.status());
            }
            error = Component.translatable("screen.minescreen.traffic.import_success",
                            result.sourceKind(), result.stationCount(), result.lineCount());
            messageColor = 0xFF62E6A7;
            updateStationAliasButton();
        } catch (Exception exception) {
            error = Component.translatable("screen.minescreen.traffic.import_failed",
                    exception.getMessage() == null ? "invalid template" : exception.getMessage());
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
            updateControls();
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
        if (TrafficTemplateRepository.stationProfiles(template.getValue()).isEmpty()) {
            error = Component.translatable("screen.minescreen.station_alias.no_stations");
            messageColor = 0xFFFFC26B;
            return;
        }
        minecraft.setScreen(new StationAliasEditorScreen(template.getValue(),
                display.getLevel(), display.getBlockPos(), this));
    }

    private boolean save(boolean stayOpen) {
        if (!valid(line) || !valid(destination) || !valid(nextStop) || !valid(eta)
                || !valid(status) || !valid(notice) || !valid(template)
                || !valid(overlayTemplate)
                || !valid(turnaround)) {
            error = Component.translatable("screen.minescreen.traffic.error_field");
            messageColor = 0xFFFF6B6B;
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return false;
        if (movingEntityId >= 0) {
            PacketDistributor.sendToServer(new MovingCeilingDisplayUpdatePayload(movingEntityId,
                    display.getBlockPos(), side, linked, mode, line.getValue(),
                    destination.getValue(), nextStop.getValue(), eta.getValue(),
                    status.getValue(), notice.getValue(), template.getValue(),
                    overlayTemplate.getValue(), turnaround.getValue()));
        } else {
            PacketDistributor.sendToServer(new CeilingDisplayUpdatePayload(
                    minecraft.level.dimension().location(), display.getBlockPos(), side, linked,
                    mode, line.getValue(), destination.getValue(), nextStop.getValue(),
                    eta.getValue(), status.getValue(), notice.getValue(),
                    template.getValue(), overlayTemplate.getValue(), turnaround.getValue()));
        }
        if (!stayOpen) onClose();
        return true;
    }

    private boolean valid(MineScreenEditBox box) {
        return TextDisplayBlockEntity.validTrafficField(box.getValue());
    }

    private Component sideLabel() { return Component.translatable("screen.minescreen.ceiling.side",
            side == 0 ? "A" : "B"); }
    private Component linkedLabel() { return Component.translatable(linked
            ? "screen.minescreen.ceiling.linked" : "screen.minescreen.ceiling.independent"); }
    private Component modeLabel() { return Component.translatable("screen.minescreen.ceiling.mode",
            Component.translatable("screen.minescreen.ceiling.mode."
                    + mode.name().toLowerCase(java.util.Locale.ROOT))); }
}
