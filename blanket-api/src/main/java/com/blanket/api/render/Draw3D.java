// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api.render;

import com.blanket.api.Box;
import com.blanket.api.Owned;

/** 3D drawing in world coordinates (rendering-api.md section 2). */
@Owned
public interface Draw3D {
    void boxFill(Box box, int argb);
}
