// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** events-and-hooks.md section 2: a throwing listener is counted, logged at most once per 60 s, and the others still run. */
class EventSourceTest {
    private final long[] now = {0};
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream oldErr;

    @BeforeEach
    void fakeClockAndStderr() {
        EventSource.clock = new EventSource.Clock() {
            public long millis() { return now[0]; }
        };
        oldErr = System.err;
        System.setErr(new PrintStream(err, true));
        EventSource.modId("demo");
    }

    @AfterEach
    void restore() {
        EventSource.clock = EventSource.SYSTEM;
        System.setErr(oldErr);
    }

    private int logLines() {
        int n = 0;
        for (String line : err.toString().split("\\R")) {
            if (line.equals("[blanket] demo: listener for CLIENT_TICK_END failed")) n++;
        }
        return n;
    }

    @Test
    void aFailingListenerIsLoggedOncePerMinuteCountedEveryTimeAndTheOthersRun() {
        EventSource<String> source = new EventSource<String>("CLIENT_TICK_END");
        final List<String> seen = new ArrayList<String>();
        source.register(new Listener<String>() {
            public void on(String e) { seen.add("a:" + e); }
        });
        source.register(new Listener<String>() {
            public void on(String e) { throw new IllegalStateException("boom " + e); }
        });
        source.register(new Listener<String>() {
            public void on(String e) { seen.add("c:" + e); }
        });
        int before = EventSource.failures();

        source.fire("1");                       // t = 0: logged
        now[0] = 30000;
        source.fire("2");                       // 30 s later: counted, not logged
        now[0] = 59999;
        source.fire("3");                       // still inside the minute
        now[0] = 60000;
        source.fire("4");                       // a minute after the first log: logged again

        assertEquals(Arrays.asList("a:1", "c:1", "a:2", "c:2", "a:3", "c:3", "a:4", "c:4"), seen);
        assertEquals(4, EventSource.failures() - before);
        assertEquals(2, logLines());
        assertEquals(true, err.toString().contains("java.lang.IllegalStateException: boom 1"), "the stack trace is logged");
        assertEquals(false, err.toString().contains("boom 2"), "a throttled failure prints no stack trace");
    }

    @Test
    void eachListenerHasItsOwnThrottle() {
        EventSource<String> source = new EventSource<String>("CLIENT_TICK_END");
        Listener<String> bad = new Listener<String>() {
            public void on(String e) { throw new RuntimeException(e); }
        };
        source.register(bad);
        source.fire("x");
        source.register(bad);                   // a second registration logs its own first failure
        source.fire("y");
        assertEquals(2, logLines());
    }

    @Test
    void aCancelledListenerNoLongerRuns() {
        EventSource<String> source = new EventSource<String>("CLIENT_TICK_END");
        final List<String> seen = new ArrayList<String>();
        Subscription s = source.register(new Listener<String>() {
            public void on(String e) { seen.add(e); }
        });
        source.fire("1");
        s.cancel();
        source.fire("2");
        assertEquals(Arrays.asList("1"), seen);
        assertEquals(true, source.isEmpty());
    }

    @Test
    void everyEventsConstantCarriesItsName() {
        Subscription s = Events.WORLD_JOIN.register(new Listener<WorldJoin>() {
            public void on(WorldJoin e) { throw new RuntimeException("join"); }
        });
        try {
            Events.WORLD_JOIN.fire(WorldJoin.INSTANCE);
        } finally {
            s.cancel();
        }
        assertEquals(true, err.toString().contains("[blanket] demo: listener for WORLD_JOIN failed"), err.toString());
    }
}
