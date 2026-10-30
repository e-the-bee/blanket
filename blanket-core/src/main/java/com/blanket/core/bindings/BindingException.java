// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.bindings;

/** A binding or selector file is malformed. The message names the file and the JSON path. */
public final class BindingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BindingException(String file, String jsonPath, String reason) {
        super(file + ": " + jsonPath + ": " + reason);
    }

    public BindingException(String file, String jsonPath, String reason, Throwable cause) {
        super(file + ": " + jsonPath + ": " + reason, cause);
    }
}
