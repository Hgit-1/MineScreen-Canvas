package dev.minescreen.network;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-dimension persistent shared screen metadata. No framebuffer/media bytes are stored. */
public final class ServerScreenStateData extends SavedData {
    private static final String DATA_NAME = "minescreen_screen_states";
    private static final Factory<ServerScreenStateData> FACTORY =
            new Factory<>(ServerScreenStateData::new, ServerScreenStateData::load);
    private final Map<UUID, State> states = new HashMap<>();

    public static ServerScreenStateData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public State get(UUID id) {
        return states.get(id);
    }

    public void put(State state) {
        states.put(state.screenId, state);
        setDirty();
    }

    public Collection<State> states() {
        return states.values();
    }

    public void releaseController(UUID playerId) {
        boolean dirty = false;
        for (State state : states.values()) {
            if (playerId.equals(state.controller)) {
                state.controller = null;
                dirty = true;
            }
        }
        if (dirty) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (State state : states.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("screen_id", state.screenId);
            entry.putString("dimension", state.dimension.toString());
            entry.putLong("master", state.master.asLong());
            entry.putInt("columns", state.columns);
            entry.putInt("rows", state.rows);
            entry.putString("content_type", state.contentType);
            entry.putString("source_reference", state.sourceReference);
            entry.putLong("position_ms", state.positionMs);
            entry.putLong("updated_game_time", state.updatedGameTime);
            entry.putBoolean("paused", state.paused);
            entry.putBoolean("loop", state.loop);
            entry.putFloat("volume", state.volume);
            entry.putString("access", state.access.name());
            entry.putUUID("owner", state.owner);
            list.add(entry);
        }
        tag.put("screens", list);
        return tag;
    }

    private static ServerScreenStateData load(CompoundTag tag, HolderLookup.Provider registries) {
        ServerScreenStateData data = new ServerScreenStateData();
        ListTag list = tag.getList("screens", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            CompoundTag entry = (CompoundTag) raw;
            try {
                State state = new State(entry.getUUID("screen_id"),
                        ResourceLocation.parse(entry.getString("dimension")),
                        BlockPos.of(entry.getLong("master")), Math.max(1, entry.getInt("columns")),
                        Math.max(1, entry.getInt("rows")), normalizeContentType(entry.getString("content_type")),
                        entry.getString("source_reference"), Math.max(0L, entry.getLong("position_ms")),
                        entry.getLong("updated_game_time"), entry.getBoolean("paused"),
                        entry.getBoolean("loop"), Math.max(0.0F, Math.min(1.0F, entry.getFloat("volume"))),
                        ScreenAccess.safeValueOf(entry.getString("access")), entry.getUUID("owner"));
                data.states.put(state.screenId, state);
            } catch (RuntimeException ignored) {
            }
        }
        return data;
    }

    private static String normalizeContentType(String value) {
        return value.equalsIgnoreCase("TEST") ? "IDLE" : value;
    }

    public static final class State {
        public final UUID screenId;
        public final ResourceLocation dimension;
        public BlockPos master;
        public int columns;
        public int rows;
        public String contentType;
        public String sourceReference;
        public long positionMs;
        public long updatedGameTime;
        public boolean paused;
        public boolean loop;
        public float volume;
        public ScreenAccess access;
        public final UUID owner;
        public UUID controller;

        public State(UUID screenId, ResourceLocation dimension, BlockPos master, int columns, int rows,
                String contentType, String sourceReference, long positionMs, long updatedGameTime,
                boolean paused, boolean loop, float volume, ScreenAccess access, UUID owner) {
            this.screenId = screenId;
            this.dimension = dimension;
            this.master = master.immutable();
            this.columns = columns;
            this.rows = rows;
            this.contentType = contentType;
            this.sourceReference = sourceReference;
            this.positionMs = positionMs;
            this.updatedGameTime = updatedGameTime;
            this.paused = paused;
            this.loop = loop;
            this.volume = volume;
            this.access = access;
            this.owner = owner;
        }

        public long currentPosition(long gameTime) {
            return paused ? positionMs : Math.max(0L, positionMs + Math.max(0L, gameTime - updatedGameTime) * 50L);
        }

        public ScreenStatePayload payload(long gameTime) {
            return new ScreenStatePayload(screenId, dimension, master, columns, rows, contentType,
                    sourceReference, currentPosition(gameTime), gameTime, paused, loop, volume, access,
                    owner, controller);
        }
    }
}
