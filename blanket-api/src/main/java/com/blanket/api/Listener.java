// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

@Owned
public interface Listener<E> {
    void on(E event);
}
