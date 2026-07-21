package dev.minescreen.client.video;

import java.util.concurrent.atomic.AtomicIntegerArray;

import com.mojang.blaze3d.platform.NativeImage;

/** Three-slot non-blocking frame queue. Old frames are dropped to preserve latency. */
public final class FrameRingBuffer implements AutoCloseable {
    private static final int FREE = 0;
    private static final int WRITING = 1;
    private static final int READY = 2;
    private static final int RENDERING = 3;

    private final NativeImage[] images;
    private final AtomicIntegerArray states;
    private final long[] ptsMicros;

    public FrameRingBuffer(int width, int height) {
        images = new NativeImage[3];
        states = new AtomicIntegerArray(images.length);
        ptsMicros = new long[images.length];
        for (int i = 0; i < images.length; i++) {
            images[i] = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            states.set(i, FREE);
        }
    }

    public NativeImage acquireWritable() {
        for (int i = 0; i < states.length(); i++) {
            if (states.compareAndSet(i, FREE, WRITING)) {
                return images[i];
            }
        }
        return null;
    }

    public void publish(NativeImage image, long ptsMicros) {
        int slot = indexOf(image);
        ptsMicros(slot, ptsMicros);
        states.set(slot, READY);
    }

    /** Returns an acquired slot when conversion fails before it can be published. */
    public void releaseWritable(NativeImage image) {
        int slot = indexOf(image);
        states.compareAndSet(slot, WRITING, FREE);
    }

    public VideoFrame pollLatest() {
        int selected = -1;
        long selectedPts = Long.MIN_VALUE;
        for (int i = 0; i < states.length(); i++) {
            if (states.compareAndSet(i, READY, RENDERING)) {
                if (ptsMicros[i] >= selectedPts) {
                    if (selected >= 0) {
                        states.set(selected, FREE);
                    }
                    selected = i;
                    selectedPts = ptsMicros[i];
                } else {
                    states.set(i, FREE);
                }
            }
        }
        return selected < 0 ? null : new VideoFrame(images[selected], selectedPts, selected);
    }

    public void release(VideoFrame frame) {
        states.compareAndSet(frame.slot(), RENDERING, FREE);
    }

    private int indexOf(NativeImage image) {
        for (int i = 0; i < images.length; i++) {
            if (images[i] == image) {
                return i;
            }
        }
        throw new IllegalArgumentException("Frame does not belong to this ring");
    }

    private void ptsMicros(int slot, long value) {
        ptsMicros[slot] = value;
    }

    @Override
    public void close() {
        for (NativeImage image : images) {
            image.close();
        }
    }
}
