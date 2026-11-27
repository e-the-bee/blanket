// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The vanilla dedicated server the client joins: offline, bound to 127.0.0.1 (a server on all interfaces raises a
 * Windows Firewall prompt), a flat world it generates itself on first start, no structures, monsters or animals.
 * The world is kept between runs; the player's saved data is deleted before each start so {@code Dev} joins fresh at
 * the world spawn. Console output goes to a log file.
 */
public final class LocalServer implements AutoCloseable {
    /** Printed by every server version when the world is ready. */
    static final String DONE = "Done (";
    static final Pattern JOINED = Pattern.compile("\\b" + LaunchCommand.PLAYER + " joined the game");
    /** World sub-folders with player data: {@code playerdata} up to 1.21.1, {@code players} on 26.1.2 (run.py). */
    static final List<String> PLAYER_DATA = List.of("playerdata", "players");

    private final Process process;
    private final CountDownLatch done = new CountDownLatch(1);
    private final CountDownLatch joined = new CountDownLatch(1);
    private volatile long joinedAtMillis;

    private LocalServer(Process process) {
        this.process = process;
    }

    /**
     * {@code server.properties}; {@code legacyLevelType} writes {@code FLAT} (1.8.9) instead of {@code minecraft:flat}.
     * Only the first {@code =} separates key and value.
     */
    static String properties(boolean legacyLevelType, int port) {
        return "online-mode=false\nserver-ip=127.0.0.1\nlevel-type=" + (legacyLevelType ? "FLAT" : "minecraft:flat")
                + "\nspawn-protection=0\nserver-port=" + port + "\ngenerate-structures=false\nspawn-monsters=false\n"
                + "spawn-animals=false\nenable-rcon=false\n";
    }

    /** Writes {@code eula.txt} and {@code server.properties} into {@code dir} and deletes saved player data. */
    static void prepare(Path dir, boolean legacyLevelType, int port) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(dir.resolve("server.properties"), properties(legacyLevelType, port));
        for (String sub : PLAYER_DATA) {
            deleteTree(dir.resolve("world").resolve(sub));
        }
    }

    /** Starts {@code java -Xmx1G -jar <jar> nogui} in {@code dir}; console lines are appended to {@code log}. */
    public static LocalServer start(Path java, Path jar, Path dir, boolean legacyLevelType, int port, Path log)
            throws IOException {
        prepare(dir, legacyLevelType, port);
        Process p = new ProcessBuilder(java.toString(), "-Xmx1G", "-jar", jar.toAbsolutePath().toString(), "nogui")
                .directory(dir.toFile()).redirectErrorStream(true).start();
        LocalServer s = new LocalServer(p);
        Writer w = Files.newBufferedWriter(log, StandardCharsets.UTF_8);
        Thread t = new Thread(() -> s.pump(w), "server-log");
        t.setDaemon(true);
        t.start();
        return s;
    }

    private void pump(Writer w) {
        try (w; BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                w.write(line);
                w.write('\n');
                w.flush();
                if (line.contains(DONE)) {
                    done.countDown();
                }
                if (JOINED.matcher(line).find() && joined.getCount() > 0) {
                    joinedAtMillis = System.currentTimeMillis();
                    joined.countDown();
                }
            }
        } catch (IOException ignored) {
            // the server stopped
        }
        done.countDown();
    }

    /** Waits until the world is ready; false when the server exited or the time ran out first. */
    public boolean awaitReady(long seconds) throws InterruptedException {
        return done.await(seconds, TimeUnit.SECONDS) && process.isAlive();
    }

    /** The time the player joined (epoch millis), or 0 before that. */
    public long joinedAtMillis() {
        return joined.getCount() == 0 ? joinedAtMillis : 0;
    }

    /** Types {@code stop} into the console and waits up to a minute, then kills the server. */
    @Override
    public void close() {
        if (process.isAlive()) {
            try {
                PrintWriter in = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
                in.println("stop");
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }
}
