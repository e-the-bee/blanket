// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** A keyboard key, independent of the target's key codes. */
@Owned
public enum Key {
    A, B, C, D, E, F, G, H,
    I, J, K, L, M, N, O, P,
    Q, R, S, T, U, V, W, X,
    Y, Z, NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5,
    NUM_6, NUM_7, NUM_8, NUM_9, F1, F2, F3, F4,
    F5, F6, F7, F8, F9, F10, F11, F12,
    ESCAPE, ENTER, SPACE, TAB, BACKSPACE, LEFT, RIGHT, UP,
    DOWN, LEFT_SHIFT, RIGHT_SHIFT, LEFT_CONTROL, RIGHT_CONTROL, LEFT_ALT, RIGHT_ALT, NONE
}
