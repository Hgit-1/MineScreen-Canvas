package dev.minescreen.client.web;

import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.ScreenInputTarget;

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

    int activeTabIndex();

    boolean openTab(String url, boolean activate);

    void activateTab(int index);

    void closeTab(int index);

}
