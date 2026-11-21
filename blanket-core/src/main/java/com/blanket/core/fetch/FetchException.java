// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.fetch;

/** A download failed, a checksum did not match, or an offline lookup missed the cache. */
public final class FetchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final boolean transportFailure;

    /** A non-transport failure (HTTP status error, checksum mismatch, unreadable cache file). */
    public FetchException(String message) {
        this(message, null, false);
    }

    public FetchException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public FetchException(String message, Throwable cause, boolean transportFailure) {
        super(message, cause);
        this.transportFailure = transportFailure;
    }

    /**
     * True when the data could not be reached at all: connection, DNS or timeout error, or an offline-mode
     * cache miss. False for an HTTP status error or a checksum mismatch, which point at bad data or a bad ref.
     */
    public boolean transportFailure() {
        return transportFailure;
    }
}
