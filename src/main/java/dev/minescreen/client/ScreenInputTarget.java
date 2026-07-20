package dev.minescreen.client;

/** Common interactive surface implemented by Chromium and RFB sessions. */
public interface ScreenInputTarget {
    int inputWidth();

    int inputHeight();

    void focus(boolean focused);

    void mouseMove(int x, int y);

    void mousePress(int x, int y, int button);

    void mouseRelease(int x, int y, int button);

    void mouseWheel(int x, int y, double amount, int modifiers);

    void keyPress(int keyCode, long scanCode, int modifiers);

    void keyRelease(int keyCode, long scanCode, int modifiers);

    void keyTyped(char character, int modifiers);
}
