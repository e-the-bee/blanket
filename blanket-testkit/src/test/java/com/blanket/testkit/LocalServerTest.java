// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalServerTest {
    @TempDir
    Path tmp;

    @Test
    void propertiesBindToLoopbackOffline() {
        assertEquals("""
                online-mode=false
                server-ip=127.0.0.1
                level-type=FLAT
                spawn-protection=0
                server-port=25589
                generate-structures=false
                spawn-monsters=false
                spawn-animals=false
                enable-rcon=false
                """, LocalServer.properties(true, 25589));
        assertTrue(LocalServer.properties(false, 25592).contains("\nlevel-type=minecraft:flat\n"));
    }

    @Test
    void prepareKeepsTheWorldButNotThePlayer() throws Exception {
        Path dir = tmp.resolve("26.1.2");
        Path world = dir.resolve("world");
        Files.createDirectories(world.resolve("players").resolve("data"));
        Files.writeString(world.resolve("players").resolve("data").resolve("x.dat"), "p");
        Files.createDirectories(world.resolve("playerdata"));
        Files.writeString(world.resolve("level.dat"), "w");
        LocalServer.prepare(dir, false, 25592);
        assertEquals("eula=true\n", Files.readString(dir.resolve("eula.txt")));
        assertTrue(Files.readString(dir.resolve("server.properties")).contains("server-port=25592"));
        assertTrue(Files.exists(world.resolve("level.dat")));
        assertFalse(Files.exists(world.resolve("players")));
        assertFalse(Files.exists(world.resolve("playerdata")));
    }

    @Test
    void joinLine() {
        assertTrue(LocalServer.JOINED.matcher("[12:00:01] [Server thread/INFO]: Dev joined the game").find());
        assertFalse(LocalServer.JOINED.matcher("[12:00:01] [Server thread/INFO]: NotDev joined the game").find());
    }

    @Test
    void peacefulBeforeTheClientStarts() {
        assertEquals(java.util.List.of("difficulty peaceful"), LocalServer.READY_COMMANDS);
    }
}
