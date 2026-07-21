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
    private float baseVolume;
    private float distanceGain;

    public VideoAudioSound(ScreenGroup group, float volume, FfmpegPcmAudioStream stream) {
        super(MineScreen.VIDEO_AUDIO.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.stream = stream;
        this.looping = false;
        this.relative = false;
        // Range differs between the screen and connected speakers, so MineScreen computes one
        // listener-relative gain and keeps a single spatial OpenAL source instead of duplicating
        // the decoded stream for every speaker.
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.pitch = 1.0F;
        update(group, volume);
    }

    public void update(ScreenGroup group, float nextVolume) {
        baseVolume = Math.max(0.0F, Math.min(1.0F, nextVolume));
        SpeakerLinkResolver.Emitter emitter = SpeakerLinkResolver.bestEmitter(group);
        Vec3 position = emitter.position();
        this.x = position.x;
        this.y = position.y;
        this.z = position.z;
        distanceGain = emitter.gain();
        this.volume = baseVolume * distanceGain;
    }

    public void setBaseVolume(float nextVolume) {
        baseVolume = Math.max(0.0F, Math.min(1.0F, nextVolume));
        this.volume = baseVolume * distanceGain;
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
