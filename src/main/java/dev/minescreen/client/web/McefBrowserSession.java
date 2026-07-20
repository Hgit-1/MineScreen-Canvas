package dev.minescreen.client.web;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.minescreen.ScreenGroup;
import dev.minescreen.client.ScreenRenderType;
import dev.minescreen.client.ScreenTextureManager;
import dev.minescreen.client.ScreenVisibility;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentType;
import dev.minescreen.client.content.ScreenRenderSource;
import dev.minescreen.client.content.ScreenResolution;
import net.minecraft.client.Minecraft;

/** One multi-tab off-screen Chromium session per joined screen group. */
public final class McefBrowserSession implements BrowserSession {
    private final List<Tab> tabs = new ArrayList<>();
    private final ClientScreenProfile profile;
    private final WebPeerSession peer;
    private ScreenGroup group;
    private int activeTab = -1;
    private int width;
    private int height;
    private boolean closed;
    private boolean focused;
    private String lastPopupUrl = "";
    private long lastPopupNanos;
    private int lastMouseX = -1;
    private int lastMouseY = -1;
    private String observedNavigationUrl = "";
    private String reportedNavigationUrl = "";
    private long navigationStableSinceNanos;

    public McefBrowserSession(ScreenGroup group, ClientScreenProfile profile) {
        this.group = group;
        this.profile = profile.copy();
        if (!BrowserRequestPolicy.isAllowed(profile.source)) {
            throw new IllegalStateException("URL blocked by MineScreen policy: " + profile.source);
        }
        if (!MCEF.isInitialized() && !MCEF.initialize()) {
            throw new IllegalStateException("MCEF failed to initialize");
        }
        BrowserRequestPolicy.install(MCEF.getClient());
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        width = dimensions[0];
        height = dimensions[1];
        reportedNavigationUrl = profile.source;
        createTab(profile.source, true);
        if (profile.webTabs != null) {
            profile.webTabs.stream().filter(url -> !url.equals(profile.source))
                    .filter(BrowserRequestPolicy::isAllowed).limit(15)
                    .forEach(url -> createTab(url, false));
        }
        peer = WebPeerService.open(group, this);
    }

    @Override
    public ScreenContentType type() {
        return ScreenContentType.WEB;
    }

    @Override
    public ScreenRenderSource renderSource() {
        if (peer != null) {
            ScreenRenderSource distributed = peer.renderSource();
            if (distributed != null) {
                return distributed;
            }
        }
        Tab tab = active();
        int textureId = tab.browser.getRenderer().getTextureID();
        if (textureId <= 0) {
            return ScreenTextureManager.idleRenderSource();
        }
        if (tab.lastTextureId > 0 && tab.lastTextureId != textureId) {
            ScreenRenderType.release(tab.lastTextureId);
        }
        tab.lastTextureId = textureId;
        return new ScreenRenderSource(ScreenRenderType.screen(textureId),
                () -> RenderSystem.setShaderTexture(0, textureId));
    }

    @Override
    public void tick(ScreenGroup group) {
        this.group = group;
        boolean visible = ScreenVisibility.evaluate(group).active();
        int activeTexture = active().browser.getRenderer().getTextureID();
        if (peer != null) {
            peer.tick(group, activeTexture, width, height, localTabs(), visible);
        }
        boolean distributed = peer != null && peer.usesRemoteFrame();
        for (int i = 0; i < tabs.size(); i++) {
            MCEFBrowser browser = tabs.get(i).browser;
            // MCEF browser controls use Minecraft's OS cursor as an independent pointer. World
            // screens deliberately have exactly one pointer -- the crosshair ray -- so keep those
            // controls and cursor callbacks disabled even if another integration changed them.
            browser.useBrowserControls(false);
            browser.setCursorChangeListener(null);
            browser.setWindowVisibility(!distributed && visible && i == activeTab);
        }
        if (!distributed) {
            synchronizeActiveNavigation(group);
        }
    }

    @Override
    public void resize(ScreenGroup group) {
        this.group = group;
        int[] dimensions = ScreenResolution.dimensions(group, profile);
        int nextWidth = dimensions[0];
        int nextHeight = dimensions[1];
        if (nextWidth != width || nextHeight != height) {
            width = nextWidth;
            height = nextHeight;
            tabs.forEach(tab -> tab.browser.resize(width, height));
        }
    }

    @Override
    public String currentUrl() {
        List<TabInfo> distributed = peer == null ? List.of() : peer.remoteTabs();
        if (!distributed.isEmpty()) {
            return distributed.stream().filter(TabInfo::active).findFirst()
                    .orElse(distributed.get(0)).url();
        }
        return active().browser.getURL();
    }

    @Override
    public boolean navigate(String url) {
        if (!BrowserRequestPolicy.isAllowed(url)) {
            return false;
        }
        if (peer != null && peer.sendCommand(output -> {
            output.writeByte(WebPeerSession.COMMAND_NAVIGATE);
            output.writeUTF(url);
        })) {
            return true;
        }
        return navigateLocal(url);
    }

    private boolean navigateLocal(String url) {
        active().browser.loadURL(url);
        return true;
    }

    @Override
    public void goBack() {
        if (peer != null && peer.sendCommand(output ->
                output.writeByte(WebPeerSession.COMMAND_BACK))) {
            return;
        }
        goBackLocal();
    }

    private void goBackLocal() {
        if (active().browser.canGoBack()) {
            active().browser.goBack();
        }
    }

    @Override
    public void goForward() {
        if (peer != null && peer.sendCommand(output ->
                output.writeByte(WebPeerSession.COMMAND_FORWARD))) {
            return;
        }
        goForwardLocal();
    }

    private void goForwardLocal() {
        if (active().browser.canGoForward()) {
            active().browser.goForward();
        }
    }

    @Override
    public void reload() {
        if (peer == null || !peer.sendCommand(output ->
                output.writeByte(WebPeerSession.COMMAND_RELOAD))) {
            active().browser.reload();
        }
    }

    @Override
    public boolean canGoBack() {
        return peer != null && peer.usesRemoteFrame() || active().browser.canGoBack();
    }

    @Override
    public boolean canGoForward() {
        return peer != null && peer.usesRemoteFrame() || active().browser.canGoForward();
    }

    @Override
    public List<TabInfo> tabs() {
        List<TabInfo> distributed = peer == null ? List.of() : peer.remoteTabs();
        return distributed.isEmpty() ? localTabs() : distributed;
    }

    List<TabInfo> localTabs() {
        List<TabInfo> result = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            String url = tabs.get(i).browser.getURL();
            result.add(new TabInfo(i, title(url), url == null ? "" : url, i == activeTab));
        }
        return List.copyOf(result);
    }

    @Override
    public int activeTabIndex() {
        int distributed = peer == null ? -1 : peer.remoteActiveTab();
        return distributed >= 0 ? distributed : activeTab;
    }

    @Override
    public boolean openTab(String url, boolean activate) {
        if (!BrowserRequestPolicy.isAllowed(url) || closed || tabs.size() >= 16) {
            return false;
        }
        if (peer != null && peer.sendCommand(output -> {
            output.writeByte(WebPeerSession.COMMAND_OPEN_TAB);
            output.writeUTF(url);
            output.writeBoolean(activate);
        })) {
            return true;
        }
        return openTabLocal(url, activate);
    }

    private boolean openTabLocal(String url, boolean activate) {
        createTab(url, activate);
        return true;
    }

    @Override
    public void activateTab(int index) {
        if (peer != null && peer.sendCommand(output -> {
            output.writeByte(WebPeerSession.COMMAND_ACTIVATE_TAB);
            output.writeInt(index);
        })) {
            return;
        }
        activateTabLocal(index);
    }

    private void activateTabLocal(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        if (activeTab >= 0 && activeTab < tabs.size() && activeTab != index) {
            tabs.get(activeTab).browser.setFocus(false);
        }
        activeTab = index;
        applyActiveTabState();
    }

    @Override
    public void closeTab(int index) {
        if (peer != null && peer.sendCommand(output -> {
            output.writeByte(WebPeerSession.COMMAND_CLOSE_TAB);
            output.writeInt(index);
        })) {
            return;
        }
        closeTabLocal(index);
    }

    private void closeTabLocal(int index) {
        if (tabs.size() <= 1 || index < 0 || index >= tabs.size()) {
            return;
        }
        Tab removed = tabs.remove(index);
        destroyTab(removed);
        if (activeTab >= tabs.size()) {
            activeTab = tabs.size() - 1;
        } else if (index < activeTab) {
            activeTab--;
        }
        applyActiveTabState();
    }

    @Override
    public int inputWidth() {
        return width;
    }

    @Override
    public int inputHeight() {
        return height;
    }

    @Override
    public void focus(boolean focused) {
        this.focused = focused;
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_FOCUS);
            output.writeBoolean(focused);
        })) {
            active().browser.setFocus(focused);
        }
    }

    @Override
    public void mouseMove(int x, int y) {
        lastMouseX = x;
        lastMouseY = y;
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_MOUSE_MOVE);
            output.writeInt(x);
            output.writeInt(y);
        })) {
            active().browser.sendMouseMove(x, y);
        }
    }

    @Override
    public void mousePress(int x, int y, int button) {
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_MOUSE_PRESS);
            output.writeInt(x);
            output.writeInt(y);
            output.writeInt(button);
        })) {
            active().browser.sendMousePress(x, y, button);
        }
    }

    @Override
    public void mouseRelease(int x, int y, int button) {
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_MOUSE_RELEASE);
            output.writeInt(x);
            output.writeInt(y);
            output.writeInt(button);
        })) {
            active().browser.sendMouseRelease(x, y, button);
        }
    }

    @Override
    public void mouseWheel(int x, int y, double amount, int modifiers) {
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_MOUSE_WHEEL);
            output.writeInt(x);
            output.writeInt(y);
            output.writeDouble(amount);
            output.writeInt(modifiers);
        })) {
            active().browser.sendMouseWheel(x, y, amount, modifiers);
        }
    }

    @Override
    public void keyPress(int keyCode, long scanCode, int modifiers) {
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_KEY_PRESS);
            output.writeInt(keyCode);
            output.writeLong(scanCode);
            output.writeInt(modifiers);
        })) {
            active().browser.sendKeyPress(keyCode, scanCode, modifiers);
        }
    }

    @Override
    public void keyRelease(int keyCode, long scanCode, int modifiers) {
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_KEY_RELEASE);
            output.writeInt(keyCode);
            output.writeLong(scanCode);
            output.writeInt(modifiers);
        })) {
            active().browser.sendKeyRelease(keyCode, scanCode, modifiers);
        }
    }

    @Override
    public void keyTyped(char character, int modifiers) {
        if (peer == null || !peer.sendInput(output -> {
            output.writeByte(WebPeerSession.INPUT_KEY_TYPED);
            output.writeChar(character);
            output.writeInt(modifiers);
        })) {
            active().browser.sendKeyTyped(character, modifiers);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (peer != null) {
            WebPeerService.close(peer, group);
        }
        tabs.forEach(this::destroyTab);
        tabs.clear();
    }

    private void createTab(String url, boolean activate) {
        MCEFBrowser browser = MCEF.createBrowser(url, false, width, height);
        // MCEF.createBrowser already creates the native browser. Calling createImmediately a
        // second time can leave navigation/renderer state associated with the wrong tab.
        browser.useBrowserControls(false);
        browser.setCursorChangeListener(null);
        browser.setWindowVisibility(false);
        Tab tab = new Tab(browser);
        tabs.add(tab);
        BrowserRequestPolicy.registerNewTabHandler(browser, this::queuePopupTab);
        BrowserRequestPolicy.installTabHooks(browser);
        if (activate) {
            activateTab(tabs.size() - 1);
        }
    }

    /** Applies an authenticated upstream action on the elected root browser, on the client thread. */
    void applyPeerMessage(byte[] message) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            int type = input.readUnsignedByte();
            int action = input.readUnsignedByte();
            if (type == WebPeerSession.MESSAGE_COMMAND) {
                switch (action) {
                    case WebPeerSession.COMMAND_NAVIGATE -> {
                        String url = input.readUTF();
                        if (BrowserRequestPolicy.isAllowed(url)) {
                            navigateLocal(url);
                        }
                    }
                    case WebPeerSession.COMMAND_BACK -> goBackLocal();
                    case WebPeerSession.COMMAND_FORWARD -> goForwardLocal();
                    case WebPeerSession.COMMAND_RELOAD -> active().browser.reload();
                    case WebPeerSession.COMMAND_OPEN_TAB -> {
                        String url = input.readUTF();
                        boolean activate = input.readBoolean();
                        if (BrowserRequestPolicy.isAllowed(url) && tabs.size() < 16) {
                            openTabLocal(url, activate);
                        }
                    }
                    case WebPeerSession.COMMAND_ACTIVATE_TAB -> activateTabLocal(input.readInt());
                    case WebPeerSession.COMMAND_CLOSE_TAB -> closeTabLocal(input.readInt());
                    default -> {
                    }
                }
            } else if (type == WebPeerSession.MESSAGE_INPUT) {
                switch (action) {
                    case WebPeerSession.INPUT_FOCUS -> active().browser.setFocus(input.readBoolean());
                    case WebPeerSession.INPUT_MOUSE_MOVE ->
                            active().browser.sendMouseMove(input.readInt(), input.readInt());
                    case WebPeerSession.INPUT_MOUSE_PRESS ->
                            active().browser.sendMousePress(input.readInt(), input.readInt(), input.readInt());
                    case WebPeerSession.INPUT_MOUSE_RELEASE ->
                            active().browser.sendMouseRelease(input.readInt(), input.readInt(), input.readInt());
                    case WebPeerSession.INPUT_MOUSE_WHEEL -> active().browser.sendMouseWheel(
                            input.readInt(), input.readInt(), input.readDouble(), input.readInt());
                    case WebPeerSession.INPUT_KEY_PRESS -> active().browser.sendKeyPress(
                            input.readInt(), input.readLong(), input.readInt());
                    case WebPeerSession.INPUT_KEY_RELEASE -> active().browser.sendKeyRelease(
                            input.readInt(), input.readLong(), input.readInt());
                    case WebPeerSession.INPUT_KEY_TYPED ->
                            active().browser.sendKeyTyped(input.readChar(), input.readInt());
                    default -> {
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void destroyTab(Tab tab) {
        BrowserRequestPolicy.unregisterNewTabHandler(tab.browser);
        tab.browser.setFocus(false);
        tab.browser.close(true);
        ScreenRenderType.release(tab.lastTextureId);
    }

    private void queuePopupTab(String target) {
        Minecraft.getInstance().execute(() -> {
            if (closed || target == null || target.isBlank()
                    || !BrowserRequestPolicy.isAllowedRequest(target)) {
                return;
            }
            long now = System.nanoTime();
            if (target.equals(lastPopupUrl)
                    && now - lastPopupNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500L)) {
                return;
            }
            lastPopupUrl = target;
            lastPopupNanos = now;
            if (tabs.size() < 16) {
                createTab(target, true);
            }
        });
    }

    private void synchronizeActiveNavigation(ScreenGroup group) {
        String url = currentUrl();
        if (url == null || url.isBlank() || url.equalsIgnoreCase("about:blank")
                || !BrowserRequestPolicy.isAllowedRequest(url)) {
            return;
        }
        long now = System.nanoTime();
        if (!url.equals(observedNavigationUrl)) {
            observedNavigationUrl = url;
            navigationStableSinceNanos = now;
            return;
        }
        // Collapse redirect chains and rapid SPA URL churn into one compact metadata packet.
        if (!url.equals(reportedNavigationUrl)
                && now - navigationStableSinceNanos
                        >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(750L)) {
            reportedNavigationUrl = url;
            dev.minescreen.client.network.ClientNetworkState.sendWebNavigation(group, url);
        }
    }

    /** Applies visibility/focus immediately instead of waiting for the next content tick. */
    private void applyActiveTabState() {
        for (int i = 0; i < tabs.size(); i++) {
            MCEFBrowser browser = tabs.get(i).browser;
            boolean active = i == activeTab;
            browser.useBrowserControls(false);
            browser.setCursorChangeListener(null);
            browser.setWindowVisibility(active);
            browser.setFocus(active && focused);
        }
        if (lastMouseX >= 0 && lastMouseY >= 0) {
            active().browser.sendMouseMove(lastMouseX, lastMouseY);
        }
    }

    private Tab active() {
        if (tabs.isEmpty()) {
            throw new IllegalStateException("Browser has no tabs");
        }
        return tabs.get(Math.max(0, Math.min(activeTab, tabs.size() - 1)));
    }

    private static String title(String url) {
        if (url == null || url.isBlank()) {
            return "New Tab";
        }
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? url : uri.getHost();
        } catch (RuntimeException ignored) {
            return url;
        }
    }

    private static final class Tab {
        private final MCEFBrowser browser;
        private int lastTextureId;

        private Tab(MCEFBrowser browser) {
            this.browser = browser;
        }
    }
}
