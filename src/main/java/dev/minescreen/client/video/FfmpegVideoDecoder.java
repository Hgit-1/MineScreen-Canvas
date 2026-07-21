package dev.minescreen.client.video;

import java.util.concurrent.locks.LockSupport;

import com.mojang.blaze3d.platform.NativeImage;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVInputFormat;
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.Pointer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * FFmpeg worker. It owns all FFmpeg objects and never touches Minecraft classes or OpenGL. Frames
 * are paced against media PTS, capped by targetFps, and written to the three-slot ring with holes
 * already letterboxed to the logical canvas aspect ratio.
 */
public final class FfmpegVideoDecoder implements AutoCloseable {
    public enum Stage {
        STARTING,
        OPENING_SOURCE,
        READING_STREAM_INFO,
        OPENING_CODEC,
        DECODING,
        CONVERTING_FIRST_FRAME,
        READY,
        FAILED
    }

    private final VideoSource source;
    private final FrameRingBuffer ring;
    private final int canvasWidth;
    private final int canvasHeight;
    private final int maxFps;
    private volatile int targetFps;
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile boolean loop = true;
    private volatile boolean ended;
    private volatile long seekMicros = -1L;
    private volatile long durationMicros;
    private volatile long currentPtsMicros;
    private volatile long decodedFrames;
    private volatile String errorMessage;
    private volatile Stage stage = Stage.STARTING;
    private Thread thread;

    public FfmpegVideoDecoder(VideoSource source, FrameRingBuffer ring, int width, int height, int maxFps) {
        this.source = source;
        this.ring = ring;
        this.canvasWidth = width;
        this.canvasHeight = height;
        this.maxFps = Math.max(1, Math.min(30, maxFps));
        this.targetFps = this.maxFps;
    }

    public void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::decodeLoop, "minescreen-ffmpeg");
        thread.setDaemon(true);
        thread.start();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        LockSupport.unpark(thread);
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    public void setTargetFps(int fps) {
        targetFps = Math.max(1, Math.min(maxFps, fps));
    }

    public void seekMicros(long micros) {
        seekMicros = Math.max(0L, micros);
        currentPtsMicros = seekMicros;
        ended = false;
        LockSupport.unpark(thread);
    }

    public long durationMs() {
        return durationMicros <= 0L ? 0L : durationMicros / 1000L;
    }

    public long positionMs() {
        return currentPtsMicros / 1000L;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean ended() {
        return ended;
    }

    public long decodedFrames() {
        return decodedFrames;
    }

    public Stage stage() {
        return stage;
    }

    private void decodeLoop() {
        // JavaCPP can fail while resolving native symbols in the first allocation. Keep that work
        // under an outer guard; otherwise the daemon dies before the normal FFmpeg catch block and
        // the UI remains stuck on "first frame" with no error.
        try {
            decodeLoopGuarded();
        } catch (Throwable exception) {
            fail(exception);
        }
    }

    private void decodeLoopGuarded() {
        AVFormatContext format = avformat_alloc_context();
        AVCodecContext codecContext = null;
        AVPacket packet = av_packet_alloc();
        AVFrame decoded = av_frame_alloc();
        AVFrame converted = av_frame_alloc();
        BytePointer convertedPixels = null;
        SwsContext scaler = null;
        AVDictionary inputOptions = null;
        AVIOInterruptCB.Callback_Pointer interruptCallback =
                new AVIOInterruptCB.Callback_Pointer((Pointer) null) {
                    @Override
                    public int call(Pointer ignored) {
                        return !running || Thread.currentThread().isInterrupted() ? 1 : 0;
                    }
                };
        int streamIndex = -1;
        double timeBase = 0.0D;
        try {
            stage = Stage.OPENING_SOURCE;
            format.interrupt_callback().callback(interruptCallback).opaque(null);
            inputOptions = FfmpegInputOptions.create(source);
            int result = avformat_open_input(format, source.ffmpegInput(), (AVInputFormat) null,
                    inputOptions);
            FfmpegInputOptions.free(inputOptions);
            inputOptions = null;
            if (result < 0) {
                throw FfmpegErrors.failure("Unable to open video source", result);
            }
            stage = Stage.READING_STREAM_INFO;
            result = avformat_find_stream_info(format, (PointerPointer<?>) null);
            if (result < 0) {
                throw FfmpegErrors.failure("Unable to read video stream information", result);
            }
            durationMicros = Math.max(0L, format.duration());
            AVStream stream = null;
            for (int i = 0; i < format.nb_streams(); i++) {
                AVStream candidate = format.streams(i);
                if (candidate.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                    streamIndex = i;
                    stream = candidate;
                    break;
                }
            }
            if (stream == null) {
                throw new IllegalStateException("The source contains no video stream");
            }
            AVCodec codec = avcodec_find_decoder(stream.codecpar().codec_id());
            if (codec == null) {
                throw new IllegalStateException("No FFmpeg decoder is available for this video codec");
            }
            stage = Stage.OPENING_CODEC;
            codecContext = avcodec_alloc_context3(codec);
            if (codecContext == null) {
                throw new IllegalStateException("Unable to allocate the FFmpeg video decoder");
            }
            result = avcodec_parameters_to_context(codecContext, stream.codecpar());
            if (result < 0) {
                throw FfmpegErrors.failure("Unable to configure the video decoder", result);
            }
            result = avcodec_open2(codecContext, codec, (AVDictionary) null);
            if (result < 0) {
                throw FfmpegErrors.failure("Unable to open the video decoder", result);
            }
            timeBase = av_q2d(stream.time_base());

            double sourceAspect = codecContext.height() <= 0 ? 1.0D
                    : (double) codecContext.width() / (double) codecContext.height();
            int scaledWidth = canvasWidth;
            int scaledHeight = Math.max(1, (int) Math.round(scaledWidth / sourceAspect));
            if (scaledHeight > canvasHeight) {
                scaledHeight = canvasHeight;
                scaledWidth = Math.max(1, (int) Math.round(scaledHeight * sourceAspect));
            }
            int offsetX = Math.max(0, (canvasWidth - scaledWidth) / 2);
            int offsetY = Math.max(0, (canvasHeight - scaledHeight) / 2);
            convertedPixels = new BytePointer((long) scaledWidth * scaledHeight * 4L);
            result = av_image_fill_arrays(converted.data(), converted.linesize(), convertedPixels,
                    AV_PIX_FMT_RGBA, scaledWidth, scaledHeight, 1);
            if (result < 0) {
                throw FfmpegErrors.failure("Unable to allocate the converted video frame", result);
            }
            scaler = sws_getContext(codecContext.width(), codecContext.height(), codecContext.pix_fmt(),
                    scaledWidth, scaledHeight, AV_PIX_FMT_RGBA, SWS_BILINEAR, null, null,
                    (DoublePointer) null);
            if (scaler == null) {
                throw new IllegalStateException("Unable to initialize the FFmpeg video scaler");
            }
            stage = Stage.DECODING;

            long basePts = Long.MIN_VALUE;
            long baseNanos = 0L;
            long lastPtsMicros = Long.MIN_VALUE;
            boolean wasPaused = false;
            while (running) {
                if (paused) {
                    wasPaused = true;
                    LockSupport.parkNanos(10_000_000L);
                    continue;
                }
                if (wasPaused) {
                    basePts = Long.MIN_VALUE;
                    baseNanos = System.nanoTime();
                    wasPaused = false;
                }
                long requestedSeek = seekMicros;
                if (requestedSeek >= 0L) {
                    seekMicros = -1L;
                    long timestamp = (long) (requestedSeek / Math.max(timeBase, 0.0000001D));
                    av_seek_frame(format, streamIndex, timestamp, AVSEEK_FLAG_BACKWARD);
                    avcodec_flush_buffers(codecContext);
                    basePts = Long.MIN_VALUE;
                    lastPtsMicros = Long.MIN_VALUE;
                }
                if (av_read_frame(format, packet) < 0) {
                    if (loop) {
                        av_seek_frame(format, streamIndex, 0L, AVSEEK_FLAG_BACKWARD);
                        avcodec_flush_buffers(codecContext);
                        currentPtsMicros = 0L;
                        basePts = Long.MIN_VALUE;
                        lastPtsMicros = Long.MIN_VALUE;
                        continue;
                    }
                    ended = true;
                    paused = true;
                    continue;
                }
                if (packet.stream_index() != streamIndex) {
                    av_packet_unref(packet);
                    continue;
                }
                if (avcodec_send_packet(codecContext, packet) >= 0) {
                    while (avcodec_receive_frame(codecContext, decoded) >= 0 && running) {
                        long pts = decoded.best_effort_timestamp();
                        long micros = pts == AV_NOPTS_VALUE || timeBase <= 0.0D ? 0L
                                : Math.max(0L, (long) (pts * timeBase * 1_000_000D));
                        int fps = Math.max(1, Math.min(maxFps, targetFps));
                        if (lastPtsMicros != Long.MIN_VALUE
                                && micros - lastPtsMicros < 1_000_000L / fps) {
                            continue;
                        }
                        if (basePts == Long.MIN_VALUE) {
                            basePts = pts == AV_NOPTS_VALUE ? 0L : pts;
                            baseNanos = System.nanoTime();
                        }
                        if (pts != AV_NOPTS_VALUE && timeBase > 0.0D) {
                            long desired = baseNanos + Math.max(0L,
                                    (long) ((pts - basePts) * timeBase * 1_000_000_000D));
                            long wait = desired - System.nanoTime();
                            if (wait > 0L) {
                                LockSupport.parkNanos(wait);
                            } else if (wait < -500_000_000L) {
                                basePts = pts;
                                baseNanos = System.nanoTime();
                            }
                        }
                        NativeImage target = ring.acquireWritable();
                        if (target == null) {
                            continue;
                        }
                        int scaledRows = sws_scale(scaler, decoded.data(), decoded.linesize(), 0,
                                codecContext.height(), converted.data(), converted.linesize());
                        if (scaledRows <= 0) {
                            ring.releaseWritable(target);
                            throw new IllegalStateException("FFmpeg failed to convert a video frame");
                        }
                        if (decodedFrames == 0L) {
                            stage = Stage.CONVERTING_FIRST_FRAME;
                        }
                        NativeImageAccess.clear(target, (long) canvasWidth * canvasHeight * 4L);
                        NativeImageAccess.copyRgbaLetterboxed(target, convertedPixels.address(), scaledWidth,
                                scaledHeight, canvasWidth, offsetX, offsetY);
                        ring.publish(target, micros);
                        currentPtsMicros = micros;
                        decodedFrames++;
                        stage = Stage.READY;
                        lastPtsMicros = micros;
                    }
                }
                av_packet_unref(packet);
            }
        } catch (Throwable exception) {
            fail(exception);
        } finally {
            FfmpegInputOptions.free(inputOptions);
            if (scaler != null) {
                sws_freeContext(scaler);
            }
            if (convertedPixels != null) {
                convertedPixels.close();
            }
            av_frame_free(decoded);
            av_frame_free(converted);
            av_packet_free(packet);
            if (codecContext != null) {
                avcodec_free_context(codecContext);
            }
            avformat_close_input(format);
            interruptCallback.close();
        }
    }

    private void fail(Throwable exception) {
        errorMessage = exception.getMessage() == null ? exception.getClass().getName()
                : exception.getClass().getSimpleName() + ": " + exception.getMessage();
        stage = Stage.FAILED;
        ended = true;
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
    }
}
