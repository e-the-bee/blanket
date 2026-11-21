// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.cli;

/** A usage or input error: reported on stderr, exit code 2. Build errors are {@code Diagnostic}s, not this. */
public final class CliException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CliException(String message) {
        super(message);
    }
}
