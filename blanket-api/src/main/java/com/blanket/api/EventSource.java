// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owned. Listeners run in registration order on the client thread. A listener that throws is logged as
 * {@code [blanket] <modid>: listener for <EVENT> failed} with its stack trace, at most once per listener per 60 seconds,
 * and the other listeners still run (events-and-hooks.md section 2).
 */
@Owned
public final class EventSource<E> {
    /** The shortest time between two failure logs of one listener. */
    static final long LOG_EVERY_MS = 60000L;

    /** Test seam: the time in milliseconds. */
    interface Clock {
        long millis();
    }

    static final Clock SYSTEM = new Clock() {
        public long millis() { return System.currentTimeMillis(); }
    };

    static volatile Clock clock = SYSTEM;
    private static volatile String modId = "?";
    private static final AtomicInteger FAILURES = new AtomicInteger();

    private final String name;
    private final CopyOnWriteArrayList<Entry<E>> listeners = new CopyOnWriteArrayList<Entry<E>>();

    /** A registered listener and when its last failure was logged. */
    private static final class Entry<E> {
        final Listener<E> listener;
        boolean logged;
        long loggedAt;

        Entry(Listener<E> listener) { this.listener = listener; }
    }

    public EventSource() {
        this("?");
    }

    /** {@code name} is the event's name in failure logs ({@code CLIENT_TICK_END}). */
    public EventSource(String name) {
        this.name = name;
    }

    public Subscription register(Listener<E> l) {
        final Entry<E> entry = new Entry<E>(l);
        listeners.add(entry);
        return new Subscription() {
            public void cancel() { listeners.remove(entry); }
        };
    }

    /** Runtime use only. */
    public boolean isEmpty() { return listeners.isEmpty(); }

    /** Runtime use only: a throwing listener is counted, logged (throttled) and does not stop the others. */
    public void fire(E event) {
        for (Entry<E> e : listeners) {
            try {
                e.listener.on(event);
            } catch (Throwable t) {
                FAILURES.incrementAndGet();
                long now = clock.millis();
                if (!e.logged || now - e.loggedAt >= LOG_EVERY_MS) {
                    e.logged = true;
                    e.loggedAt = now;
                    System.err.println("[blanket] " + modId + ": listener for " + name + " failed");
                    t.printStackTrace();
                }
            }
        }
    }

    /** Runtime use only: the mod id in failure logs; the runtime sets it before {@code ClientMod.init()}. */
    public static void modId(String id) { modId = id; }

    /** Runtime use only: how many times a listener of this mod has thrown so far. */
    public static int failures() { return FAILURES.get(); }
}
