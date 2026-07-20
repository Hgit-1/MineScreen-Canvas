package dev.minescreen.client.audio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.LockSupport;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVInputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.system.MemoryUtil;

import dev.minescreen.client.video.VideoSource;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swresample.*;

/** FFmpeg audio worker: decodes/resamples to 48 kHz stereo S16 without any OpenAL calls. */
public final class FfmpegAudioDecoder implements AutoCloseable {
    public static final int SAMPLE_RATE = 48_000;
    private static final int QUEUE_CAPACITY = 16;

    private final VideoSource source;
    private final ArrayBlockingQueue<AudioChunk> chunks = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile boolean loop = true;
    private volatile long seekMicros = -1L;
    private volatile String errorMessage;
    private volatile boolean audioStreamFound;
    private Thread thread;

    public FfmpegAudioDecoder(VideoSource source) {
        this.source = source;
    }

    public void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::decodeLoop, "minescreen-ffmpeg-audio");
        thread.setDaemon(true);
        thread.start();
    }

    public AudioChunk poll() {
        return chunks.poll();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        LockSupport.unpark(thread);
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    public void seek(long positionMs) {
        seekMicros = Math.max(0L, positionMs) * 1000L;
        clearQueue();
        LockSupport.unpark(thread);
    }

    public boolean audioStreamFound() {
        return audioStreamFound;
    }

    public String errorMessage() {
        return errorMessage;
    }

    private void decodeLoop() {
        AVFormatContext format = avformat_alloc_context();
        AVCodecContext codecContext = null;
        AVPacket packet = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        AVChannelLayout outputLayout = new AVChannelLayout();
        SwrContext resampler = new SwrContext((Pointer) null);
        BytePointer converted = null;
        PointerPointer<BytePointer> outputPlanes = new PointerPointer<>(1);
        int streamIndex = -1;
        double timeBase = 0.0D;
        try {
            if (avformat_open_input(format, source.path().toString(), (AVInputFormat) null,
                    (AVDictionary) null) < 0 || avformat_find_stream_info(format, (PointerPointer<?>) null) < 0) {
                throw new IllegalStateException("Unable to open audio source");
            }
            AVStream stream = null;
            for (int i = 0; i < format.nb_streams(); i++) {
                AVStream candidate = format.streams(i);
                if (candidate.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                    streamIndex = i;
                    stream = candidate;
                    break;
                }
            }
            if (stream == null) {
                errorMessage = "No audio stream";
                return;
            }
            audioStreamFound = true;
            AVCodec codec = avcodec_find_decoder(stream.codecpar().codec_id());
            if (codec == null) {
                throw new IllegalStateException("No decoder for audio stream");
            }
            codecContext = avcodec_alloc_context3(codec);
            avcodec_parameters_to_context(codecContext, stream.codecpar());
            if (avcodec_open2(codecContext, codec, (AVDictionary) null) < 0) {
                throw new IllegalStateException("Unable to open audio decoder");
            }
            timeBase = av_q2d(stream.time_base());
            av_channel_layout_default(outputLayout, 2);
            if (swr_alloc_set_opts2(resampler, outputLayout, AV_SAMPLE_FMT_S16, SAMPLE_RATE,
                    codecContext.ch_layout(), codecContext.sample_fmt(), codecContext.sample_rate(),
                    0, null) < 0 || swr_init(resampler) < 0) {
                throw new IllegalStateException("Unable to initialize audio resampler");
            }

            while (running) {
                if (paused || chunks.remainingCapacity() == 0) {
                    LockSupport.parkNanos(5_000_000L);
                    continue;
                }
                long requestedSeek = seekMicros;
                if (requestedSeek >= 0L) {
                    seekMicros = -1L;
                    long timestamp = (long) (requestedSeek / Math.max(timeBase, 0.0000001D));
                    av_seek_frame(format, streamIndex, timestamp, AVSEEK_FLAG_BACKWARD);
                    avcodec_flush_buffers(codecContext);
                    swr_close(resampler);
                    swr_init(resampler);
                }
                if (av_read_frame(format, packet) < 0) {
                    if (loop) {
                        av_seek_frame(format, streamIndex, 0L, AVSEEK_FLAG_BACKWARD);
                        avcodec_flush_buffers(codecContext);
                        continue;
                    }
                    paused = true;
                    continue;
                }
                if (packet.stream_index() != streamIndex) {
                    av_packet_unref(packet);
                    continue;
                }
                if (avcodec_send_packet(codecContext, packet) >= 0) {
                    while (avcodec_receive_frame(codecContext, frame) >= 0 && running) {
                        int inputRate = Math.max(1, codecContext.sample_rate());
                        int outputSamples = Math.toIntExact(Math.min(Integer.MAX_VALUE,
                                av_rescale_rnd(swr_get_delay(resampler, inputRate) + frame.nb_samples(),
                                        SAMPLE_RATE, inputRate, AV_ROUND_UP)));
                        long requiredBytes = (long) outputSamples * 2L * Short.BYTES;
                        if (converted == null || converted.capacity() < requiredBytes) {
                            if (converted != null) {
                                converted.close();
                            }
                            converted = new BytePointer(requiredBytes);
                            outputPlanes.put(0, converted);
                        }
                        int produced = swr_convert(resampler, outputPlanes, outputSamples,
                                frame.extended_data(), frame.nb_samples());
                        if (produced <= 0) {
                            continue;
                        }
                        int byteCount = Math.multiplyExact(Math.multiplyExact(produced, 2), Short.BYTES);
                        java.nio.ByteBuffer pcm = MemoryUtil.memAlloc(byteCount);
                        MemoryUtil.memCopy(converted.address(), MemoryUtil.memAddress(pcm), byteCount);
                        pcm.limit(byteCount);
                        long pts = frame.best_effort_timestamp();
                        long ptsMicros = pts == AV_NOPTS_VALUE ? 0L
                                : Math.max(0L, (long) (pts * timeBase * 1_000_000D));
                        AudioChunk chunk = new AudioChunk(pcm, produced, ptsMicros);
                        if (!chunks.offer(chunk)) {
                            chunk.close();
                        }
                    }
                }
                av_packet_unref(packet);
            }
        } catch (Throwable exception) {
            errorMessage = exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : exception.getMessage();
        } finally {
            if (converted != null) {
                converted.close();
            }
            outputPlanes.close();
            swr_free(resampler);
            av_channel_layout_uninit(outputLayout);
            outputLayout.close();
            av_frame_free(frame);
            av_packet_free(packet);
            if (codecContext != null) {
                avcodec_free_context(codecContext);
            }
            avformat_close_input(format);
        }
    }

    private void clearQueue() {
        AudioChunk chunk;
        while ((chunk = chunks.poll()) != null) {
            chunk.close();
        }
    }

    @Override
    public void close() {
        running = false;
        Thread worker = thread;
        if (worker != null) {
            worker.interrupt();
            LockSupport.unpark(worker);
            try {
                worker.join(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        clearQueue();
    }
}
