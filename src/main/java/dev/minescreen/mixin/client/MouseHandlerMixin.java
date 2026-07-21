package dev.minescreen.mixin.client;

import dev.minescreen.client.ClientInput;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives WEB Pointer Lock exclusive ownership of raw mouse movement. Cancelling at the head keeps
 * Minecraft yaw/pitch unchanged; MouseHandler still clears its accumulated deltas after this call,
 * so movement cannot leak into the camera when the page releases the lock.
 */
@Mixin(value = MouseHandler.class, priority = 2000)
public abstract class MouseHandlerMixin {
    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void minescreen$interceptPointerLock(double frameTime, CallbackInfo callback) {
        if (ClientInput.interceptPlayerTurn(accumulatedDX, accumulatedDY)) {
            callback.cancel();
        }
    }
}
