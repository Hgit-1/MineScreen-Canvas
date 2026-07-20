package dev.minescreen.client.vnc;

import java.nio.ByteBuffer;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.minescreen.MineScreen;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.ScreenInputTarget;
import dev.minescreen.client.ScreenRenderType;
import dev.minescreen.client.ScreenTextureManager;
import dev.minescreen.client.ScreenVisibility;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.video.NativeImageAccess;
import dev.minescreen.client.web.BrowserRequestPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** RFB-backed screen source. Dirty rectangles are copied/uploaded only on the render thread. */
public final class VncScreenSession implements ScreenContentSession, ScreenInputTarget {
    private final UUID groupId;
    private final RfbClient client;
    private final boolean readOnly;
    private final int inputWidth;
    private final int inputHeight;
    private final ResourceLocation textureLocation;
    private DynamicTexture texture;
    private NativeImage image;
    private int textureWidth;
    private int textureHeight;
    private int buttonMask;

    public VncScreenSession(ScreenGroup group, ClientScreenProfile profile) {
        RfbEndpoint endpoint = RfbEndpoint.parse(profile.source);
        if (!BrowserRequestPolicy.isAllowed(endpoint.policyUrl())) {
            throw new IllegalStateException("VNC endpoint blocked by MineScreen network policy");
        }
        groupId = group.groupId();
        readOnly = profile.vncReadOnly;
        inputWidth = group.canvasWidth();
        inputHeight = group.canvasHeight();
        VncCredentialStore.Credential credential = VncCredentialStore.get(groupId);
        client = new RfbClient(endpoint, credential.password());
        textureLocation = ResourceLocation.fromNamespaceAndPath(MineScreen.MOD_ID,
                "vnc/" + groupId.toString().replace('-', '_'));
        client.start();
    }

    @Override
    public ScreenContentType type() {
        return ScreenContentType.VNC;
    }

    @Override
    public int inputWidth() {
        return inputWidth;
    }

    @Override
    public int inputHeight() {
        return inputHeight;
    }

    @Override
    public ScreenRenderSource renderSource() {
        pumpUpdates();
        if (texture == null) {
            return ScreenTextureManager.idleRenderSource();
        }
        return new ScreenRenderSource(ScreenRenderType.screen(textureLocation), this::pumpUpdates);
    }

    private void pumpUpdates() {
        RenderSystem.assertOnRenderThreadOrInit();
        RfbFramebufferUpdate update;
        int processed = 0;
        while (processed++ < 8 && (update = client.pollUpdate()) != null) {
            try {
                if (texture == null || textureWidth != update.framebufferWidth()
                        || textureHeight != update.framebufferHeight()) {
                    replaceTexture(update.framebufferWidth(), update.framebufferHeight());
                }
                ByteBuffer data = update.rgba();
                NativeImageAccess.copyRgbaRegion(image, org.lwjgl.system.MemoryUtil.memAddress(data),
                        update.width(), update.height(), textureWidth, update.x(), update.y());
                texture.bind();
                image.upload(0, update.x(), update.y(), update.x(), update.y(), update.width(),
                        update.height(), false, false, false, false);
            } finally {
                update.close();
            }
        }
    }

    private void replaceTexture(int width, int height) {
        if (width < 1 || height < 1) {
            return;
        }
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureLocation);
        }
        image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        texture = new DynamicTexture(image);
        textureWidth = width;
        textureHeight = height;
        Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
    }

    @Override
    public void tick(ScreenGroup group) {
        ScreenVisibility.State visibility = ScreenVisibility.evaluate(group);
        client.setUpdatesEnabled(visibility.active());
    }

    @Override
    public void focus(boolean focused) {
        // Pointer/keyboard routing is local to each client. No single-controller lease is taken:
        // multiple clients may connect to and control the same RFB endpoint concurrently.
    }

    @Override
    public void mouseMove(int x, int y) {
        if (hasControl()) {
            client.pointerEvent(scaleX(x), scaleY(y), buttonMask);
        }
    }

    @Override
    public void mousePress(int x, int y, int button) {
        if (!hasControl()) {
            return;
        }
        buttonMask |= switch (button) {
            case 0 -> 1;
            case 1 -> 4;
            case 2 -> 2;
            default -> 0;
        };
        client.pointerEvent(scaleX(x), scaleY(y), buttonMask);
    }

    @Override
    public void mouseRelease(int x, int y, int button) {
        if (!hasControl()) {
            return;
        }
        buttonMask &= ~switch (button) {
            case 0 -> 1;
            case 1 -> 4;
            case 2 -> 2;
            default -> 0;
        };
        client.pointerEvent(scaleX(x), scaleY(y), buttonMask);
    }

    @Override
    public void mouseWheel(int x, int y, double amount, int modifiers) {
        if (!hasControl() || amount == 0.0D) {
            return;
        }
        int wheelMask = amount > 0.0D ? 8 : 16;
        client.pointerEvent(scaleX(x), scaleY(y), buttonMask | wheelMask);
        client.pointerEvent(scaleX(x), scaleY(y), buttonMask);
    }

    @Override
    public void keyPress(int keyCode, long scanCode, int modifiers) {
        if (hasControl()) {
            client.keyEvent(true, RfbKeySym.fromGlfw(keyCode));
        }
    }

    @Override
    public void keyRelease(int keyCode, long scanCode, int modifiers) {
        if (hasControl()) {
            client.keyEvent(false, RfbKeySym.fromGlfw(keyCode));
        }
    }

    @Override
    public void keyTyped(char character, int modifiers) {
        if (hasControl() && character > 0xFF) {
            int keysym = RfbKeySym.fromCharacter(character);
            client.keyEvent(true, keysym);
            client.keyEvent(false, keysym);
        }
    }

    private int scaleX(int value) {
        return textureWidth <= 0 ? 0 : Math.round(value * (textureWidth - 1F)
                / Math.max(1F, inputWidth - 1F));
    }

    private int scaleY(int value) {
        return textureHeight <= 0 ? 0 : Math.round(value * (textureHeight - 1F)
                / Math.max(1F, inputHeight - 1F));
    }

    private boolean hasControl() {
        return !readOnly;
    }

    @Override
    public String errorMessage() {
        return client.errorMessage();
    }

    @Override
    public void close() {
        client.close();
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureLocation);
            texture = null;
            image = null;
        }
    }
}
