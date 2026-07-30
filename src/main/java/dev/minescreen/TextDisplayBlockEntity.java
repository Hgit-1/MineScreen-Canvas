package dev.minescreen;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Authoritative text-board state. No rendered glyphs or animation frames are serialized: clients
 * receive only content, style and a shared game-time origin.
 */
public final class TextDisplayBlockEntity extends BlockEntity {
    public static final int MAX_TEXT_LENGTH = 512;
    public static final int MAX_TRAFFIC_FIELD_LENGTH = 96;
    public static final float MIN_SPEED = 0.1F;
    public static final float MAX_SPEED = 8.0F;
    public static final int MIN_FONT_SIZE = 25;
    public static final int MAX_FONT_SIZE = 200;

    private String text = "MineScreen";
    private int textColor = 0xFFFFFFFF;
    private int backgroundColor = 0xFF101820;
    private TextDisplayAnimation animation;
    private float speed = 1.0F;
    private int fontSize = 100;
    private DisplayBackMode backMode = DisplayBackMode.OFF;
    private String backText = "MineScreen";
    private int backTextColor = 0xFFFFFFFF;
    private int backBackgroundColor = 0xFF101820;
    private TextDisplayAnimation backAnimation = TextDisplayAnimation.STATIC;
    private float backSpeed = 1.0F;
    private int backFontSize = 100;
    private String trafficLine = "MINS";
    private String trafficDestination = "Destination";
    private String trafficCurrentStop = "";
    private String trafficNextStop = "Next stop";
    private String trafficEta = "--";
    private String trafficStatus = "";
    private String templateId = "builtin_transit";
    private String overlayTemplateId = "";
    private String backTrafficLine = "MINS";
    private String backTrafficDestination = "Destination";
    private String backTrafficCurrentStop = "";
    private String backTrafficNextStop = "Next stop";
    private String backTrafficEta = "--";
    private String backTrafficStatus = "";
    private String backTemplateId = "builtin_transit";
    private String backOverlayTemplateId = "";
    private StationDisplayMode stationMode = StationDisplayMode.MANUAL;
    private String stationBindingName = "";
    private String stationTurnaroundName = "";
    private String stationTrainType = "";
    private String stationMapTemplateId = "builtin_transit";
    private StationDisplayMode backStationMode = StationDisplayMode.MANUAL;
    private String backStationBindingName = "";
    private String backStationTurnaroundName = "";
    private String backStationTrainType = "";
    private String backStationMapTemplateId = "builtin_transit";
    private TrafficDisplayRole trafficRole = TrafficDisplayRole.PLATFORM;
    private long animationStartGameTime;
    private long backAnimationStartGameTime;
    @Nullable
    private UUID owner;

    public TextDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(MineScreen.TEXT_DISPLAY_BLOCK_ENTITY.get(), pos, state);
        animation = state.is(MineScreen.ANIMATED_TEXT_DISPLAY_BLOCK.get())
                ? TextDisplayAnimation.MARQUEE : TextDisplayAnimation.STATIC;
        if (state.is(MineScreen.TRAFFIC_DISPLAY_BLOCK.get())) {
            text = "Next stop";
            animation = TextDisplayAnimation.STATIC;
        } else if (state.is(MineScreen.ELECTRIC_DISPLAY_BLOCK.get())) {
            text = "MINE SCREEN";
            textColor = 0xFF62FFF1;
            backgroundColor = 0xFF010807;
            backTextColor = textColor;
            backBackgroundColor = backgroundColor;
            animation = TextDisplayAnimation.MARQUEE;
        }
    }

    public String text() {
        return text;
    }

    public int textColor() {
        return textColor;
    }

    public int backgroundColor() {
        return backgroundColor;
    }

    public TextDisplayAnimation animation() {
        return animation;
    }

    public float speed() {
        return speed;
    }

    public long animationStartGameTime() {
        return animationStartGameTime;
    }
    public long animationStartGameTime(boolean back) {
        return back && backMode == DisplayBackMode.INDEPENDENT
                ? backAnimationStartGameTime : animationStartGameTime;
    }

    public int fontSize() {
        return fontSize;
    }
    public DisplayBackMode backMode() { return backMode; }
    public String text(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backText : text; }
    public int textColor(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backTextColor : textColor; }
    public int backgroundColor(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backBackgroundColor : backgroundColor; }
    public TextDisplayAnimation animation(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backAnimation : animation; }
    public float speed(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backSpeed : speed; }
    public int fontSize(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backFontSize : fontSize; }

    public boolean isTraffic() {
        return getBlockState().is(MineScreen.TRAFFIC_DISPLAY_BLOCK.get());
    }

    public boolean isElectric() {
        return getBlockState().is(MineScreen.ELECTRIC_DISPLAY_BLOCK.get());
    }

    /** Only the text/animated and traffic board families are physically double-sided. */
    public boolean supportsBackSide() {
        return !isElectric();
    }

    public String trafficLine() { return trafficLine; }
    public String trafficDestination() { return trafficDestination; }
    public String trafficCurrentStop() { return trafficCurrentStop; }
    public String trafficNextStop() { return trafficNextStop; }
    public String trafficEta() { return trafficEta; }
    public String trafficStatus() { return trafficStatus; }
    public String templateId() { return templateId; }
    public String trafficLine(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backTrafficLine : trafficLine; }
    public String trafficDestination(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backTrafficDestination : trafficDestination; }
    public String trafficCurrentStop(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backTrafficCurrentStop : trafficCurrentStop; }
    public String trafficNextStop(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backTrafficNextStop : trafficNextStop; }
    public String trafficEta(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backTrafficEta : trafficEta; }
    public String trafficStatus(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backTrafficStatus : trafficStatus; }
    public String templateId(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backTemplateId : templateId; }
    public String overlayTemplateId(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backOverlayTemplateId : overlayTemplateId; }
    public StationDisplayMode stationMode(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backStationMode : stationMode; }
    public String stationBindingName(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backStationBindingName : stationBindingName; }
    public String stationTurnaroundName(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backStationTurnaroundName : stationTurnaroundName; }
    public String stationTrainType(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backStationTrainType : stationTrainType; }
    public String stationMapTemplateId(boolean back) { return back && backMode == DisplayBackMode.INDEPENDENT ? backStationMapTemplateId : stationMapTemplateId; }
    public TrafficDisplayRole trafficRole() { return trafficRole; }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public void setOwnerIfAbsent(UUID playerId) {
        if (owner == null) {
            owner = playerId;
            setChanged();
        }
    }

    public boolean mayEdit(UUID playerId) {
        return owner == null || owner.equals(playerId);
    }

    /** Called only after the server payload handler has validated distance, owner and input. */
    public void applyServerUpdate(String text, int textColor, int backgroundColor,
            TextDisplayAnimation animation, float speed, int fontSize, UUID editor) {
        applyServerUpdate(false, backMode, text, textColor, backgroundColor, animation, speed,
                fontSize, editor);
    }

    public void applyServerUpdate(boolean back, DisplayBackMode backMode, String text,
            int textColor, int backgroundColor, TextDisplayAnimation animation, float speed,
            int fontSize, UUID editor) {
        this.backMode = supportsBackSide() && backMode != null ? backMode : DisplayBackMode.OFF;
        if (back) {
            this.backText = text;
            this.backTextColor = textColor;
            this.backBackgroundColor = backgroundColor;
            this.backAnimation = animation;
            this.backSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
            this.backFontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, fontSize));
            this.backAnimationStartGameTime = level == null ? 0L : level.getGameTime();
            setOwnerIfAbsent(editor);
            syncChanged();
            return;
        }
        this.text = text;
        this.textColor = textColor;
        this.backgroundColor = backgroundColor;
        this.animation = animation;
        this.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        this.fontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, fontSize));
        this.animationStartGameTime = level == null ? 0L : level.getGameTime();
        setOwnerIfAbsent(editor);
        syncChanged();
    }

    public void applyTrafficServerUpdate(String line, String destination, String currentStop,
            String nextStop, String eta, String status, String templateId, int textColor,
            int backgroundColor, TextDisplayAnimation animation, float speed, int fontSize,
            UUID editor) {
        this.trafficLine = line;
        this.trafficDestination = destination;
        this.trafficCurrentStop = currentStop;
        this.trafficNextStop = nextStop;
        this.trafficEta = eta;
        this.trafficStatus = status;
        this.templateId = templateId;
        this.overlayTemplateId = "";
        this.stationMode = StationDisplayMode.MANUAL;
        applyServerUpdate(destination, textColor, backgroundColor, animation, speed, fontSize,
                editor);
    }

    public void applyTrafficServerUpdate(boolean back, DisplayBackMode backMode, String line,
            String destination, String currentStop, String nextStop, String eta, String status,
            String templateId, int textColor, int backgroundColor, TextDisplayAnimation animation,
            float speed, int fontSize, UUID editor) {
        applyTrafficServerUpdate(back, backMode, line, destination, currentStop, nextStop, eta,
                status, templateId, "", StationDisplayMode.MANUAL, "", "", "",
                "builtin_transit", textColor,
                backgroundColor, animation, speed, fontSize, editor);
    }

    public void applyTrafficServerUpdate(boolean back, DisplayBackMode backMode, String line,
            String destination, String currentStop, String nextStop, String eta, String status,
            String templateId, String overlayTemplateId, StationDisplayMode stationMode,
            String stationBindingName,
            String stationTurnaroundName, String stationTrainType, String stationMapTemplateId,
            int textColor, int backgroundColor, TextDisplayAnimation animation, float speed,
            int fontSize, UUID editor) {
        applyTrafficServerUpdate(back, backMode, line, destination, currentStop, nextStop, eta,
                status, templateId, overlayTemplateId, TrafficDisplayRole.PLATFORM, stationMode,
                stationBindingName,
                stationTurnaroundName, stationTrainType, stationMapTemplateId, textColor,
                backgroundColor, animation, speed, fontSize, editor);
    }

    public void applyTrafficServerUpdate(boolean back, DisplayBackMode backMode, String line,
            String destination, String currentStop, String nextStop, String eta, String status,
            String templateId, String overlayTemplateId, TrafficDisplayRole trafficRole,
            StationDisplayMode stationMode,
            String stationBindingName, String stationTurnaroundName, String stationTrainType,
            String stationMapTemplateId, int textColor, int backgroundColor,
            TextDisplayAnimation animation, float speed, int fontSize, UUID editor) {
        this.trafficRole = trafficRole == null ? TrafficDisplayRole.PLATFORM : trafficRole;
        if (back) {
            this.backTrafficLine = line;
            this.backTrafficDestination = destination;
            this.backTrafficCurrentStop = currentStop;
            this.backTrafficNextStop = nextStop;
            this.backTrafficEta = eta;
            this.backTrafficStatus = status;
            this.backTemplateId = templateId;
            this.backOverlayTemplateId = overlayTemplateId == null ? "" : overlayTemplateId;
            this.backStationMode = stationMode == null ? StationDisplayMode.MANUAL : stationMode;
            this.backStationBindingName = stationBindingName == null ? "" : stationBindingName;
            this.backStationTurnaroundName = stationTurnaroundName == null
                    ? "" : stationTurnaroundName;
            this.backStationTrainType = stationTrainType == null ? "" : stationTrainType;
            this.backStationMapTemplateId = stationMapTemplateId == null ? "builtin_transit" : stationMapTemplateId;
        } else {
            this.trafficLine = line;
            this.trafficDestination = destination;
            this.trafficCurrentStop = currentStop;
            this.trafficNextStop = nextStop;
            this.trafficEta = eta;
            this.trafficStatus = status;
            this.templateId = templateId;
            this.overlayTemplateId = overlayTemplateId == null ? "" : overlayTemplateId;
            this.stationMode = stationMode == null ? StationDisplayMode.MANUAL : stationMode;
            this.stationBindingName = stationBindingName == null ? "" : stationBindingName;
            this.stationTurnaroundName = stationTurnaroundName == null
                    ? "" : stationTurnaroundName;
            this.stationTrainType = stationTrainType == null ? "" : stationTrainType;
            this.stationMapTemplateId = stationMapTemplateId == null ? "builtin_transit" : stationMapTemplateId;
        }
        applyServerUpdate(back, backMode, destination, textColor, backgroundColor, animation, speed,
                fontSize, editor);
    }

    public static boolean validTrafficField(String value) {
        if (value == null || value.length() > MAX_TRAFFIC_FIELD_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    public static boolean validText(String value) {
        if (value == null || value.length() > MAX_TEXT_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) && character != '\n' && character != '\t') {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("text", text);
        tag.putInt("text_color", textColor);
        tag.putInt("background_color", backgroundColor);
        tag.putString("animation", animation.name());
        tag.putFloat("speed", speed);
        tag.putInt("font_size", fontSize);
        tag.putString("back_mode", backMode.name());
        tag.putString("back_text", backText);
        tag.putInt("back_text_color", backTextColor);
        tag.putInt("back_background_color", backBackgroundColor);
        tag.putString("back_animation", backAnimation.name());
        tag.putFloat("back_speed", backSpeed);
        tag.putInt("back_font_size", backFontSize);
        tag.putString("traffic_line", trafficLine);
        tag.putString("traffic_destination", trafficDestination);
        tag.putString("traffic_current", trafficCurrentStop);
        tag.putString("traffic_next", trafficNextStop);
        tag.putString("traffic_eta", trafficEta);
        tag.putString("traffic_status", trafficStatus);
        tag.putString("template_id", templateId);
        tag.putString("overlay_template_id", overlayTemplateId);
        tag.putString("back_traffic_line", backTrafficLine);
        tag.putString("back_traffic_destination", backTrafficDestination);
        tag.putString("back_traffic_current", backTrafficCurrentStop);
        tag.putString("back_traffic_next", backTrafficNextStop);
        tag.putString("back_traffic_eta", backTrafficEta);
        tag.putString("back_traffic_status", backTrafficStatus);
        tag.putString("back_template_id", backTemplateId);
        tag.putString("back_overlay_template_id", backOverlayTemplateId);
        tag.putString("station_mode", stationMode.name());
        tag.putString("station_binding_name", stationBindingName);
        tag.putString("station_turnaround_name", stationTurnaroundName);
        tag.putString("station_train_type", stationTrainType);
        tag.putString("station_map_template", stationMapTemplateId);
        tag.putString("back_station_mode", backStationMode.name());
        tag.putString("back_station_binding_name", backStationBindingName);
        tag.putString("back_station_turnaround_name", backStationTurnaroundName);
        tag.putString("back_station_train_type", backStationTrainType);
        tag.putString("back_station_map_template", backStationMapTemplateId);
        tag.putString("traffic_role", trafficRole.name());
        tag.putLong("animation_start", animationStartGameTime);
        tag.putLong("back_animation_start", backAnimationStartGameTime);
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("text", Tag.TAG_STRING) && validText(tag.getString("text"))) {
            text = tag.getString("text");
        }
        if (tag.contains("text_color", Tag.TAG_INT)) {
            textColor = tag.getInt("text_color");
        }
        if (tag.contains("background_color", Tag.TAG_INT)) {
            backgroundColor = tag.getInt("background_color");
        }
        if (tag.contains("animation", Tag.TAG_STRING)) {
            try {
                animation = TextDisplayAnimation.valueOf(tag.getString("animation"));
            } catch (IllegalArgumentException ignored) {
                animation = getBlockState().is(MineScreen.ANIMATED_TEXT_DISPLAY_BLOCK.get())
                        ? TextDisplayAnimation.MARQUEE : TextDisplayAnimation.STATIC;
            }
        }
        if (tag.contains("speed", Tag.TAG_FLOAT)) {
            float loadedSpeed = tag.getFloat("speed");
            speed = Float.isFinite(loadedSpeed)
                    ? Math.max(MIN_SPEED, Math.min(MAX_SPEED, loadedSpeed)) : 1.0F;
        }
        if (tag.contains("font_size", Tag.TAG_INT)) {
            fontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, tag.getInt("font_size")));
        }
        try { backMode = DisplayBackMode.valueOf(tag.getString("back_mode")); }
        catch (IllegalArgumentException ignored) { backMode = DisplayBackMode.OFF; }
        backText = tag.contains("back_text", Tag.TAG_STRING) && validText(tag.getString("back_text"))
                ? tag.getString("back_text") : backText;
        if (tag.contains("back_text_color", Tag.TAG_INT)) backTextColor = tag.getInt("back_text_color");
        if (tag.contains("back_background_color", Tag.TAG_INT)) backBackgroundColor = tag.getInt("back_background_color");
        try { backAnimation = TextDisplayAnimation.valueOf(tag.getString("back_animation")); }
        catch (IllegalArgumentException ignored) { backAnimation = TextDisplayAnimation.STATIC; }
        if (tag.contains("back_speed", Tag.TAG_FLOAT) && Float.isFinite(tag.getFloat("back_speed")))
            backSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, tag.getFloat("back_speed")));
        if (tag.contains("back_font_size", Tag.TAG_INT))
            backFontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, tag.getInt("back_font_size")));
        trafficLine = readTrafficField(tag, "traffic_line", trafficLine);
        trafficDestination = readTrafficField(tag, "traffic_destination", trafficDestination);
        trafficCurrentStop = readTrafficField(tag, "traffic_current", trafficCurrentStop);
        trafficNextStop = readTrafficField(tag, "traffic_next", trafficNextStop);
        trafficEta = readTrafficField(tag, "traffic_eta", trafficEta);
        trafficStatus = readTrafficField(tag, "traffic_status", trafficStatus);
        templateId = readTrafficField(tag, "template_id", templateId);
        overlayTemplateId = readTrafficField(tag, "overlay_template_id", overlayTemplateId);
        backTrafficLine = readTrafficField(tag, "back_traffic_line", backTrafficLine);
        backTrafficDestination = readTrafficField(tag, "back_traffic_destination", backTrafficDestination);
        backTrafficCurrentStop = readTrafficField(tag, "back_traffic_current", backTrafficCurrentStop);
        backTrafficNextStop = readTrafficField(tag, "back_traffic_next", backTrafficNextStop);
        backTrafficEta = readTrafficField(tag, "back_traffic_eta", backTrafficEta);
        backTrafficStatus = readTrafficField(tag, "back_traffic_status", backTrafficStatus);
        backTemplateId = readTrafficField(tag, "back_template_id", backTemplateId);
        backOverlayTemplateId = readTrafficField(tag, "back_overlay_template_id",
                backOverlayTemplateId);
        try { stationMode = StationDisplayMode.valueOf(tag.getString("station_mode")); }
        catch (IllegalArgumentException ignored) { stationMode = StationDisplayMode.MANUAL; }
        stationBindingName = readTrafficField(tag, "station_binding_name", stationBindingName);
        stationTurnaroundName = readTrafficField(tag, "station_turnaround_name",
                stationTurnaroundName);
        stationTrainType = readTrafficField(tag, "station_train_type", stationTrainType);
        stationMapTemplateId = readTrafficField(tag, "station_map_template", stationMapTemplateId);
        try { backStationMode = StationDisplayMode.valueOf(tag.getString("back_station_mode")); }
        catch (IllegalArgumentException ignored) { backStationMode = StationDisplayMode.MANUAL; }
        backStationBindingName = readTrafficField(tag, "back_station_binding_name",
                backStationBindingName);
        backStationTurnaroundName = readTrafficField(tag, "back_station_turnaround_name",
                backStationTurnaroundName);
        backStationTrainType = readTrafficField(tag, "back_station_train_type", backStationTrainType);
        backStationMapTemplateId = readTrafficField(tag, "back_station_map_template", backStationMapTemplateId);
        try { trafficRole = TrafficDisplayRole.valueOf(tag.getString("traffic_role")); }
        catch (IllegalArgumentException ignored) { trafficRole = TrafficDisplayRole.PLATFORM; }
        animationStartGameTime = tag.getLong("animation_start");
        backAnimationStartGameTime = tag.getLong("back_animation_start");
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private static String readTrafficField(CompoundTag tag, String key, String fallback) {
        if (!tag.contains(key, Tag.TAG_STRING) || !validTrafficField(tag.getString(key))) {
            return fallback;
        }
        return tag.getString(key);
    }

    private void syncChanged() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
