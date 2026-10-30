// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

import java.util.concurrent.CopyOnWriteArrayList;

/** Owned. Listeners run in registration order on the client thread. */
public final class EventSource<E> {
    private final CopyOnWriteArrayList<Listener<E>> listeners = new CopyOnWriteArrayList<Listener<E>>();

    public Subscription register(final Listener<E> l) {
        listeners.add(l);
        return new Subscription() {
            public void cancel() { listeners.remove(l); }
        };
    }

    /** Runtime use only. */
    public boolean isEmpty() { return listeners.isEmpty(); }

    /** Runtime use only: a throwing listener is logged and does not stop the others. */
    public void fire(E event) {
        for (Listener<E> l : listeners) {
            try {
                l.on(event);
            } catch (Throwable t) {
                System.err.println("[blanket] listener failed: " + t);
                t.printStackTrace();
            }
        }
    }
}
