package dev.minescreen.client.web;

import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.ScreenInputTarget;
import dev.minescreen.client.content.WebSplitLayout;

public interface BrowserSession extends ScreenContentSession, ScreenInputTarget {
    record TabInfo(int index, String title, String url, boolean active) {
    }

    String currentUrl();

    boolean navigate(String url);

    void goBack();

    void goForward();

    void reload();

    boolean canGoBack();

    boolean canGoForward();

    java.util.List<TabInfo> tabs();

    /** URLs to persist across backend recreation; may include lazily-created background tabs. */
    default java.util.List<String> restorableUrls() {
        return tabs().stream().map(TabInfo::url).toList();
    }

    default String restorableActiveUrl() {
        return currentUrl();
    }

    int activeTabIndex();

    boolean openTab(String url, boolean activate);

    void activateTab(int index);

    void closeTab(int index);

    WebSplitLayout splitLayout();

    void setSplitLayout(WebSplitLayout layout);

    /** True only after this page requested the browser Pointer Lock API. */
    default boolean pointerLockRequested() {
        return false;
    }

    /** Raw mouse delta used while Minecraft camera rotation is frozen. */
    default void relativeMouseMove(double deltaX, double deltaY) {
    }

    /** Releases both MineScreen's lock state and the page's DOM pointer lock. */
    default void cancelPointerLock() {
    }

}
