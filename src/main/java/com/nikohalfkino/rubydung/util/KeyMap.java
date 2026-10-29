package com.nikohalfkino.rubydung.util;

import org.lwjgl.input.Keyboard;

/**
 * Key mapping utility providing clean aliases for LWJGL keyboard codes.
 */
public final class KeyMap {

    private KeyMap() {}

    // Movement
    public static final int W = Keyboard.KEY_W;
    public static final int A = Keyboard.KEY_A;
    public static final int S = Keyboard.KEY_S;
    public static final int D = Keyboard.KEY_D;

    public static final int R = Keyboard.KEY_R;

    public static final int UP = Keyboard.KEY_UP;
    public static final int DOWN = Keyboard.KEY_DOWN;
    public static final int LEFT = Keyboard.KEY_LEFT;
    public static final int RIGHT = Keyboard.KEY_RIGHT;

    // Actions & Modifiers
    public static final int SPACE = Keyboard.KEY_SPACE;
    public static final int LSHIFT = Keyboard.KEY_LSHIFT;
    public static final int RSHIFT = Keyboard.KEY_RSHIFT;
    public static final int SHIFT = Keyboard.KEY_LSHIFT; // Default shortcut
    public static final int LCONTROL = Keyboard.KEY_LCONTROL;
    public static final int RCONTROL = Keyboard.KEY_RCONTROL;
    public static final int CTRL = Keyboard.KEY_LCONTROL;  // Default shortcut
    public static final int LMENU = Keyboard.KEY_LMENU;   // Left Alt
    public static final int RMENU = Keyboard.KEY_RMENU;   // Right Alt
    public static final int ALT = Keyboard.KEY_LMENU;     // Default shortcut
    public static final int LWIN = Keyboard.KEY_LMETA;    // Windows / Super / Command key
    public static final int RWIN = Keyboard.KEY_RMETA;

    // Navigation & UI
    public static final int ESCAPE = Keyboard.KEY_ESCAPE;
    public static final int ESC = Keyboard.KEY_ESCAPE;
    public static final int RETURN = Keyboard.KEY_RETURN;
    public static final int ENTER = Keyboard.KEY_RETURN;
    public static final int NUMPAD_ENTER = Keyboard.KEY_NUMPADENTER;
    public static final int BACKSPACE = Keyboard.KEY_BACK;
    public static final int TAB = Keyboard.KEY_TAB;
    public static final int GRAVE = Keyboard.KEY_GRAVE;   // Tilde / Console key (`)

    // Function keys
    public static final int F1 = Keyboard.KEY_F1;
    public static final int F2 = Keyboard.KEY_F2;
    public static final int F3 = Keyboard.KEY_F3;
    public static final int F4 = Keyboard.KEY_F4;
    public static final int F5 = Keyboard.KEY_F5;
    public static final int F11 = Keyboard.KEY_F11;

    // Hotbar / Numbers
    public static final int ZERO  = Keyboard.KEY_0;
    public static final int ONE   = Keyboard.KEY_1;
    public static final int TWO   = Keyboard.KEY_2;
    public static final int THREE = Keyboard.KEY_3;
    public static final int FOUR  = Keyboard.KEY_4;
    public static final int FIVE  = Keyboard.KEY_5;
    public static final int SIX   = Keyboard.KEY_6;
    public static final int SEVEN = Keyboard.KEY_7;
    public static final int EIGHT = Keyboard.KEY_8;
    public static final int NINE  = Keyboard.KEY_9;
    // Utility methods
    public static boolean isDown(int key) {
        return Keyboard.isKeyDown(key);
    }
}