// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

public interface Listener<E> {
    void on(E event);
}
