package dev.minescreen.client.web;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minescreen.MineScreenClientConfig;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.client.ScreenVisibility;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ScreenResolution;
import dev.minescreen.client.content.WebSplitLayout;
import net.minecraft.client.Minecraft;

/**
 * Muted emergency WEB backend using an existing Chromium process. "CDP" is deliberately kept out
 * of user-visible strings; it is only the loopback transport between MineScreen and the process.
 */
public final class ExternalBrowserSession implements BrowserSession {
    private static final ExecutorService FRAMES = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "minescreen-browser-frame");
        thread.setDaemon(true);
        return thread;
    });
    private static final String TAB_HOOK = """
            (() => {
              if (window.__mineScreenExternalHook) return;
              Object.defineProperty(window, '__mineScreenExternalHook', {value:true});
              const send = value => {
                if (!value) return;
                try { minescreenOpenTab(new URL(String(value), document.baseURI).href); } catch (_) {}
              };
              window.open = function(url) { send(url); return null; };
              document.addEventListener('click', event => {
                const a = event.target && event.target.closest ? event.target.closest('a[href]') : null;
                if (a && (a.getAttribute('target') || '').toLowerCase() === '_blank') {
                  event.preventDefault(); event.stopImmediatePropagation(); send(a.href);
                }
              }, true);
              let locked = false;
              const change = value => { locked=value; try { minescreenPointerLock(value?'1':'0'); } catch(_){}
                Promise.resolve().then(() => document.dispatchEvent(new Event('pointerlockchange'))); };
              try { Object.defineProperty(document, 'pointerLockElement', {configurable:true,
                get:()=>locked ? document.documentElement : null}); } catch(_){}
              Element.prototype.requestPointerLock = function(){ change(true); return Promise.resolve(); };
              Document.prototype.exitPointerLock = function(){ change(false); };
            })();
            """;

    private final ClientScreenProfile profile;
    private final Path executable;
    private ExternalBrowserProcess process;
    private final List<Tab> tabs = new ArrayList<>();
    private ScreenGroup group;
    private WebSplitLayout splitLayout;
    private int activeTab = -1;
    private int splitWindowStart;
    private int width;
    private int height;
    private boolean focused;
    private boolean closed;
    private boolean pointerLocked;
    private String errorMessage;
    private int restartAttempts;
    private long restartAfterNanos;

    public ExternalBrowserSession(ScreenGroup group, ClientScreenProfile profile, Path executable) {
        this.group = group;
        this.profile = profile.copy();
        this.executable = executable;
        splitLayout = profile.webSplitLayout == null ? WebSplitLayout.SINGLE : profile.webSplitLayout;
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        double scale = Math.min(1.0D, MineScreenClientConfig.EXTERNAL_WEB_MAX_WIDTH.get()
                / (double) Math.max(1, dimensions[0]));
        width = Math.max(1, (int) Math.round(dimensions[0] * scale));
        height = Math.max(1, (int) Math.round(dimensions[1] * scale));
        process = new ExternalBrowserProcess(executable);
        createTab(profile.source, true);
        if (profile.webTabs != null) {
            profile.webTabs.stream().filter(url -> url != null && !url.equals(profile.source))
                    .filter(NetworkRequestPolicy::isAllowed)
                    .limit(Math.max(0, MineScreenConfig.MAX_WEB_TABS_PER_SESSION.get() - 1L))
                    .forEach(url -> createTab(url, false));
        }
        applyLayout();
    }

    @Override public ScreenContentType type() { return ScreenContentType.WEB; }

    @Override
    public ScreenRenderSource renderSource() {
        List<Integer> indices = visibleTabIndices();
        List<float[]> bounds = paneBounds(splitLayout, indices.size());
        List<ScreenRenderSource.Pane> panes = new ArrayList<>();
        for (int pane = 0; pane < indices.size(); pane++) {
            Tab tab = tabs.get(indices.get(pane));
            ScreenRenderSource frame = tab.frames.renderSource();
            ScreenRenderSource source = frame == null ? tab.status.renderSource() : frame;
            float[] area = bounds.get(pane);
            panes.add(new ScreenRenderSource.Pane(area[0], area[1], area[2], area[3],
                    source.renderType(), source::bind, false));
        }
        return panes.isEmpty() ? dev.minescreen.client.ScreenTextureManager.idleRenderSource()
                : new ScreenRenderSource(panes);
    }

    @Override
    public void tick(ScreenGroup group) {
        this.group = group;
        if ((process == null || !process.alive()) && !closed) {
            scheduleOrRestartBrowser();
        }
        boolean visible = ScreenVisibility.evaluate(group).active();
        for (int index = 0; index < tabs.size(); index++) {
            Tab tab = tabs.get(index);
            boolean tabVisible = visible && visibleTabIndices().contains(index);
            setScreencast(tab, tabVisible);
            if (tabVisible && tab.screenshotFallback) {
                captureFallbackFrame(tab);
            }
        }
    }

    private void scheduleOrRestartBrowser() {
        long now = System.nanoTime();
        if (restartAttempts >= 2) {
            if (errorMessage == null) {
                errorMessage = "Compatibility browser stopped after repeated failures";
                tabs.forEach(tab -> tab.status.error(tab.url, 0, errorMessage));
            }
            return;
        }
        if (restartAfterNanos == 0L) {
            restartAfterNanos = now + TimeUnit.SECONDS.toNanos(restartAttempts == 0 ? 2L : 8L);
            tabs.forEach(tab -> tab.status.loading(tab.url));
            return;
        }
        if (now < restartAfterNanos) return;
        restartAfterNanos = 0L;
        restartAttempts++;
        List<String> urls = tabs.stream().map(tab -> tab.url).toList();
        int selected = Math.max(0, Math.min(activeTab, Math.max(0, urls.size() - 1)));
        ExternalBrowserProcess oldProcess = process;
        ExternalBrowserProcess replacement;
        try {
            replacement = new ExternalBrowserProcess(executable);
        } catch (RuntimeException failure) {
            errorMessage = failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            tabs.forEach(tab -> tab.status.error(tab.url, 0, errorMessage));
            restartAfterNanos = now + TimeUnit.SECONDS.toNanos(8L);
            return;
        }
        try {
            for (Tab tab : List.copyOf(tabs)) destroyTab(tab);
            tabs.clear();
            if (oldProcess != null) oldProcess.close();
            process = replacement;
            for (int index = 0; index < urls.size(); index++) {
                createTab(urls.get(index), index == selected);
            }
            if (urls.isEmpty()) createTab(profile.source, true);
            errorMessage = null;
            applyLayout();
        } catch (RuntimeException failure) {
            replacement.close();
            errorMessage = failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            restartAfterNanos = now + TimeUnit.SECONDS.toNanos(8L);
        }
    }

    @Override
    public void resize(ScreenGroup group) {
        this.group = group;
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        double scale = Math.min(1.0D, MineScreenClientConfig.EXTERNAL_WEB_MAX_WIDTH.get()
                / (double) Math.max(1, dimensions[0]));
        int nextWidth = Math.max(1, (int) Math.round(dimensions[0] * scale));
        int nextHeight = Math.max(1, (int) Math.round(dimensions[1] * scale));
        if (nextWidth != width || nextHeight != height) {
            width = nextWidth;
            height = nextHeight;
            applyLayout();
        }
    }

    @Override public String currentUrl() { return active().url; }

    @Override
    public boolean navigate(String url) {
        if (!NetworkRequestPolicy.isAllowed(url)) return false;
        Tab tab = active();
        tab.status.loading(url);
        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        tab.cdp.send("Page.navigate", params).exceptionally(failure -> {
            fail(tab, failure); return null;
        });
        return true;
    }

    @Override public void goBack() { history(-1); }
    @Override public void goForward() { history(1); }

    private void history(int direction) {
        Tab tab = active();
        tab.cdp.send("Page.getNavigationHistory").thenAccept(history -> {
            int current = history.get("currentIndex").getAsInt();
            JsonArray entries = history.getAsJsonArray("entries");
            int target = current + direction;
            if (target >= 0 && target < entries.size()) {
                JsonObject params = new JsonObject();
                params.addProperty("entryId", entries.get(target).getAsJsonObject()
                        .get("id").getAsInt());
                tab.status.loading(tab.url);
                tab.cdp.send("Page.navigateToHistoryEntry", params);
            }
        }).exceptionally(failure -> { fail(tab, failure); return null; });
    }

    @Override
    public void reload() {
        active().status.loading(active().url);
        active().cdp.send("Page.reload");
    }

    @Override public boolean canGoBack() { return true; }
    @Override public boolean canGoForward() { return true; }

    @Override
    public List<TabInfo> tabs() {
        List<TabInfo> result = new ArrayList<>(tabs.size());
        for (int index = 0; index < tabs.size(); index++) {
            Tab tab = tabs.get(index);
            result.add(new TabInfo(index, title(tab.url), tab.url, index == activeTab));
        }
        return List.copyOf(result);
    }

    @Override public int activeTabIndex() { return activeTab; }

    @Override
    public boolean openTab(String url, boolean activate) {
        if (!NetworkRequestPolicy.isAllowed(url) || tabs.size() >= maxTabs()) return false;
        createTab(url, activate);
        applyLayout();
        return true;
    }

    @Override
    public void activateTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        activeTab = index;
        ensureActiveTabVisible();
        applyLayout();
        active().cdp.send("Page.bringToFront");
    }

    @Override
    public void closeTab(int index) {
        if (tabs.size() <= 1 || index < 0 || index >= tabs.size()) return;
        destroyTab(tabs.remove(index));
        if (activeTab >= tabs.size()) activeTab = tabs.size() - 1;
        else if (index < activeTab) activeTab--;
        ensureActiveTabVisible();
        applyLayout();
    }

    @Override public WebSplitLayout splitLayout() { return splitLayout; }

    @Override
    public void setSplitLayout(WebSplitLayout layout) {
        splitLayout = layout == null ? WebSplitLayout.SINGLE : layout;
        applyLayout();
    }

    @Override public int inputWidth() { return width; }
    @Override public int inputHeight() { return height; }

    @Override
    public void focus(boolean focused) {
        this.focused = focused;
        JsonObject params = new JsonObject();
        params.addProperty("expression", focused ? "window.focus()" : "window.blur()");
        active().cdp.send("Runtime.evaluate", params);
    }

    @Override public void mouseMove(int x, int y) { mouse("mouseMoved", x, y, -1, 0, 0.0D); }
    @Override public void mousePress(int x, int y, int button) { mouse("mousePressed", x, y, button, 1, 0.0D); }
    @Override public void mouseRelease(int x, int y, int button) { mouse("mouseReleased", x, y, button, 1, 0.0D); }

    @Override
    public void mouseWheel(int x, int y, double amount, int modifiers) {
        mouse("mouseWheel", x, y, -1, 0, -amount * 53.0D);
    }

    private void mouse(String type, int x, int y, int button, int clickCount, double wheel) {
        PaneTarget target = paneTarget(x, y);
        if (target == null) return;
        if (type.equals("mousePressed")) activateTab(target.index);
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.addProperty("x", target.x);
        params.addProperty("y", target.y);
        if (button >= 0) params.addProperty("button", mouseButton(button));
        if (clickCount > 0) params.addProperty("clickCount", clickCount);
        if (type.equals("mouseWheel")) {
            params.addProperty("deltaX", 0);
            params.addProperty("deltaY", wheel);
        }
        target.tab.cdp.send("Input.dispatchMouseEvent", params);
    }

    @Override public void keyPress(int keyCode, long scanCode, int modifiers) { key("rawKeyDown", keyCode, modifiers, null); }
    @Override public void keyRelease(int keyCode, long scanCode, int modifiers) { key("keyUp", keyCode, modifiers, null); }
    @Override public void keyTyped(char character, int modifiers) { key("char", character, modifiers, String.valueOf(character)); }

    private void key(String type, int keyCode, int modifiers, String text) {
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.addProperty("windowsVirtualKeyCode", virtualKey(keyCode));
        params.addProperty("nativeVirtualKeyCode", virtualKey(keyCode));
        params.addProperty("modifiers", cdpModifiers(modifiers));
        if (text != null) params.addProperty("text", text);
        active().cdp.send("Input.dispatchKeyEvent", params);
    }

    @Override public boolean pointerLockRequested() { return pointerLocked; }

    @Override
    public void relativeMouseMove(double deltaX, double deltaY) {
        JsonObject params = new JsonObject();
        params.addProperty("type", "mouseMoved");
        params.addProperty("x", width / 2);
        params.addProperty("y", height / 2);
        params.addProperty("deltaX", deltaX);
        params.addProperty("deltaY", deltaY);
        active().cdp.send("Input.dispatchMouseEvent", params);
    }

    @Override
    public void cancelPointerLock() {
        pointerLocked = false;
        JsonObject params = new JsonObject();
        params.addProperty("expression", "document.exitPointerLock && document.exitPointerLock()");
        active().cdp.send("Runtime.evaluate", params);
    }

    @Override public String errorMessage() { return errorMessage; }

    private void createTab(String url, boolean activate) {
        ExternalBrowserProcess.PageTarget target = process.openPage("about:blank");
        Tab tab = new Tab(target.id(), url, new PeerFrameTexture(UUID.randomUUID(), FRAMES),
                new BrowserStatusTexture(group.groupId(), width, height));
        tab.status.loading(url);
        tab.cdp = new CdpConnection(target.webSocketEndpoint(),
                (method, params) -> event(tab, method, params));
        tabs.add(tab);
        initialize(tab, url);
        if (activate || activeTab < 0) activeTab = tabs.size() - 1;
    }

    private void initialize(Tab tab, String url) {
        tab.cdp.send("Page.enable");
        tab.cdp.send("Network.enable");
        tab.cdp.send("Runtime.enable");
        JsonObject fetch = new JsonObject();
        JsonArray patterns = new JsonArray();
        JsonObject allRequests = new JsonObject();
        allRequests.addProperty("urlPattern", "*");
        allRequests.addProperty("requestStage", "Request");
        patterns.add(allRequests);
        fetch.add("patterns", patterns);
        tab.cdp.send("Fetch.enable", fetch);
        JsonObject binding = new JsonObject();
        binding.addProperty("name", "minescreenOpenTab");
        tab.cdp.send("Runtime.addBinding", binding);
        binding = new JsonObject();
        binding.addProperty("name", "minescreenPointerLock");
        tab.cdp.send("Runtime.addBinding", binding);
        JsonObject hook = new JsonObject();
        hook.addProperty("source", TAB_HOOK);
        tab.cdp.send("Page.addScriptToEvaluateOnNewDocument", hook);
        resize(tab, width, height);
        navigateTab(tab, url);
    }

    private void navigateTab(Tab tab, String url) {
        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        tab.cdp.send("Page.navigate", params).exceptionally(failure -> {
            fail(tab, failure); return null;
        });
    }

    private void event(Tab tab, String method, JsonObject params) {
        if (closed) return;
        switch (method) {
            case "Page.screencastFrame" -> {
                byte[] jpeg = Base64.getDecoder().decode(params.get("data").getAsString());
                tab.frames.accept(jpeg);
                tab.status.ready(tab.url);
                JsonObject ack = new JsonObject();
                ack.addProperty("sessionId", params.get("sessionId").getAsInt());
                tab.cdp.send("Page.screencastFrameAck", ack);
            }
            case "Page.frameNavigated" -> {
                JsonObject frame = params.getAsJsonObject("frame");
                if (!frame.has("parentId") && frame.has("url")) {
                    String next = frame.get("url").getAsString();
                    if (NetworkRequestPolicy.isAllowedRequest(next)) tab.url = next;
                }
            }
            case "Page.loadEventFired" -> tab.status.ready(tab.url);
            case "Network.responseReceived" -> {
                JsonObject response = params.getAsJsonObject("response");
                if (response != null && response.has("url") && response.has("remoteIPAddress")
                        && !NetworkRequestPolicy.isAllowedResolvedAddress(
                                response.get("url").getAsString(),
                                response.get("remoteIPAddress").getAsString())) {
                    tab.cdp.send("Page.stopLoading");
                    tab.status.error(response.get("url").getAsString(), 403,
                            "Blocked after resolved-address verification");
                }
            }
            case "Fetch.requestPaused" -> {
                String requestId = params.get("requestId").getAsString();
                String requestUrl = params.getAsJsonObject("request").get("url").getAsString();
                JsonObject decision = new JsonObject();
                decision.addProperty("requestId", requestId);
                if (NetworkRequestPolicy.isAllowedRequest(requestUrl)) {
                    tab.cdp.send("Fetch.continueRequest", decision);
                } else {
                    decision.addProperty("errorReason", "BlockedByClient");
                    tab.cdp.send("Fetch.failRequest", decision);
                    if (params.has("resourceType") && "Document".equals(
                            params.get("resourceType").getAsString())) {
                        tab.status.error(requestUrl, 403, "Blocked by MineScreen network policy");
                    }
                }
            }
            case "Runtime.bindingCalled" -> {
                String name = params.get("name").getAsString();
                String payload = params.get("payload").getAsString();
                Minecraft.getInstance().execute(() -> {
                    if (name.equals("minescreenOpenTab")) openTab(payload, true);
                    else if (name.equals("minescreenPointerLock")) {
                        pointerLocked = payload.startsWith("1");
                        if (pointerLocked) dev.minescreen.client.ClientInput.centerViewForPointerLock();
                    }
                });
            }
            default -> {
            }
        }
    }

    private void setScreencast(Tab tab, boolean enabled) {
        if (tab.screencasting == enabled) return;
        tab.screencasting = enabled;
        if (enabled) {
            if (tab.screenshotFallback) return;
            JsonObject params = new JsonObject();
            params.addProperty("format", "jpeg");
            params.addProperty("quality", MineScreenClientConfig.EXTERNAL_WEB_JPEG_QUALITY.get());
            params.addProperty("maxWidth", Math.max(1, tab.viewportWidth));
            params.addProperty("maxHeight", Math.max(1, tab.viewportHeight));
            int everyNth = Math.max(1, 60 / MineScreenClientConfig.EXTERNAL_WEB_MAX_FPS.get());
            params.addProperty("everyNthFrame", everyNth);
            tab.cdp.send("Page.startScreencast", params).exceptionally(failure -> {
                // Older Chromium builds (notably emergency Win7 choices) may not implement
                // screencasting. Keep the page alive and switch to rate-limited screenshots.
                tab.screenshotFallback = true;
                tab.status.loading(tab.url);
                return null;
            });
        } else {
            tab.cdp.send("Page.stopScreencast");
            tab.screenshotPending = false;
        }
    }

    private void captureFallbackFrame(Tab tab) {
        long now = System.nanoTime();
        long interval = 1_000_000_000L
                / Math.max(1, MineScreenClientConfig.EXTERNAL_WEB_MAX_FPS.get());
        if (tab.screenshotPending || now < tab.nextScreenshotNanos) return;
        tab.screenshotPending = true;
        tab.nextScreenshotNanos = now + interval;
        JsonObject params = new JsonObject();
        params.addProperty("format", "jpeg");
        params.addProperty("quality", MineScreenClientConfig.EXTERNAL_WEB_JPEG_QUALITY.get());
        params.addProperty("fromSurface", true);
        params.addProperty("captureBeyondViewport", false);
        tab.cdp.send("Page.captureScreenshot", params).thenAccept(result -> {
            if (result.has("data")) {
                tab.frames.accept(Base64.getDecoder().decode(result.get("data").getAsString()));
                tab.status.ready(tab.url);
            }
        }).exceptionally(failure -> {
            fail(tab, failure);
            return null;
        }).whenComplete((ignored, failure) -> tab.screenshotPending = false);
    }

    private void applyLayout() {
        if (tabs.isEmpty()) return;
        List<Integer> indices = visibleTabIndices();
        List<float[]> bounds = paneBounds(splitLayout, indices.size());
        for (int index = 0; index < tabs.size(); index++) {
            Tab tab = tabs.get(index);
            int pane = indices.indexOf(index);
            int targetWidth = 64;
            int targetHeight = 64;
            if (pane >= 0) {
                float[] area = bounds.get(pane);
                targetWidth = Math.max(1, Math.round(width * (area[2] - area[0])));
                targetHeight = Math.max(1, Math.round(height * (area[3] - area[1])));
            }
            resize(tab, targetWidth, targetHeight);
        }
    }

    private void resize(Tab tab, int targetWidth, int targetHeight) {
        if (tab.viewportWidth == targetWidth && tab.viewportHeight == targetHeight) return;
        tab.viewportWidth = targetWidth;
        tab.viewportHeight = targetHeight;
        tab.status.resize(targetWidth, targetHeight);
        JsonObject params = new JsonObject();
        params.addProperty("width", targetWidth);
        params.addProperty("height", targetHeight);
        params.addProperty("deviceScaleFactor", 1.0D);
        params.addProperty("mobile", false);
        tab.cdp.send("Emulation.setDeviceMetricsOverride", params);
        if (tab.screencasting) {
            tab.cdp.send("Page.stopScreencast");
            tab.screencasting = false;
        }
    }

    private List<Integer> visibleTabIndices() {
        if (tabs.isEmpty()) return List.of();
        if (splitLayout == WebSplitLayout.SINGLE) return List.of(Math.max(0, activeTab));
        int count = Math.min(splitLayout.paneCount(), tabs.size());
        ensureActiveTabVisible();
        List<Integer> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) result.add(splitWindowStart + index);
        return result;
    }

    private void ensureActiveTabVisible() {
        if (tabs.isEmpty() || splitLayout == WebSplitLayout.SINGLE) return;
        int count = Math.min(splitLayout.paneCount(), tabs.size());
        if (activeTab < splitWindowStart) splitWindowStart = activeTab;
        else if (activeTab >= splitWindowStart + count) splitWindowStart = activeTab - count + 1;
        splitWindowStart = Math.max(0, Math.min(splitWindowStart, tabs.size() - count));
    }

    private PaneTarget paneTarget(int x, int y) {
        float nx = Math.max(0.0F, Math.min(0.999999F, x / (float) Math.max(1, width)));
        float ny = Math.max(0.0F, Math.min(0.999999F, y / (float) Math.max(1, height)));
        List<Integer> indices = visibleTabIndices();
        List<float[]> bounds = paneBounds(splitLayout, indices.size());
        for (int pane = 0; pane < indices.size(); pane++) {
            float[] area = bounds.get(pane);
            if (nx >= area[0] && nx < area[2] && ny >= area[1] && ny < area[3]) {
                int index = indices.get(pane);
                Tab tab = tabs.get(index);
                int px = Math.max(0, Math.min(tab.viewportWidth - 1,
                        (int) ((nx - area[0]) / (area[2] - area[0]) * tab.viewportWidth)));
                int py = Math.max(0, Math.min(tab.viewportHeight - 1,
                        (int) ((ny - area[1]) / (area[3] - area[1]) * tab.viewportHeight)));
                return new PaneTarget(index, tab, px, py);
            }
        }
        return null;
    }

    private static List<float[]> paneBounds(WebSplitLayout layout, int count) {
        List<float[]> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(switch (layout) {
                case SINGLE -> new float[] {0, 0, 1, 1};
                case VERTICAL -> index == 0 ? new float[] {0, 0, .5F, 1} : new float[] {.5F, 0, 1, 1};
                case HORIZONTAL -> index == 0 ? new float[] {0, 0, 1, .5F} : new float[] {0, .5F, 1, 1};
                case QUAD -> switch (index) {
                    case 0 -> new float[] {0, 0, .5F, .5F};
                    case 1 -> new float[] {.5F, 0, 1, .5F};
                    case 2 -> new float[] {0, .5F, .5F, 1};
                    default -> new float[] {.5F, .5F, 1, 1};
                };
            });
        }
        return result;
    }

    private void fail(Tab tab, Throwable failure) {
        Throwable cause = failure == null ? null : failure.getCause() == null ? failure : failure.getCause();
        errorMessage = cause == null ? "Compatibility browser failed"
                : cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        tab.status.error(tab.url, 0, errorMessage);
    }

    private void destroyTab(Tab tab) {
        int texture = tab.frames.textureId();
        if (texture > 0) WebThumbnailCache.capture(group.groupId(), tab.url, texture,
                tab.frames.width(), tab.frames.height());
        tab.cdp.close();
        tab.frames.close();
        tab.status.close();
        process.closePage(tab.targetId);
    }

    @Override
    public void close() {
        closed = true;
        for (Tab tab : List.copyOf(tabs)) destroyTab(tab);
        tabs.clear();
        process.close();
    }

    private Tab active() {
        if (tabs.isEmpty()) throw new IllegalStateException("Browser has no tabs");
        return tabs.get(Math.max(0, Math.min(activeTab, tabs.size() - 1)));
    }

    private static int maxTabs() { return MineScreenConfig.MAX_WEB_TABS_PER_SESSION.get(); }

    private static String title(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? url : uri.getHost();
        } catch (RuntimeException ignored) {
            return url == null || url.isBlank() ? "New Tab" : url;
        }
    }

    private static String mouseButton(int button) {
        return switch (button) { case 0 -> "left"; case 1 -> "right"; case 2 -> "middle"; default -> "none"; };
    }

    private static int cdpModifiers(int glfw) {
        int result = 0;
        if ((glfw & org.lwjgl.glfw.GLFW.GLFW_MOD_ALT) != 0) result |= 1;
        if ((glfw & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0) result |= 2;
        if ((glfw & org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER) != 0) result |= 4;
        if ((glfw & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0) result |= 8;
        return result;
    }

    private static int virtualKey(int key) {
        return switch (key) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER -> 13;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_TAB -> 9;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> 8;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE -> 46;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> 27;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> 37;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> 38;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> 39;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> 40;
            default -> key;
        };
    }

    private static final class Tab {
        private final String targetId;
        private final PeerFrameTexture frames;
        private final BrowserStatusTexture status;
        private CdpConnection cdp;
        private String url;
        private int viewportWidth;
        private int viewportHeight;
        private boolean screencasting;
        private volatile boolean screenshotFallback;
        private volatile boolean screenshotPending;
        private volatile long nextScreenshotNanos;

        private Tab(String targetId, String url, PeerFrameTexture frames, BrowserStatusTexture status) {
            this.targetId = targetId;
            this.url = url;
            this.frames = frames;
            this.status = status;
        }
    }

    private record PaneTarget(int index, Tab tab, int x, int y) {
    }
}
