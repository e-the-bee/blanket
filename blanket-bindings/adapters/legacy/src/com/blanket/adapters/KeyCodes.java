// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.api.Key;
import org.lwjgl.input.Keyboard;

/**
 * LWJGL 2 key codes as {@link Key} (M2 plan U6: the tables are Java, one per set). The codes are the library's own
 * constants, so javac takes them from the jar. Used by the screen host.
 */
public final class KeyCodes {
    private KeyCodes() {}

    /** The {@link Key} of a key code; {@link Key#NONE} for a key the API does not name. */
    public static Key key(int code) {
        switch (code) {
            case Keyboard.KEY_A: return Key.A;
            case Keyboard.KEY_B: return Key.B;
            case Keyboard.KEY_C: return Key.C;
            case Keyboard.KEY_D: return Key.D;
            case Keyboard.KEY_E: return Key.E;
            case Keyboard.KEY_F: return Key.F;
            case Keyboard.KEY_G: return Key.G;
            case Keyboard.KEY_H: return Key.H;
            case Keyboard.KEY_I: return Key.I;
            case Keyboard.KEY_J: return Key.J;
            case Keyboard.KEY_K: return Key.K;
            case Keyboard.KEY_L: return Key.L;
            case Keyboard.KEY_M: return Key.M;
            case Keyboard.KEY_N: return Key.N;
            case Keyboard.KEY_O: return Key.O;
            case Keyboard.KEY_P: return Key.P;
            case Keyboard.KEY_Q: return Key.Q;
            case Keyboard.KEY_R: return Key.R;
            case Keyboard.KEY_S: return Key.S;
            case Keyboard.KEY_T: return Key.T;
            case Keyboard.KEY_U: return Key.U;
            case Keyboard.KEY_V: return Key.V;
            case Keyboard.KEY_W: return Key.W;
            case Keyboard.KEY_X: return Key.X;
            case Keyboard.KEY_Y: return Key.Y;
            case Keyboard.KEY_Z: return Key.Z;
            case Keyboard.KEY_0: return Key.NUM_0;
            case Keyboard.KEY_1: return Key.NUM_1;
            case Keyboard.KEY_2: return Key.NUM_2;
            case Keyboard.KEY_3: return Key.NUM_3;
            case Keyboard.KEY_4: return Key.NUM_4;
            case Keyboard.KEY_5: return Key.NUM_5;
            case Keyboard.KEY_6: return Key.NUM_6;
            case Keyboard.KEY_7: return Key.NUM_7;
            case Keyboard.KEY_8: return Key.NUM_8;
            case Keyboard.KEY_9: return Key.NUM_9;
            case Keyboard.KEY_F1: return Key.F1;
            case Keyboard.KEY_F2: return Key.F2;
            case Keyboard.KEY_F3: return Key.F3;
            case Keyboard.KEY_F4: return Key.F4;
            case Keyboard.KEY_F5: return Key.F5;
            case Keyboard.KEY_F6: return Key.F6;
            case Keyboard.KEY_F7: return Key.F7;
            case Keyboard.KEY_F8: return Key.F8;
            case Keyboard.KEY_F9: return Key.F9;
            case Keyboard.KEY_F10: return Key.F10;
            case Keyboard.KEY_F11: return Key.F11;
            case Keyboard.KEY_F12: return Key.F12;
            case Keyboard.KEY_ESCAPE: return Key.ESCAPE;
            case Keyboard.KEY_RETURN: return Key.ENTER;
            case Keyboard.KEY_SPACE: return Key.SPACE;
            case Keyboard.KEY_TAB: return Key.TAB;
            case Keyboard.KEY_BACK: return Key.BACKSPACE;
            case Keyboard.KEY_LEFT: return Key.LEFT;
            case Keyboard.KEY_RIGHT: return Key.RIGHT;
            case Keyboard.KEY_UP: return Key.UP;
            case Keyboard.KEY_DOWN: return Key.DOWN;
            case Keyboard.KEY_LSHIFT: return Key.LEFT_SHIFT;
            case Keyboard.KEY_RSHIFT: return Key.RIGHT_SHIFT;
            case Keyboard.KEY_LCONTROL: return Key.LEFT_CONTROL;
            case Keyboard.KEY_RCONTROL: return Key.RIGHT_CONTROL;
            case Keyboard.KEY_LMENU: return Key.LEFT_ALT;
            case Keyboard.KEY_RMENU: return Key.RIGHT_ALT;
            default: return Key.NONE;
        }
    }

    /** The key code of a {@link Key}; the library's "no key" code for {@link Key#NONE}. */
    public static int code(Key key) {
        switch (key) {
            case A: return Keyboard.KEY_A;
            case B: return Keyboard.KEY_B;
            case C: return Keyboard.KEY_C;
            case D: return Keyboard.KEY_D;
            case E: return Keyboard.KEY_E;
            case F: return Keyboard.KEY_F;
            case G: return Keyboard.KEY_G;
            case H: return Keyboard.KEY_H;
            case I: return Keyboard.KEY_I;
            case J: return Keyboard.KEY_J;
            case K: return Keyboard.KEY_K;
            case L: return Keyboard.KEY_L;
            case M: return Keyboard.KEY_M;
            case N: return Keyboard.KEY_N;
            case O: return Keyboard.KEY_O;
            case P: return Keyboard.KEY_P;
            case Q: return Keyboard.KEY_Q;
            case R: return Keyboard.KEY_R;
            case S: return Keyboard.KEY_S;
            case T: return Keyboard.KEY_T;
            case U: return Keyboard.KEY_U;
            case V: return Keyboard.KEY_V;
            case W: return Keyboard.KEY_W;
            case X: return Keyboard.KEY_X;
            case Y: return Keyboard.KEY_Y;
            case Z: return Keyboard.KEY_Z;
            case NUM_0: return Keyboard.KEY_0;
            case NUM_1: return Keyboard.KEY_1;
            case NUM_2: return Keyboard.KEY_2;
            case NUM_3: return Keyboard.KEY_3;
            case NUM_4: return Keyboard.KEY_4;
            case NUM_5: return Keyboard.KEY_5;
            case NUM_6: return Keyboard.KEY_6;
            case NUM_7: return Keyboard.KEY_7;
            case NUM_8: return Keyboard.KEY_8;
            case NUM_9: return Keyboard.KEY_9;
            case F1: return Keyboard.KEY_F1;
            case F2: return Keyboard.KEY_F2;
            case F3: return Keyboard.KEY_F3;
            case F4: return Keyboard.KEY_F4;
            case F5: return Keyboard.KEY_F5;
            case F6: return Keyboard.KEY_F6;
            case F7: return Keyboard.KEY_F7;
            case F8: return Keyboard.KEY_F8;
            case F9: return Keyboard.KEY_F9;
            case F10: return Keyboard.KEY_F10;
            case F11: return Keyboard.KEY_F11;
            case F12: return Keyboard.KEY_F12;
            case ESCAPE: return Keyboard.KEY_ESCAPE;
            case ENTER: return Keyboard.KEY_RETURN;
            case SPACE: return Keyboard.KEY_SPACE;
            case TAB: return Keyboard.KEY_TAB;
            case BACKSPACE: return Keyboard.KEY_BACK;
            case LEFT: return Keyboard.KEY_LEFT;
            case RIGHT: return Keyboard.KEY_RIGHT;
            case UP: return Keyboard.KEY_UP;
            case DOWN: return Keyboard.KEY_DOWN;
            case LEFT_SHIFT: return Keyboard.KEY_LSHIFT;
            case RIGHT_SHIFT: return Keyboard.KEY_RSHIFT;
            case LEFT_CONTROL: return Keyboard.KEY_LCONTROL;
            case RIGHT_CONTROL: return Keyboard.KEY_RCONTROL;
            case LEFT_ALT: return Keyboard.KEY_LMENU;
            case RIGHT_ALT: return Keyboard.KEY_RMENU;
            default: return Keyboard.KEY_NONE;
        }
    }
}
