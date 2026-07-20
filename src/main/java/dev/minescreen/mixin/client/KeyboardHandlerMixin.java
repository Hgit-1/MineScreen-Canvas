package dev.minescreen.mixin.client;

import dev.minescreen.client.ClientInput;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes MineScreen keyboard capture exclusive before vanilla and mod keybind processing. */
@Mixin(value = KeyboardHandler.class, priority = 2000)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void minescreen$interceptKey(long window, int keyCode, int scanCode, int action,
            int modifiers, CallbackInfo callback) {
        if (ClientInput.interceptKey(window, keyCode, scanCode, action, modifiers)) {
            callback.cancel();
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void minescreen$interceptChar(long window, int codePoint, int modifiers,
            CallbackInfo callback) {
        if (ClientInput.interceptChar(window, codePoint, modifiers)) {
            callback.cancel();
        }
    }
}
