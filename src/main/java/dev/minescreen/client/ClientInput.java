package dev.minescreen.client;

import org.lwjgl.glfw.GLFW;

import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Crosshair-driven WEB/VNC routing. The ray UV is the virtual mouse position; raw mouse actions are
 * cancelled before Minecraft can mine/use while a screen is active. Holding the dismantling tool
 * opts out immediately, allowing the same left click to break the physical screen block.
 */
@EventBusSubscriber(modid = MineScreen.MOD_ID, value = Dist.CLIENT)
public final class ClientInput {
    private static ActiveTarget active;
    private static ScreenInputTarget keyboardFocus;
    private static BlockPos fixedKeyboardPos;
    private static boolean keyboardExitLatched;
    private static final java.util.Set<Integer> pressedButtons = new java.util.HashSet<>();

    private ClientInput() {
    }

    /** True while a handheld or fixed keyboard owns keyboard input for WEB/VNC. */
    public static boolean keyboardCaptured() {
        return keyboardFocus != null && Minecraft.getInstance().screen == null;
    }

    /**
     * Called at the head of Minecraft's KeyboardHandler, before vanilla shortcuts, KeyMapping
     * mutation and NeoForge InputEvent.Key dispatch. Returning true means the physical event is
     * exclusively owned by the MineScreen keyboard and must not reach game/mod hotkeys.
     */
    public static boolean interceptKey(long window, int keyCode, int scanCode, int action,
            int modifiers) {
        Minecraft minecraft = Minecraft.getInstance();
        if (window != minecraft.getWindow().getWindow() || minecraft.screen != null
                || keyboardFocus == null || keyCode == GLFW.GLFW_KEY_UNKNOWN) {
            return false;
        }
        // Alt+Esc is supported when the OS delivers it; plain Esc is intentionally also a
        // reliable fallback because desktop window managers may reserve Alt+Esc themselves.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (action == GLFW.GLFW_PRESS) {
                exitKeyboardMode();
            }
            return true;
        }
        if (action == GLFW.GLFW_RELEASE) {
            keyboardFocus.keyRelease(keyCode, scanCode, modifiers);
        } else if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) {
            keyboardFocus.keyPress(keyCode, scanCode, modifiers);
        }
        suppressGameplayBindings(minecraft);
        return true;
    }

    /** Receives GLFW Unicode input and prevents it from being delivered to any game GUI/mod. */
    public static boolean interceptChar(long window, int codePoint, int modifiers) {
        Minecraft minecraft = Minecraft.getInstance();
        if (window != minecraft.getWindow().getWindow() || minecraft.screen != null
                || keyboardFocus == null || !Character.isValidCodePoint(codePoint)) {
            return false;
        }
        for (char character : Character.toChars(codePoint)) {
            keyboardFocus.keyTyped(character, modifiers);
        }
        return true;
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean usableWindow = minecraft.level != null && minecraft.player != null
                && minecraft.screen == null
                && GLFW.glfwGetWindowAttrib(minecraft.getWindow().getWindow(), GLFW.GLFW_FOCUSED)
                        == GLFW.GLFW_TRUE;
        if (!usableWindow && keyboardFocus != null
                && GLFW.glfwGetWindowAttrib(minecraft.getWindow().getWindow(), GLFW.GLFW_FOCUSED)
                        != GLFW.GLFW_TRUE) {
            exitKeyboardMode();
        }
        setActive(usableWindow ? resolveTarget() : null);
        if (active != null) {
            sendCrosshairMove(active);
        }
        updateKeyboardMode(minecraft, usableWindow);
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        // Shift + right click remains the explicit settings gesture and is not sent to content.
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && Screen.hasShiftDown()) {
            return;
        }
        ActiveTarget target = refreshForEvent();
        if (target == null) {
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && canDismantle(target)) {
            setKeyboardFocus(null);
            return;
        }
        sendCrosshairMove(target);
        if (event.getAction() == GLFW.GLFW_PRESS) {
            pressedButtons.add(event.getButton());
            target.target().mousePress(target.x(), target.y(), event.getButton());
        } else if (event.getAction() == GLFW.GLFW_RELEASE) {
            pressedButtons.remove(event.getButton());
            target.target().mouseRelease(target.x(), target.y(), event.getButton());
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        ActiveTarget target = refreshForEvent();
        if (target == null) {
            return;
        }
        sendCrosshairMove(target);
        target.target().mouseWheel(target.x(), target.y(), event.getScrollDeltaY(), modifiers());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getKey() == GLFW.GLFW_KEY_UNKNOWN) {
            return;
        }
        if (keyboardFocus != null && event.getAction() == GLFW.GLFW_PRESS
                && event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
            exitKeyboardMode();
            return;
        }
        if (Minecraft.getInstance().screen != null || keyboardFocus == null) {
            return;
        }
        if (event.getAction() == GLFW.GLFW_RELEASE) {
            keyboardFocus.keyRelease(event.getKey(), event.getScanCode(), event.getModifiers());
        } else {
            keyboardFocus.keyPress(event.getKey(), event.getScanCode(), event.getModifiers());
            Character typed = typedCharacter(event.getKey(), event.getScanCode(), event.getModifiers());
            if (typed != null) {
                keyboardFocus.keyTyped(typed, event.getModifiers());
            }
        }
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            return;
        }
        if (event.isUseItem() && minecraft.hitResult instanceof BlockHitResult blockHit
                && minecraft.level != null
                && minecraft.level.getBlockState(blockHit.getBlockPos())
                        .is(MineScreen.COMPUTER_BLOCK.get())) {
            ScreenGroup linked = ScreenLinkResolver.findGroup(minecraft.level, blockHit.getBlockPos());
            if (linked == null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("screen.minescreen.computer.no_link"), true);
            } else {
                setActive(null);
                minecraft.setScreen(new ComputerScreen(blockHit.getBlockPos(), linked));
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        if (event.isUseItem() && minecraft.hitResult instanceof BlockHitResult blockHit
                && minecraft.level != null
                && minecraft.level.getBlockState(blockHit.getBlockPos())
                        .is(MineScreen.FIXED_KEYBOARD_BLOCK.get())) {
            ScreenGroup linked = ScreenLinkResolver.findGroup(minecraft.level, blockHit.getBlockPos());
            if (linked == null || inputTarget(linked) == null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("screen.minescreen.keyboard.no_link"), true);
            } else {
                fixedKeyboardPos = blockHit.getBlockPos().immutable();
                keyboardExitLatched = false;
                minecraft.player.displayClientMessage(
                        Component.translatable("screen.minescreen.keyboard.entered"), true);
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        if (event.isUseItem() && Screen.hasShiftDown()) {
            ScreenRaycast.ScreenHit hit = ScreenRaycast.raycastNow();
            ScreenGroup group = hit == null ? null : ScreenGroupManager.group(hit.groupId());
            if (group != null) {
                setActive(null);
                minecraft.setScreen(new ScreenEditorScreen(group));
                event.setCanceled(true);
                event.setSwingHand(false);
            }
            return;
        }
        ActiveTarget target = refreshForEvent();
        if (target != null && !(event.isAttack() && canDismantle(target))) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if ((active == null && keyboardFocus == null) || Minecraft.getInstance().screen != null) {
            return;
        }
        String hex = MineScreenConfig.CONTROL_INDICATOR_COLOR.get().replace("#", "");
        int color = 0xFFFFD43B;
        try {
            color = (int) Long.parseLong(hex, 16);
            if (hex.length() <= 6) {
                color |= 0xFF000000;
            }
        } catch (NumberFormatException ignored) {
        }
        Minecraft minecraft = Minecraft.getInstance();
        Component label = Component.translatable(keyboardFocus == null
                ? "screen.minescreen.controlling_crosshair"
                : "screen.minescreen.keyboard.controlling");
        int margin = 6;
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int textWidth = minecraft.font.width(label);
        String corner = MineScreenConfig.CONTROL_INDICATOR_CORNER.get()
                .toLowerCase(java.util.Locale.ROOT);
        int x = corner.endsWith("left") ? margin : width - textWidth - margin;
        int y = corner.startsWith("top") ? margin : height - minecraft.font.lineHeight - margin;
        event.getGuiGraphics().drawString(minecraft.font, label, x, y, color, true);
    }

    private static ActiveTarget refreshForEvent() {
        ActiveTarget next = resolveTarget();
        setActive(next);
        return active;
    }

    private static ActiveTarget resolveTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null) {
            return null;
        }
        ScreenRaycast.ScreenHit hit = ScreenRaycast.raycastNow();
        if (hit == null) {
            return null;
        }
        ScreenGroup group = ScreenGroupManager.group(hit.groupId());
        if (group == null) {
            return null;
        }
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
        if (profile.contentType != ScreenContentType.WEB
                && profile.contentType != ScreenContentType.VNC) {
            return null;
        }
        if (profile.contentType == ScreenContentType.VNC && profile.vncReadOnly) {
            return null;
        }
        ScreenContentManager.sourceFor(group);
        if (!(ScreenContentManager.session(group.groupId()) instanceof ScreenInputTarget target)) {
            return null;
        }
        int x = clamp((int) Math.floor(hit.u() * target.inputWidth()), target.inputWidth());
        int y = clamp((int) Math.floor(hit.v() * target.inputHeight()), target.inputHeight());
        return new ActiveTarget(group.groupId(), target, x, y);
    }

    private static void setActive(ActiveTarget next) {
        if (sameTarget(active, next)) {
            active = next;
            return;
        }
        if (active != null) {
            for (int button : pressedButtons) {
                active.target().mouseRelease(active.x(), active.y(), button);
            }
            pressedButtons.clear();
        }
        active = next;
        if (active != null) {
            sendCrosshairMove(active);
        }
    }

    private static boolean sameTarget(ActiveTarget first, ActiveTarget second) {
        return first == null ? second == null
                : second != null && first.groupId().equals(second.groupId())
                        && first.target() == second.target();
    }

    private static void sendCrosshairMove(ActiveTarget target) {
        target.target().mouseMove(target.x(), target.y());
    }

    private static boolean canDismantle(ActiveTarget target) {
        Minecraft minecraft = Minecraft.getInstance();
        ScreenGroup group = ScreenGroupManager.group(target.groupId());
        return minecraft.player != null && minecraft.level != null && group != null
                && minecraft.player.getMainHandItem().isCorrectToolForDrops(
                        minecraft.level.getBlockState(group.master()));
    }

    private static void setKeyboardFocus(ScreenInputTarget next) {
        if (keyboardFocus == next) {
            return;
        }
        if (keyboardFocus != null) {
            keyboardFocus.focus(false);
        }
        keyboardFocus = next;
        if (keyboardFocus != null) {
            keyboardFocus.focus(true);
        }
    }

    private static void updateKeyboardMode(Minecraft minecraft, boolean usableWindow) {
        ScreenInputTarget desired = null;
        boolean handheldCandidate = usableWindow && holdingKeyboard() && active != null;
        if (fixedKeyboardPos != null && usableWindow && minecraft.level != null && minecraft.player != null) {
            if (!minecraft.level.getBlockState(fixedKeyboardPos)
                    .is(MineScreen.FIXED_KEYBOARD_BLOCK.get())
                    || minecraft.player.distanceToSqr(fixedKeyboardPos.getX() + 0.5D,
                            fixedKeyboardPos.getY() + 0.5D, fixedKeyboardPos.getZ() + 0.5D) > 64.0D) {
                fixedKeyboardPos = null;
            } else {
                ScreenGroup linked = ScreenLinkResolver.findGroup(minecraft.level, fixedKeyboardPos);
                desired = linked == null ? null : inputTarget(linked);
            }
        } else if (handheldCandidate && !keyboardExitLatched) {
            desired = active.target();
        }
        if (!handheldCandidate && fixedKeyboardPos == null) {
            keyboardExitLatched = false;
        }
        setKeyboardFocus(desired);
        if (keyboardFocus != null) {
            suppressGameplayBindings(minecraft);
        }
    }

    private static boolean holdingKeyboard() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null
                && (minecraft.player.getMainHandItem().is(MineScreen.KEYBOARD_ITEM.get())
                        || minecraft.player.getOffhandItem().is(MineScreen.KEYBOARD_ITEM.get()));
    }

    private static ScreenInputTarget inputTarget(ScreenGroup group) {
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId());
        if (profile.contentType != ScreenContentType.WEB
                && (profile.contentType != ScreenContentType.VNC || profile.vncReadOnly)) {
            return null;
        }
        ScreenContentManager.sourceFor(group);
        return ScreenContentManager.session(group.groupId()) instanceof ScreenInputTarget target
                ? target : null;
    }

    private static void exitKeyboardMode() {
        keyboardExitLatched = true;
        fixedKeyboardPos = null;
        setKeyboardFocus(null);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("screen.minescreen.keyboard.exited"), true);
        }
    }

    private static void suppressGameplayBindings(Minecraft minecraft) {
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            mapping.setDown(false);
            while (mapping.consumeClick()) {
                // Keyboard mode intentionally reserves physical keys for WEB/VNC text input.
            }
        }
    }

    private static Character typedCharacter(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            return ' ';
        }
        String name = GLFW.glfwGetKeyName(keyCode, scanCode);
        if (name == null || name.codePointCount(0, name.length()) != 1) {
            return null;
        }
        char character = name.charAt(0);
        if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            character = switch (character) {
                case '1' -> '!'; case '2' -> '@'; case '3' -> '#'; case '4' -> '$';
                case '5' -> '%'; case '6' -> '^'; case '7' -> '&'; case '8' -> '*';
                case '9' -> '('; case '0' -> ')'; case '-' -> '_'; case '=' -> '+';
                case '[' -> '{'; case ']' -> '}'; case '\\' -> '|'; case ';' -> ':';
                case '\'' -> '"'; case ',' -> '<'; case '.' -> '>'; case '/' -> '?';
                case '`' -> '~';
                default -> Character.isLetter(character) ? Character.toUpperCase(character) : character;
            };
        }
        return character;
    }

    private static int modifiers() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        int modifiers = 0;
        if (down(window, GLFW.GLFW_KEY_LEFT_SHIFT) || down(window, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            modifiers |= GLFW.GLFW_MOD_SHIFT;
        }
        if (down(window, GLFW.GLFW_KEY_LEFT_CONTROL) || down(window, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            modifiers |= GLFW.GLFW_MOD_CONTROL;
        }
        if (down(window, GLFW.GLFW_KEY_LEFT_ALT) || down(window, GLFW.GLFW_KEY_RIGHT_ALT)) {
            modifiers |= GLFW.GLFW_MOD_ALT;
        }
        return modifiers;
    }

    private static boolean down(long window, int key) {
        return GLFW.glfwGetKey(window, key) != GLFW.GLFW_RELEASE;
    }

    private static int clamp(int value, int extent) {
        return Math.max(0, Math.min(Math.max(0, extent - 1), value));
    }

    private record ActiveTarget(java.util.UUID groupId, ScreenInputTarget target, int x, int y) {
    }
}
