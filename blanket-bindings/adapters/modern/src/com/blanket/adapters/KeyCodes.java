// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.adapters;

import com.blanket.api.Key;
import org.lwjgl.glfw.GLFW;

/**
 * GLFW key codes as {@link Key} (M2 plan U6: the tables are Java, one per set). The codes are the library's own
 * constants, so javac takes them from the jar. Used by the screen host.
 */
public final class KeyCodes {
    private KeyCodes() {}

    /** The {@link Key} of a key code; {@link Key#NONE} for a key the API does not name. */
    public static Key key(int code) {
        switch (code) {
            case GLFW.GLFW_KEY_A: return Key.A;
            case GLFW.GLFW_KEY_B: return Key.B;
            case GLFW.GLFW_KEY_C: return Key.C;
            case GLFW.GLFW_KEY_D: return Key.D;
            case GLFW.GLFW_KEY_E: return Key.E;
            case GLFW.GLFW_KEY_F: return Key.F;
            case GLFW.GLFW_KEY_G: return Key.G;
            case GLFW.GLFW_KEY_H: return Key.H;
            case GLFW.GLFW_KEY_I: return Key.I;
            case GLFW.GLFW_KEY_J: return Key.J;
            case GLFW.GLFW_KEY_K: return Key.K;
            case GLFW.GLFW_KEY_L: return Key.L;
            case GLFW.GLFW_KEY_M: return Key.M;
            case GLFW.GLFW_KEY_N: return Key.N;
            case GLFW.GLFW_KEY_O: return Key.O;
            case GLFW.GLFW_KEY_P: return Key.P;
            case GLFW.GLFW_KEY_Q: return Key.Q;
            case GLFW.GLFW_KEY_R: return Key.R;
            case GLFW.GLFW_KEY_S: return Key.S;
            case GLFW.GLFW_KEY_T: return Key.T;
            case GLFW.GLFW_KEY_U: return Key.U;
            case GLFW.GLFW_KEY_V: return Key.V;
            case GLFW.GLFW_KEY_W: return Key.W;
            case GLFW.GLFW_KEY_X: return Key.X;
            case GLFW.GLFW_KEY_Y: return Key.Y;
            case GLFW.GLFW_KEY_Z: return Key.Z;
            case GLFW.GLFW_KEY_0: return Key.NUM_0;
            case GLFW.GLFW_KEY_1: return Key.NUM_1;
            case GLFW.GLFW_KEY_2: return Key.NUM_2;
            case GLFW.GLFW_KEY_3: return Key.NUM_3;
            case GLFW.GLFW_KEY_4: return Key.NUM_4;
            case GLFW.GLFW_KEY_5: return Key.NUM_5;
            case GLFW.GLFW_KEY_6: return Key.NUM_6;
            case GLFW.GLFW_KEY_7: return Key.NUM_7;
            case GLFW.GLFW_KEY_8: return Key.NUM_8;
            case GLFW.GLFW_KEY_9: return Key.NUM_9;
            case GLFW.GLFW_KEY_F1: return Key.F1;
            case GLFW.GLFW_KEY_F2: return Key.F2;
            case GLFW.GLFW_KEY_F3: return Key.F3;
            case GLFW.GLFW_KEY_F4: return Key.F4;
            case GLFW.GLFW_KEY_F5: return Key.F5;
            case GLFW.GLFW_KEY_F6: return Key.F6;
            case GLFW.GLFW_KEY_F7: return Key.F7;
            case GLFW.GLFW_KEY_F8: return Key.F8;
            case GLFW.GLFW_KEY_F9: return Key.F9;
            case GLFW.GLFW_KEY_F10: return Key.F10;
            case GLFW.GLFW_KEY_F11: return Key.F11;
            case GLFW.GLFW_KEY_F12: return Key.F12;
            case GLFW.GLFW_KEY_ESCAPE: return Key.ESCAPE;
            case GLFW.GLFW_KEY_ENTER: return Key.ENTER;
            case GLFW.GLFW_KEY_SPACE: return Key.SPACE;
            case GLFW.GLFW_KEY_TAB: return Key.TAB;
            case GLFW.GLFW_KEY_BACKSPACE: return Key.BACKSPACE;
            case GLFW.GLFW_KEY_LEFT: return Key.LEFT;
            case GLFW.GLFW_KEY_RIGHT: return Key.RIGHT;
            case GLFW.GLFW_KEY_UP: return Key.UP;
            case GLFW.GLFW_KEY_DOWN: return Key.DOWN;
            case GLFW.GLFW_KEY_LEFT_SHIFT: return Key.LEFT_SHIFT;
            case GLFW.GLFW_KEY_RIGHT_SHIFT: return Key.RIGHT_SHIFT;
            case GLFW.GLFW_KEY_LEFT_CONTROL: return Key.LEFT_CONTROL;
            case GLFW.GLFW_KEY_RIGHT_CONTROL: return Key.RIGHT_CONTROL;
            case GLFW.GLFW_KEY_LEFT_ALT: return Key.LEFT_ALT;
            case GLFW.GLFW_KEY_RIGHT_ALT: return Key.RIGHT_ALT;
            default: return Key.NONE;
        }
    }
}
