package dev.minescreen.client.content;

import dev.minescreen.ScreenGroup;

public interface ScreenContentSession extends AutoCloseable {
    ScreenContentType type();

    ScreenRenderSource renderSource();

    default void tick(ScreenGroup group) {
    }

    default void resize(ScreenGroup group) {
    }

    default long positionMs() {
        return 0L;
    }

    default long durationMs() {
        return 0L;
    }

    default void seek(long positionMs) {
    }

    default void setPaused(boolean paused) {
    }

    /** Hot audio control. Implementations must not recreate their visual/backend session. */
    default void setVolume(float volume) {
    }

    /** Non-null only when a backend could not open its source; used by the local editor. */
    default String errorMessage() {
        return null;
    }

    @Override
    void close();
}
