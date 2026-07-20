package dev.minescreen.client.vnc;

import org.lwjgl.glfw.GLFW;

/** GLFW key code to X11/RFB keysym mapping for keyboard control. */
final class RfbKeySym {
    private RfbKeySym() {
    }

    static int fromGlfw(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            return 'a' + key - GLFW.GLFW_KEY_A;
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            return '0' + key - GLFW.GLFW_KEY_0;
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) {
            return 0xFFBE + key - GLFW.GLFW_KEY_F1;
        }
        return switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> 0xFF08;
            case GLFW.GLFW_KEY_TAB -> 0xFF09;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0xFF0D;
            case GLFW.GLFW_KEY_ESCAPE -> 0xFF1B;
            case GLFW.GLFW_KEY_INSERT -> 0xFF63;
            case GLFW.GLFW_KEY_DELETE -> 0xFFFF;
            case GLFW.GLFW_KEY_HOME -> 0xFF50;
            case GLFW.GLFW_KEY_END -> 0xFF57;
            case GLFW.GLFW_KEY_PAGE_UP -> 0xFF55;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0xFF56;
            case GLFW.GLFW_KEY_LEFT -> 0xFF51;
            case GLFW.GLFW_KEY_UP -> 0xFF52;
            case GLFW.GLFW_KEY_RIGHT -> 0xFF53;
            case GLFW.GLFW_KEY_DOWN -> 0xFF54;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> 0xFFE1;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> 0xFFE2;
            case GLFW.GLFW_KEY_LEFT_CONTROL -> 0xFFE3;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> 0xFFE4;
            case GLFW.GLFW_KEY_LEFT_ALT -> 0xFFE9;
            case GLFW.GLFW_KEY_RIGHT_ALT -> 0xFFEA;
            case GLFW.GLFW_KEY_LEFT_SUPER -> 0xFFEB;
            case GLFW.GLFW_KEY_RIGHT_SUPER -> 0xFFEC;
            case GLFW.GLFW_KEY_SPACE -> 0x20;
            case GLFW.GLFW_KEY_MINUS -> '-';
            case GLFW.GLFW_KEY_EQUAL -> '=';
            case GLFW.GLFW_KEY_LEFT_BRACKET -> '[';
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> ']';
            case GLFW.GLFW_KEY_BACKSLASH -> '\\';
            case GLFW.GLFW_KEY_SEMICOLON -> ';';
            case GLFW.GLFW_KEY_APOSTROPHE -> '\'';
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> '`';
            case GLFW.GLFW_KEY_COMMA -> ',';
            case GLFW.GLFW_KEY_PERIOD -> '.';
            case GLFW.GLFW_KEY_SLASH -> '/';
            default -> 0;
        };
    }

    static int fromCharacter(char character) {
        return character <= 0xFF ? character : 0x01000000 | character;
    }
}
