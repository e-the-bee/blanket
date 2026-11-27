// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

/** A provisioning or usage problem that stops a run before a verdict exists (exit code 2). */
public final class HarnessException extends Exception {
    public HarnessException(String message) {
        super(message);
    }

    public HarnessException(String message, Throwable cause) {
        super(message, cause);
    }
}
