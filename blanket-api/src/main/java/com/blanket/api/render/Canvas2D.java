// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api.render;

import com.blanket.api.Owned;

/** 2D drawing in GUI-scaled pixels, origin top left. */
@Owned
public interface Canvas2D {
    void fillRect(float x, float y, float w, float h, int argb);
}
