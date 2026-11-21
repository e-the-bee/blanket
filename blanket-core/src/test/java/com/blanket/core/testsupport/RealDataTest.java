// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.testsupport;

import com.blanket.core.fetch.ArtifactFetcher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealDataTest {

    @TempDir
    Path cache;

    @Test
    void httpErrorIsRethrownNotSkipped() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x.jar");
            RuntimeException thrown = null;
            try {
                new ArtifactFetcher(cache, false).fetch(uri, null);
            } catch (RuntimeException e) {
                thrown = e;
            }
            RuntimeException original = thrown;
            assertSame(original, RealData.skipIfOffline(original));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectionRefusedAborts() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        URI uri = URI.create("http://127.0.0.1:" + port + "/x.jar");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> new ArtifactFetcher(cache, false).fetch(uri, null));
        assertThrows(TestAbortedException.class, () -> RealData.skipIfOffline(thrown));
    }

    @Test
    void foreignExceptionIsRethrown() {
        IllegalStateException e = new IllegalStateException("x");
        assertSame(e, RealData.skipIfOffline(e));
    }
}
