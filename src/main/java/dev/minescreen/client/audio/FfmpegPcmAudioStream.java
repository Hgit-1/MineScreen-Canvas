package dev.minescreen.client.audio;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.sound.sampled.AudioFormat;

import net.minecraft.client.sounds.AudioStream;

/** Pull adapter consumed by Minecraft's streaming Channel on its OpenAL executor. */
public final class FfmpegPcmAudioStream implements AudioStream {
    private static final AudioFormat FORMAT = new AudioFormat(FfmpegAudioDecoder.SAMPLE_RATE,
            16, 2, true, false);
    private final FfmpegAudioDecoder decoder;
    private final long minimumPtsMicros;
    private AudioChunk current;
    private volatile boolean closed;

    public FfmpegPcmAudioStream(FfmpegAudioDecoder decoder, long startPositionMs) {
        this.decoder = decoder;
        this.minimumPtsMicros = Math.max(0L, startPositionMs) * 1000L;
    }

    @Override
    public AudioFormat getFormat() {
        return FORMAT;
    }

    @Override
    public synchronized ByteBuffer read(int capacity) throws IOException {
        if (closed) {
            return null;
        }
        ByteBuffer output = ByteBuffer.allocateDirect(Math.max(4, capacity));
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (output.hasRemaining() && !closed) {
            if (current == null) {
                current = decoder.poll();
                if (current == null) {
                    if (output.position() > 0) {
                        break;
                    }
                    if (decoder.errorMessage() != null || System.nanoTime() >= deadline) {
                        return null;
                    }
                    try {
                        wait(2L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                if (current.ptsMicros() + current.durationMicros(FfmpegAudioDecoder.SAMPLE_RATE)
                        < minimumPtsMicros) {
                    current.close();
                    current = null;
                    continue;
                }
            }
            ByteBuffer pcm = current.pcm();
            int count = Math.min(output.remaining(), pcm.remaining());
            int oldLimit = pcm.limit();
            pcm.limit(pcm.position() + count);
            output.put(pcm);
            pcm.limit(oldLimit);
            if (!pcm.hasRemaining()) {
                current.close();
                current = null;
            }
            if (output.position() >= 16_384) {
                break;
            }
        }
        output.flip();
        return output.hasRemaining() ? output : null;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (current != null) {
            current.close();
            current = null;
        }
        decoder.close();
    }
}
