package dev.minescreen.client;

import org.lwjgl.glfw.GLFW;

import dev.minescreen.MineScreen;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.ScreenGeometry;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenRegionLayout;
import dev.minescreen.client.content.ScreenRotation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
        if (window != minecraft.getWindow().getWindow() || keyCode == GLFW.GLFW_KEY_UNKNOWN) {
            return false;
        }
        if (minecraft.screen instanceof ComputerScreen computer
                && computer.interceptBuiltInKeyboardKey(keyCode, scanCode, action, modifiers)) {
            return true;
        }
        if (minecraft.screen != null || keyboardFocus == null) {
            return false;
        }
        // Escape is the single reliable exit path and also releases WEB Pointer Lock.
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
        if (window != minecraft.getWindow().getWindow() || !Character.isValidCodePoint(codePoint)) {
            return false;
        }
        if (minecraft.screen instanceof ComputerScreen computer
                && computer.interceptBuiltInKeyboardChar(codePoint, modifiers)) {
            return true;
        }
        if (minecraft.screen != null || keyboardFocus == null) {
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
        if (active != null && !pointerLockActive()) {
            sendCrosshairMove(active);
        }
        updateKeyboardMode(minecraft, usableWindow);
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        // The upstream-style Screen Configurator and the legacy Shift gesture own right-click.
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && (Screen.hasShiftDown() || holdingScreenConfigurator())) {
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
                if (!ScreenPowerManager.isPowered(linked)) {
                    minecraft.player.displayClientMessage(
                            Component.translatable("screen.minescreen.power.computer_warning"),
                            true);
                }
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
        if (event.isUseItem() && (Screen.hasShiftDown() || holdingScreenConfigurator())) {
            ScreenRaycast.ScreenHit hit = ScreenRaycast.raycastNow();
            ScreenGroup group = hit == null ? null : ScreenGroupManager.group(hit.groupId());
            if (group != null) {
                if (!ScreenPowerManager.isPowered(group)) {
                    minecraft.player.displayClientMessage(
                            Component.translatable("screen.minescreen.power.required"), true);
                    event.setCanceled(true);
                    event.setSwingHand(false);
                    return;
                }
                setActive(null);
                minecraft.setScreen(new ScreenEditorScreen(group, hit.regionId(), null));
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

    private static boolean holdingScreenConfigurator() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null
                && (minecraft.player.getMainHandItem().is(MineScreen.SCREEN_CONFIGURATOR_ITEM.get())
                        || minecraft.player.getOffhandItem()
                                .is(MineScreen.SCREEN_CONFIGURATOR_ITEM.get()));
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
                : pointerLockActive()
                        ? "screen.minescreen.web.pointer_lock"
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
        if (group == null || !ScreenPowerManager.isPowered(group)) {
            return null;
        }
        ScreenContentManager.PanoramaRender joined = ScreenContentManager.panoramaFor(group);
        if (joined != null) {
            ClientScreenProfile root = ScreenContentManager.profile(joined.network().rootGroupId());
            if (root.contentType != ScreenContentType.WEB
                    && (root.contentType != ScreenContentType.VNC || root.vncReadOnly)) {
                return null;
            }
            ScreenContentManager.sourceFor(group);
            if (!(ScreenContentManager.session(group.groupId()) instanceof ScreenInputTarget target)) {
                return null;
            }
            double globalU = joined.surface().canvasU((float) hit.u(), (float) hit.v());
            double globalV = joined.surface().canvasV((float) hit.u(), (float) hit.v());
            int x = clamp((int) Math.floor(globalU * target.inputWidth()), target.inputWidth());
            int y = clamp((int) Math.floor(globalV * target.inputHeight()), target.inputHeight());
            return new ActiveTarget(group.groupId(), 0, target, x, y);
        }
        ClientScreenProfile profile = ScreenContentManager.profile(group.groupId(), hit.regionId());
        if (profile.contentType != ScreenContentType.WEB
                && profile.contentType != ScreenContentType.VNC) {
            return null;
        }
        if (profile.contentType == ScreenContentType.VNC && profile.vncReadOnly) {
            return null;
        }
        ScreenContentManager.sourceFor(group, hit.regionId());
        if (!(ScreenContentManager.session(group.groupId(), hit.regionId())
                instanceof ScreenInputTarget target)) {
            return null;
        }
        int rotation = ScreenHostNetworkManager.rotationFor(group);
        double contentU = dev.minescreen.client.content.ScreenRotation.contentU(
                (float) hit.u(), (float) hit.v(), rotation);
        double contentV = dev.minescreen.client.content.ScreenRotation.contentV(
                (float) hit.u(), (float) hit.v(), rotation);
        int x = clamp((int) Math.floor(contentU * target.inputWidth()), target.inputWidth());
        int y = clamp((int) Math.floor(contentV * target.inputHeight()), target.inputHeight());
        return new ActiveTarget(group.groupId(), hit.regionId(), target, x, y);
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
                        && first.regionId() == second.regionId()
                        && first.target() == second.target();
    }

    private static void sendCrosshairMove(ActiveTarget target) {
        if (pointerLockActive() && target.target() == keyboardFocus) {
            return;
        }
        target.target().mouseMove(target.x(), target.y());
    }

    /** Called from MouseHandler.turnPlayer before Minecraft applies yaw/pitch. */
    public static boolean interceptPlayerTurn(double deltaX, double deltaY) {
        if (!(keyboardFocus instanceof dev.minescreen.client.web.BrowserSession browser)
                || Minecraft.getInstance().screen != null || !browser.pointerLockRequested()) {
            return false;
        }
        browser.relativeMouseMove(deltaX, deltaY);
        return true;
    }

    /**
     * Aligns Pointer Lock with the physical point that displays the logical image centre.
     *
     * <p>A host canvas can contain rotated surfaces, differently sized planes and empty gaps. The
     * centre therefore cannot be approximated by the master tile. We project every live physical
     * tile into content-canvas UV space, select the tile containing (0.5, 0.5), then invert that
     * surface's rotation. If the logical centre is a gap, the nearest real displayed point wins.
     */
    public static void centerViewForPointerLock() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || active == null) {
            return;
        }
        ScreenGroup group = ScreenGroupManager.group(active.groupId());
        if (group == null) {
            return;
        }
        Vec3 target = logicalCentreTarget(minecraft.level, group, active.regionId());
        if (target == null) {
            return;
        }
        Vec3 view = target.subtract(minecraft.player.getEyePosition());
        if (view.lengthSqr() < 1.0E-6D) {
            view = ScreenGeometry.normal(group.facing()).scale(-1.0D);
        } else {
            view = view.normalize();
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-view.x, view.z));
        float pitch = (float) Math.toDegrees(Math.asin(-view.y));
        minecraft.player.setYRot(yaw);
        minecraft.player.setYHeadRot(yaw);
        minecraft.player.setYBodyRot(yaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.yRotO = yaw;
        minecraft.player.xRotO = pitch;
    }

    private static Vec3 logicalCentreTarget(ClientLevel level, ScreenGroup activeGroup,
            int regionId) {
        AimCandidate best = null;
        ScreenHostNetworkManager.HostNetwork network =
                ScreenHostNetworkManager.networkFor(activeGroup);
        if (network != null && network.panoramic()) {
            // The simulated image spans all cable-connected physical surfaces. Search the entire
            // host canvas so its centre may legitimately resolve to a different face/group.
            for (ScreenGroup group : network.groups()) {
                ScreenHostNetworkManager.Surface surface = network.surface(group.groupId());
                if (surface == null) {
                    continue;
                }
                best = nearestDisplayedPoint(level, surface,
                        ScreenContentManager.profile(group.groupId()), best);
            }
            return best == null ? null : best.worldPoint();
        }

        ClientScreenProfile profile = ScreenContentManager.profile(activeGroup.groupId());
        ScreenGroup displayedGroup = activeGroup;
        if (!activeGroup.legacyAnchor()) {
            ScreenRegionLayout.Canvas canvas = ScreenRegionLayout.canvas(activeGroup, profile,
                    regionId);
            if (canvas == null) {
                return null;
            }
            displayedGroup = canvas.group();
        }
        int rotation = ScreenHostNetworkManager.rotationFor(activeGroup);
        ScreenHostNetworkManager.Surface unitSurface = new ScreenHostNetworkManager.Surface(
                displayedGroup, 0.0F, 0.0F, 1.0F, 1.0F, 0, 0, rotation,
                ScreenRotation.logicalWidth(displayedGroup.columns(), displayedGroup.rows(),
                        rotation),
                ScreenRotation.logicalHeight(displayedGroup.columns(), displayedGroup.rows(),
                        rotation));
        best = nearestDisplayedPoint(level, unitSurface, profile, null);
        return best == null ? null : best.worldPoint();
    }

    private static AimCandidate nearestDisplayedPoint(ClientLevel level,
            ScreenHostNetworkManager.Surface surface, ClientScreenProfile profile,
            AimCandidate currentBest) {
        ScreenGroup group = surface.group();
        if (group.legacyAnchor()) {
            if (!ScreenTileIndex.isLive(level, group.master(), group.facing())) {
                return currentBest;
            }
            return chooseCandidate(surface, -1, -1, null, currentBest);
        }

        net.minecraft.core.Direction right = ScreenGeometry.rightDirection(group.facing());
        net.minecraft.core.Direction up = ScreenGeometry.upDirection(group.facing());
        int originRight = ScreenGeometry.coordinate(group.origin(), right);
        int originUp = ScreenGeometry.coordinate(group.origin(), up);
        for (BlockPos tile : group.tiles()) {
            if (!ScreenTileIndex.isLive(level, tile, group.facing())
                    || profile.disabledTiles.contains(tile.asLong())) {
                continue;
            }
            int column = ScreenGeometry.coordinate(tile, right) - originRight;
            int row = ScreenGeometry.coordinate(tile, up) - originUp;
            currentBest = chooseCandidate(surface, column, row, tile, currentBest);
        }
        return currentBest;
    }

    private static AimCandidate chooseCandidate(ScreenHostNetworkManager.Surface surface,
            int column, int row, BlockPos tile, AimCandidate currentBest) {
        ScreenGroup group = surface.group();
        double physicalLeft;
        double physicalRight;
        double physicalTop;
        double physicalBottom;
        if (tile == null) {
            physicalLeft = 0.0D;
            physicalRight = 1.0D;
            physicalTop = 0.0D;
            physicalBottom = 1.0D;
        } else {
            physicalLeft = column / (double) group.columns();
            physicalRight = (column + 1.0D) / group.columns();
            physicalTop = 1.0D - (row + 1.0D) / group.rows();
            physicalBottom = 1.0D - row / (double) group.rows();
        }

        double[] first = canvasPoint(surface, physicalLeft, physicalTop);
        double[] second = canvasPoint(surface, physicalRight, physicalTop);
        double[] third = canvasPoint(surface, physicalRight, physicalBottom);
        double[] fourth = canvasPoint(surface, physicalLeft, physicalBottom);
        double left = Math.min(Math.min(first[0], second[0]), Math.min(third[0], fourth[0]));
        double right = Math.max(Math.max(first[0], second[0]), Math.max(third[0], fourth[0]));
        double top = Math.min(Math.min(first[1], second[1]), Math.min(third[1], fourth[1]));
        double bottom = Math.max(Math.max(first[1], second[1]), Math.max(third[1], fourth[1]));

        double closedU = clamp(0.5D, left, right);
        double closedV = clamp(0.5D, top, bottom);
        double distance = square(closedU - 0.5D) + square(closedV - 0.5D);
        // Keep the selected point just inside its real tile. Exact shared edges can otherwise be
        // rounded into an adjacent empty/disabled cell by ScreenRaycast's floor operation.
        double marginU = Math.min(0.0001D, Math.max(0.0D, right - left) * 0.05D);
        double marginV = Math.min(0.0001D, Math.max(0.0D, bottom - top) * 0.05D);
        double canvasU = clamp(0.5D, left + marginU, right - marginU);
        double canvasV = clamp(0.5D, top + marginV, bottom - marginV);
        AimCandidate candidate = new AimCandidate(surface, column, row, tile, canvasU, canvasV,
                distance);
        return candidate.betterThan(currentBest) ? candidate : currentBest;
    }

    private static double[] canvasPoint(ScreenHostNetworkManager.Surface surface,
            double physicalU, double physicalV) {
        return new double[] {surface.canvasU((float) physicalU, (float) physicalV),
                surface.canvasV((float) physicalU, (float) physicalV)};
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (maximum < minimum) {
            return (minimum + maximum) * 0.5D;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double square(double value) {
        return value * value;
    }

    private record AimCandidate(ScreenHostNetworkManager.Surface surface, int column, int row,
            BlockPos tile, double canvasU, double canvasV, double distance) {
        private boolean betterThan(AimCandidate other) {
            if (other == null || distance < other.distance - 1.0E-12D) {
                return true;
            }
            if (Math.abs(distance - other.distance) > 1.0E-12D) {
                return false;
            }
            int groupOrder = surface.group().groupId().compareTo(other.surface.group().groupId());
            if (groupOrder != 0) {
                return groupOrder < 0;
            }
            if (tile == null || other.tile == null) {
                return tile != null && other.tile == null;
            }
            return Long.compare(tile.asLong(), other.tile.asLong()) < 0;
        }

        private Vec3 worldPoint() {
            ScreenGroup group = surface.group();
            double physicalU = surface.physicalU((float) canvasU, (float) canvasV);
            double physicalV = surface.physicalV((float) canvasU, (float) canvasV);
            if (tile != null) {
                double inset = 0.0001D;
                double minU = (column + inset) / group.columns();
                double maxU = (column + 1.0D - inset) / group.columns();
                double minV = 1.0D - (row + 1.0D - inset) / group.rows();
                double maxV = 1.0D - (row + inset) / group.rows();
                physicalU = clamp(physicalU, minU, maxU);
                physicalV = clamp(physicalV, minV, maxV);
            } else {
                physicalU = clamp(physicalU, 0.0001D, 0.9999D);
                physicalV = clamp(physicalV, 0.0001D, 0.9999D);
            }
            return ScreenGeometry.origin(group.origin(), group.facing())
                    .add(ScreenGeometry.right(group.facing())
                            .scale(physicalU * group.columns()))
                    .add(ScreenGeometry.up(group.facing())
                            .scale((1.0D - physicalV) * group.rows()));
        }
    }

    private static boolean pointerLockActive() {
        return keyboardFocus instanceof dev.minescreen.client.web.BrowserSession browser
                && browser.pointerLockRequested();
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
            if (keyboardFocus instanceof dev.minescreen.client.web.BrowserSession browser) {
                browser.cancelPointerLock();
            }
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
        ScreenContentManager.PanoramaRender joined = ScreenContentManager.panoramaFor(group);
        if (joined != null) {
            ClientScreenProfile profile = ScreenContentManager.profile(joined.network().rootGroupId());
            if (profile.contentType != ScreenContentType.WEB
                    && (profile.contentType != ScreenContentType.VNC || profile.vncReadOnly)) {
                return null;
            }
            ScreenContentManager.sourceFor(group);
            return ScreenContentManager.session(group.groupId()) instanceof ScreenInputTarget target
                    ? target : null;
        }
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

    private record ActiveTarget(java.util.UUID groupId, int regionId,
            ScreenInputTarget target, int x, int y) {
    }
}
