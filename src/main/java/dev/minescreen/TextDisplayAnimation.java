package dev.minescreen;

/**
 * Parameter-only display animation. The server synchronizes the selected value and a shared start
 * game time; every client derives the current frame locally instead of receiving image frames.
 */
public enum TextDisplayAnimation {
    STATIC,
    MARQUEE,
    PULSE,
    ALERT
}
