// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.testsupport;

import com.blanket.core.fetch.FetchException;
import org.junit.jupiter.api.Assumptions;

/** Skip rules for tests that download real data: only an unreachable network skips; bad data fails. */
public final class RealData {

    private RealData() {
    }

    /**
     * Aborts the test (skip) when a {@link FetchException} with {@code transportFailure()} is in the cause chain of
     * {@code t}; otherwise returns {@code t} as an unchecked exception for the caller to throw.
     */
    public static RuntimeException skipIfOffline(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof FetchException f && f.transportFailure()) {
                Assumptions.abort("data unavailable (offline?): " + f.getMessage());
            }
        }
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }
}
