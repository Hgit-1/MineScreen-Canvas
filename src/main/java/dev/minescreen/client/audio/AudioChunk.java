package dev.minescreen.client.audio;

import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryUtil;

/** Stereo signed-16 PCM owned by the audio queue until uploaded to an OpenAL buffer. */
public record AudioChunk(ByteBuffer pcm, int samples, long ptsMicros) implements AutoCloseable {
    public long durationMicros(int sampleRate) {
        return samples * 1_000_000L / sampleRate;
    }

    @Override
    public void close() {
        MemoryUtil.memFree(pcm);
    }
}
