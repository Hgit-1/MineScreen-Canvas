package dev.minescreen.client;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ScreenResolution;
import dev.minescreen.client.content.WebSplitLayout;
import dev.minescreen.client.video.VideoPlaybackSession;
import dev.minescreen.client.video.VideoSource;
import dev.minescreen.client.vnc.RfbEndpoint;
import dev.minescreen.client.vnc.VncScreenSession;
import dev.minescreen.client.web.BrowserRequestPolicy;
import dev.minescreen.client.web.BrowserSession;
import dev.minescreen.client.web.McefBrowserSession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Bounded two-stage loader. URL/file/DNS preflight runs on one daemon worker; at most one prepared
 * backend is finalized on the client thread per tick. A global session/pixel reservation prevents
 * a multi-screen host from creating an unbounded Chromium/NativeImage burst.
 */
final class AsyncContentSession implements BrowserSession {
    private static final int MAX_PENDING_PREFLIGHTS = 8;
    private static final ThreadPoolExecutor PREFLIGHT = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_PENDING_PREFLIGHTS), runnable -> {
                Thread thread = new Thread(runnable, "minescreen-content-preflight");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final java.util.Map<AsyncContentSession, Long> RESERVATIONS =
            new java.util.IdentityHashMap<>();
    private static boolean finalizedThisTick;

    private ScreenGroup group;
    private final ScreenGroup stateGroup;
    private final UUID credentialGroupId;
    private final ClientScreenProfile profile;
    private final CompletableFuture<Prepared> prepared;
    private ScreenContentSession delegate;
    private boolean closed;
    private boolean reserved;
    private boolean failureReported;
    private String errorMessage;

    AsyncContentSession(ScreenGroup group, ClientScreenProfile profile, ScreenGroup stateGroup,
            UUID credentialGroupId) {
        this.group = group;
        this.stateGroup = stateGroup == null ? group : stateGroup;
        this.credentialGroupId = credentialGroupId == null ? group.groupId() : credentialGroupId;
        this.profile = profile.copy();
        prepared = submit(this.profile);
    }

    static void beginClientTick() {
        finalizedThisTick = false;
    }

    private static CompletableFuture<Prepared> submit(ClientScreenProfile profile) {
        CompletableFuture<Prepared> future = new CompletableFuture<>();
        try {
            PREFLIGHT.execute(() -> {
                try {
                    future.complete(preflight(profile));
                } catch (Throwable exception) {
                    future.completeExceptionally(exception);
                }
            });
        } catch (RejectedExecutionException rejected) {
            future.completeExceptionally(new IllegalStateException(
                    "Asynchronous content load queue is full (maximum "
                            + MAX_PENDING_PREFLIGHTS + ")", rejected));
        }
        return future;
    }

    private static Prepared preflight(ClientScreenProfile input) {
        ClientScreenProfile checked = input.copy();
        VideoSource video = null;
        RfbEndpoint vnc = null;
        switch (checked.contentType) {
            case VIDEO -> video = VideoSource.resolve(checked.source);
            case WEB -> {
                if (!BrowserRequestPolicy.isAllowed(checked.source)) {
                    throw new IllegalStateException("WEB URL blocked by MineScreen network policy");
                }
                checked.webTabs = checked.webTabs == null ? new java.util.ArrayList<>()
                        : checked.webTabs.stream().filter(java.util.Objects::nonNull)
                                .filter(url -> !url.equals(checked.source))
                                .filter(BrowserRequestPolicy::isAllowed).distinct()
                                .limit(Math.max(0,
                                        MineScreenConfig.MAX_WEB_TABS_PER_SESSION.get() - 1L))
                                .collect(java.util.stream.Collectors.toCollection(
                                        java.util.ArrayList::new));
            }
            case VNC -> {
                vnc = RfbEndpoint.parse(checked.source);
                if (!BrowserRequestPolicy.isAllowed(vnc.policyUrl())) {
                    throw new IllegalStateException("VNC endpoint blocked by MineScreen network policy");
                }
            }
            case IDLE -> {
            }
        }
        return new Prepared(checked, video, vnc);
    }

    @Override
    public ScreenContentType type() {
        return profile.contentType;
    }

    @Override
    public ScreenRenderSource renderSource() {
        return delegate == null ? ScreenTextureManager.idleRenderSource() : delegate.renderSource();
    }

    @Override
    public void tick(ScreenGroup nextGroup) {
        group = nextGroup;
        if (delegate != null) {
            delegate.tick(nextGroup);
            String backendError = delegate.errorMessage();
            if (backendError != null && !backendError.isBlank()) {
                errorMessage = backendError;
                reportFailure();
            }
            return;
        }
        if (closed || !prepared.isDone() || finalizedThisTick || !reserveIfPossible()) {
            reportFailure();
            return;
        }
        finalizedThisTick = true;
        try {
            Prepared result = prepared.join();
            delegate = switch (result.profile().contentType) {
                case VIDEO -> new VideoPlaybackSession(group, result.profile(), result.video());
                case WEB -> new McefBrowserSession(group, result.profile(), true, stateGroup);
                case VNC -> new VncScreenSession(group, result.profile(), result.vnc(),
                        credentialGroupId, true);
                case IDLE -> null;
            };
        } catch (Throwable exception) {
            releaseReservation();
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            errorMessage = cause.getMessage() == null ? cause.getClass().getSimpleName()
                    : cause.getMessage();
            reportFailure();
        }
    }

    private boolean reserveIfPossible() {
        if (reserved) {
            return true;
        }
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        long pixels = (long) dimensions[0] * dimensions[1];
        synchronized (RESERVATIONS) {
            long used = RESERVATIONS.values().stream().mapToLong(Long::longValue).sum();
            int maxSessions = MineScreenConfig.MAX_ACTIVE_CONTENT_SESSIONS.get();
            long maxPixels = MineScreenConfig.MAX_ACTIVE_CANVAS_PIXELS.get();
            if (RESERVATIONS.size() >= maxSessions
                    || !RESERVATIONS.isEmpty() && used + pixels > maxPixels) {
                return false;
            }
            RESERVATIONS.put(this, pixels);
            reserved = true;
            return true;
        }
    }

    private void releaseReservation() {
        if (!reserved) {
            return;
        }
        synchronized (RESERVATIONS) {
            RESERVATIONS.remove(this);
        }
        reserved = false;
    }

    private void reportFailure() {
        if (failureReported || errorMessage == null) {
            return;
        }
        failureReported = true;
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("screen.minescreen.content_failed", errorMessage), false);
        }
    }

    @Override
    public void resize(ScreenGroup nextGroup) {
        group = nextGroup;
        updateReservation();
        if (delegate != null) {
            delegate.resize(nextGroup);
        }
    }

    /** Keeps the aggregate budget accurate after a custom host layout changes canvas dimensions. */
    private void updateReservation() {
        if (!reserved) {
            return;
        }
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        long pixels = (long) dimensions[0] * dimensions[1];
        synchronized (RESERVATIONS) {
            if (RESERVATIONS.containsKey(this)) {
                RESERVATIONS.put(this, pixels);
            }
        }
    }

    @Override
    public long positionMs() {
        return delegate == null ? profile.positionMs : delegate.positionMs();
    }

    @Override
    public long durationMs() {
        return delegate == null ? 0L : delegate.durationMs();
    }

    @Override
    public void seek(long positionMs) {
        profile.positionMs = Math.max(0L, positionMs);
        if (delegate != null) delegate.seek(positionMs);
    }

    @Override
    public void setPaused(boolean paused) {
        profile.paused = paused;
        if (delegate != null) delegate.setPaused(paused);
    }

    @Override
    public void setVolume(float volume) {
        profile.volume = Math.max(0.0F, Math.min(1.0F, volume));
        if (delegate != null) delegate.setVolume(profile.volume);
    }

    @Override
    public String errorMessage() {
        return errorMessage != null ? errorMessage
                : delegate == null ? null : delegate.errorMessage();
    }

    ScreenContentSession loadedDelegate() {
        return delegate;
    }

    @Override
    public int inputWidth() {
        return delegate instanceof ScreenInputTarget target ? target.inputWidth() : group.canvasWidth();
    }

    @Override
    public int inputHeight() {
        return delegate instanceof ScreenInputTarget target ? target.inputHeight() : group.canvasHeight();
    }

    @Override
    public void focus(boolean focused) {
        if (delegate instanceof ScreenInputTarget target) target.focus(focused);
    }

    @Override
    public void mouseMove(int x, int y) {
        if (delegate instanceof ScreenInputTarget target) target.mouseMove(x, y);
    }

    @Override
    public void mousePress(int x, int y, int button) {
        if (delegate instanceof ScreenInputTarget target) target.mousePress(x, y, button);
    }

    @Override
    public void mouseRelease(int x, int y, int button) {
        if (delegate instanceof ScreenInputTarget target) target.mouseRelease(x, y, button);
    }

    @Override
    public void mouseWheel(int x, int y, double amount, int modifiers) {
        if (delegate instanceof ScreenInputTarget target) target.mouseWheel(x, y, amount, modifiers);
    }

    @Override
    public void keyPress(int keyCode, long scanCode, int modifiers) {
        if (delegate instanceof ScreenInputTarget target) target.keyPress(keyCode, scanCode, modifiers);
    }

    @Override
    public void keyRelease(int keyCode, long scanCode, int modifiers) {
        if (delegate instanceof ScreenInputTarget target) target.keyRelease(keyCode, scanCode, modifiers);
    }

    @Override
    public void keyTyped(char character, int modifiers) {
        if (delegate instanceof ScreenInputTarget target) target.keyTyped(character, modifiers);
    }

    private BrowserSession browser() {
        return delegate instanceof BrowserSession browser ? browser : null;
    }

    @Override public String currentUrl() { return browser() == null ? profile.source : browser().currentUrl(); }
    @Override public boolean navigate(String url) { return browser() != null && browser().navigate(url); }
    @Override public void goBack() { if (browser() != null) browser().goBack(); }
    @Override public void goForward() { if (browser() != null) browser().goForward(); }
    @Override public void reload() { if (browser() != null) browser().reload(); }
    @Override public boolean canGoBack() { return browser() != null && browser().canGoBack(); }
    @Override public boolean canGoForward() { return browser() != null && browser().canGoForward(); }
    @Override public List<TabInfo> tabs() { return browser() == null ? List.of() : browser().tabs(); }
    @Override public List<String> restorableUrls() {
        return browser() == null ? List.of(profile.source) : browser().restorableUrls();
    }
    @Override public String restorableActiveUrl() {
        return browser() == null ? profile.source : browser().restorableActiveUrl();
    }
    @Override public int activeTabIndex() { return browser() == null ? -1 : browser().activeTabIndex(); }
    @Override public boolean openTab(String url, boolean activate) {
        return browser() != null && browser().openTab(url, activate);
    }
    @Override public void activateTab(int index) { if (browser() != null) browser().activateTab(index); }
    @Override public void closeTab(int index) { if (browser() != null) browser().closeTab(index); }
    @Override public WebSplitLayout splitLayout() {
        return browser() == null ? profile.webSplitLayout : browser().splitLayout();
    }
    @Override public void setSplitLayout(WebSplitLayout layout) {
        profile.webSplitLayout = layout;
        if (browser() != null) browser().setSplitLayout(layout);
    }
    @Override public boolean pointerLockRequested() {
        return browser() != null && browser().pointerLockRequested();
    }
    @Override public void relativeMouseMove(double deltaX, double deltaY) {
        if (browser() != null) browser().relativeMouseMove(deltaX, deltaY);
    }
    @Override public void cancelPointerLock() {
        if (browser() != null) browser().cancelPointerLock();
    }

    @Override
    public void close() {
        closed = true;
        prepared.cancel(true);
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
        releaseReservation();
    }

    private record Prepared(ClientScreenProfile profile, VideoSource video, RfbEndpoint vnc) {
    }
}
