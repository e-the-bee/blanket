// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.fetch;

/** A download failed, a checksum did not match, or an offline lookup missed the cache. */
public final class FetchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FetchException(String message) {
        super(message);
    }

    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
