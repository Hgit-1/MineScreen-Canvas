package dev.minescreen.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Remapped access to NativeImage's native allocation. A reflection lookup by the Mojmap string
 * "pixels" works in development but is not rewritten for an obfuscated production client.
 */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
    @Accessor("pixels")
    long minescreen$getPixels();
}
