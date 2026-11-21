// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import java.util.List;

/**
 * One identity check of a native listener: the listener returns early unless {@code getter} (a chain from nothing,
 * usually starting with {@code LOAD} of the event) yields the same object as the static field {@code staticField}.
 */
public record Filter(List<Op> getter, Op staticField) {}
