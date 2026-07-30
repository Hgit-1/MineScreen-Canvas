package dev.minescreen.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.client.compat.MovingStructureCompat;
import dev.minescreen.client.traffic.TrafficStationLocator;
import dev.minescreen.client.traffic.TrafficTemplateRepository;
import dev.minescreen.client.traffic.TrafficTemplateRepository.StationProfile;
import dev.minescreen.client.ui.MineScreenUiRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/**
 * One-station-at-a-time wizard that links imported LCD codes to Create's exact internal station
 * names. Direction-specific platforms intentionally remain separate mappings.
 */
public final class StationAliasEditorScreen extends ResponsiveMineScreen {
    private final String templateId;
    private final Level level;
    private final BlockPos displayPos;
    private final Screen parent;
    private final List<StationProfile> stations;
    private final Map<String, String> bindings;
    private final List<String> createSuggestions;
    private MineScreenEditBox createName;
    private net.minecraft.client.gui.components.Button previousButton;
    private net.minecraft.client.gui.components.Button nextButton;
    private net.minecraft.client.gui.components.Button suggestionButton;
    private net.minecraft.client.gui.components.Button autoMatchButton;
    private int index;
    private int suggestionIndex;
    private int left;
    private int top;
    private int panelWidth;
    private Component message = Component.empty();
    private int messageColor = 0xFFFFC26B;

    public StationAliasEditorScreen(String templateId, Level level, BlockPos displayPos,
            Screen parent) {
        super(Component.translatable("screen.minescreen.station_alias.title"));
        this.templateId = templateId == null ? "" : templateId.trim();
        this.level = level;
        this.displayPos = displayPos == null ? BlockPos.ZERO : displayPos.immutable();
        this.parent = parent;
        this.stations = TrafficTemplateRepository.stationProfiles(this.templateId);
        this.bindings = new LinkedHashMap<>(
                TrafficTemplateRepository.metadata(this.templateId).stationBindings());
        this.createSuggestions = collectSuggestions();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        configureResponsiveLayout(576, 310);
        panelWidth = Math.min(560, layoutWidth() - 16);
        left = (layoutWidth() - panelWidth) / 2;
        top = Math.max(8, (layoutHeight() - 286) / 2);
        int inner = panelWidth - 32;
        createName = new MineScreenEditBox(font, left + 16, top + 104, inner, 20,
                Component.translatable("screen.minescreen.station_alias.create_name"));
        createName.setMaxLength(128);
        addRenderableWidget(createName);
        previousButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.station_alias.previous"),
                button -> changeStation(-1), left + 16, top + 142, 92, 20));
        suggestionButton = addRenderableWidget(MineScreenButton.create(Component.empty(),
                button -> useSuggestion(), left + 116, top + 142, inner - 216, 20));
        nextButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.station_alias.next"),
                button -> changeStation(1), left + panelWidth - 108, top + 142, 92, 20));
        autoMatchButton = addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.station_alias.auto_match_all"),
                button -> autoMatchAll(), left + 16, top + 170, inner, 20));
        int half = (inner - 8) / 2;
        addRenderableWidget(MineScreenButton.create(
                Component.translatable("screen.minescreen.station_alias.save"),
                button -> saveAndClose(), left + 16, top + 254, half, 20));
        addRenderableWidget(MineScreenButton.create(Component.translatable("gui.cancel"),
                button -> minecraft.setScreen(parent), left + 24 + half, top + 254, half, 20));
        loadStation();
    }

    private List<String> collectSuggestions() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (TrafficStationLocator.StationMatch station : TrafficStationLocator.nearbyStations(
                level, displayPos, MineScreenConfig.CREATE_STATION_SCAN_RADIUS.get())) {
            if (!station.createStationName().isBlank()) values.add(station.createStationName());
        }
        MovingStructureCompat.CarriageTrainStatus live =
                MovingStructureCompat.carriageTrainStatus(level);
        if (live != null) {
            if (!live.stationName().isBlank()) values.add(live.stationName());
            values.addAll(live.upcomingStops());
            if (!live.terminalStation().isBlank()) values.add(live.terminalStation());
        }
        return List.copyOf(values);
    }

    private void loadStation() {
        boolean available = !stations.isEmpty();
        createName.visible = available;
        previousButton.active = available && index > 0;
        nextButton.active = available && index + 1 < stations.size();
        suggestionButton.active = available && !rankedSuggestions().isEmpty();
        autoMatchButton.active = available && !createSuggestions.isEmpty();
        if (!available) {
            message = Component.translatable("screen.minescreen.station_alias.no_stations");
            messageColor = 0xFFFF6B6B;
            suggestionButton.setMessage(Component.translatable(
                    "screen.minescreen.station_alias.no_suggestion"));
            return;
        }
        StationProfile station = stations.get(index);
        createName.setValue(bindings.getOrDefault(station.code(), ""));
        suggestionIndex = 0;
        updateSuggestion();
        setInitialFocus(createName);
    }

    private void saveCurrent() {
        if (stations.isEmpty()) return;
        String code = stations.get(index).code();
        String value = createName.getValue().trim();
        if (value.isBlank()) bindings.remove(code);
        else bindings.put(code, value);
    }

    private void changeStation(int delta) {
        saveCurrent();
        index = Math.max(0, Math.min(stations.size() - 1, index + delta));
        loadStation();
    }

    private void useSuggestion() {
        List<String> suggestions = rankedSuggestions();
        if (suggestions.isEmpty()) return;
        createName.setValue(suggestions.get(Math.floorMod(suggestionIndex, suggestions.size())));
        suggestionIndex = (suggestionIndex + 1) % suggestions.size();
        updateSuggestion();
    }

    /**
     * Applies only unambiguous name matches. Codes such as C1 cannot safely be guessed as an
     * unrelated Create name, so those entries remain for the user instead of silently producing a
     * wrong route.
     */
    private void autoMatchAll() {
        saveCurrent();
        int changed = 0;
        for (StationProfile station : stations) {
            if (!bindings.getOrDefault(station.code(), "").isBlank()) continue;
            List<String> ranked = rankedSuggestions(station);
            if (ranked.isEmpty()) continue;
            int bestScore = suggestionScore(normalize(station.code() + station.displayName()),
                    normalize(ranked.getFirst()));
            if (bestScore > 1) continue;
            long sameScore = ranked.stream().filter(value -> suggestionScore(
                    normalize(station.code() + station.displayName()), normalize(value))
                    == bestScore).count();
            if (sameScore != 1) continue;
            bindings.put(station.code(), ranked.getFirst());
            changed++;
        }
        loadStation();
        message = Component.translatable("screen.minescreen.station_alias.auto_match_result",
                changed, mappedCount(), stations.size());
        messageColor = changed > 0 ? 0xFF62E6A7 : 0xFFFFC26B;
    }

    private void updateSuggestion() {
        List<String> suggestions = rankedSuggestions();
        if (suggestions.isEmpty()) {
            suggestionButton.setMessage(Component.translatable(
                    "screen.minescreen.station_alias.no_suggestion"));
            createName.setHint(Component.translatable(
                    "screen.minescreen.station_alias.manual_hint"));
            return;
        }
        String suggestion = suggestions.get(Math.floorMod(suggestionIndex, suggestions.size()));
        suggestionButton.setMessage(Component.translatable(
                "screen.minescreen.station_alias.use_suggestion", suggestion));
        createName.setHint(Component.translatable(
                "screen.minescreen.station_alias.suggestion_hint", suggestion));
    }

    private List<String> rankedSuggestions() {
        if (stations.isEmpty()) return List.of();
        return rankedSuggestions(stations.get(index));
    }

    private List<String> rankedSuggestions(StationProfile station) {
        String search = normalize(station.code() + station.displayName());
        List<String> result = new ArrayList<>(createSuggestions);
        result.sort(Comparator
                .comparingInt((String value) -> suggestionScore(search, normalize(value)))
                .thenComparing(String::compareToIgnoreCase));
        return result;
    }

    private int mappedCount() {
        int mapped = 0;
        for (StationProfile station : stations) {
            if (!bindings.getOrDefault(station.code(), "").isBlank()) mapped++;
        }
        return mapped;
    }

    private static int suggestionScore(String target, String candidate) {
        if (target.equals(candidate)) return 0;
        if (!target.isBlank() && (target.contains(candidate) || candidate.contains(target))) {
            return 1;
        }
        return 2;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("station", "").replace("駅", "")
                .replaceAll("[\\s._\\-\\[\\]（）()]+", "");
    }

    private void saveAndClose() {
        saveCurrent();
        try {
            TrafficTemplateRepository.updateStationBindings(templateId, bindings);
            minecraft.setScreen(parent);
        } catch (IOException exception) {
            message = Component.translatable("screen.minescreen.station_alias.save_failed",
                    exception.getMessage() == null ? "I/O" : exception.getMessage());
            messageColor = 0xFFFF6B6B;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        renderResponsive(graphics, mouseX, mouseY, partialTick,
                (logicalX, logicalY, tick) -> renderLayer(graphics, logicalX, logicalY, tick));
    }

    private void renderLayer(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(left, top, left + panelWidth, top + 286,
                0xFF1A202B, 0xFF0F141D);
        graphics.fill(left, top, left + 4, top + 286, 0xFF4AA8FF);
        graphics.drawString(font, title, left + 16, top + 12, 0xFFF4F7FB, false);
        graphics.drawString(font, Component.translatable(
                "screen.minescreen.station_alias.template", templateId),
                left + 16, top + 31, 0xFF9EB0C4, false);
        if (!stations.isEmpty()) {
            StationProfile station = stations.get(index);
            String stationTitle = station.code() + (station.displayName().isBlank()
                    ? "" : "  ·  " + station.displayName());
            graphics.drawString(font, Component.translatable(
                    "screen.minescreen.station_alias.station",
                    index + 1, stations.size(), stationTitle),
                    left + 16, top + 57, 0xFFFFD166, false);
            graphics.drawString(font, Component.translatable(
                    "screen.minescreen.station_alias.mapped_progress",
                    mappedCount(), stations.size()),
                    left + panelWidth - 16 - font.width(Component.translatable(
                            "screen.minescreen.station_alias.mapped_progress",
                            mappedCount(), stations.size())),
                    top + 57, 0xFF62E6A7, false);
            graphics.drawString(font, Component.translatable(
                    "screen.minescreen.station_alias.mapping_explanation"),
                    left + 16, top + 75, 0xFF62E6A7, false);
            graphics.drawString(font, Component.translatable(
                    "screen.minescreen.station_alias.create_name"),
                    left + 16, top + 91, 0xFFB9C8D8, false);
        }
        drawWrapped(graphics, Component.translatable("screen.minescreen.station_alias.help"),
                left + 16, top + 199, panelWidth - 32, 0xFF9EB0C4, 2);
        if (!message.getString().isBlank()) {
            drawWrapped(graphics, message, left + 16, top + 224,
                    panelWidth - 32, messageColor, 2);
        }
        for (var child : children()) {
            if (child instanceof net.minecraft.client.gui.components.Renderable renderable) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.flush();
    }

    private void drawWrapped(GuiGraphics graphics, Component text, int x, int y,
            int maximumWidth, int color, int maximumLines) {
        var lines = font.split(text, Math.max(1, maximumWidth));
        for (int line = 0; line < Math.min(maximumLines, lines.size()); line++) {
            graphics.drawString(font, lines.get(line), x, y + line * font.lineHeight,
                    color, false);
        }
    }
}
