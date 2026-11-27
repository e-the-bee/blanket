// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

/** What kind of screen opened (api-surface.md section 3.12); {@link #NONE} when the screen closes. */
@Owned
public enum ScreenKind {
    NONE, TITLE, PAUSE, CHAT, INVENTORY, CONTAINER, OTHER
}
