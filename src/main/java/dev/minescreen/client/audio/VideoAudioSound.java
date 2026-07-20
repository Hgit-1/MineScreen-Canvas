package dev.minescreen.client.audio;

import java.util.concurrent.CompletableFuture;

import dev.minescreen.MineScreen;
import dev.minescreen.ScreenGroup;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Tickable positional wrapper; Minecraft's sound executor performs the actual OpenAL calls. */
public final class VideoAudioSound extends AbstractTickableSoundInstance {
    private final FfmpegPcmAudioStream stream;

    public VideoAudioSound(ScreenGroup group, float volume, FfmpegPcmAudioStream stream) {
        super(MineScreen.VIDEO_AUDIO.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.stream = stream;
        this.looping = false;
        this.relative = false;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.pitch = 1.0F;
        update(group, volume);
    }

    public void update(ScreenGroup group, float nextVolume) {
        Vec3 center = group.bounds().getCenter();
        this.x = center.x;
        this.y = center.y;
        this.z = center.z;
        this.volume = Math.max(0.0F, Math.min(1.0F, nextVolume));
    }

    @Override
    public void tick() {
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library, Sound sound,
            boolean looping) {
        return CompletableFuture.completedFuture(stream);
    }

    public void stopNow() {
        stop();
    }
}
